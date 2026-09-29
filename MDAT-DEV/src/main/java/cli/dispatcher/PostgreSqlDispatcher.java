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
        if ("revshell".equals(tool) || "list-files".equals(tool) || "read".equals(tool)
                || "write".equals(tool) || "upload".equals(tool) || "download".equals(tool)
                || "rm".equals(tool) || "mkdir".equals(tool)) {
            return m3a(tool, dbType()).withId(id);
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
