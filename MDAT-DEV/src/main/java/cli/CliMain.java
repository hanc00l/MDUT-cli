package cli;

import cli.dispatcher.BaseDispatcher;
import cli.dispatcher.Dispatcher;
import Util.Reporter;
import Util.Utils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Socket;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * CLI 入口：全局 flag → 命令分发 → 单行 JSON 信封 → 审计落账 → 退出码。
 *
 * 纪律（AGENTS.md §1.4）：
 *  - stdout 恰好一行 JSON（信封经 FileDescriptor.out 直写真实 stdout；
 *    System.out 被重定向到 stderr，深层杂散 println 不污染信封）；
 *  - 日志一律 stderr（[*] 时间戳格式沿用 Utils.log）；
 *  - 退出码 0/2/3/4/5；看门狗超时兜底 exit 4。
 */
public class CliMain {

    public static final String VERSION = "v2.1.1-cli.1.0.0";

    /** 真实 stdout（信封专用） */
    private static final PrintStream STDOUT = newPrintStreamUtf8();

    private static PrintStream newPrintStreamUtf8() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new PrintStream(new FileOutputStream(FileDescriptor.out));
        }
    }

    /** 看门狗已发信封标记（防重复输出） */
    private static volatile boolean envelopePrinted = false;

    public static void main(String[] args) {
        int exit = run(args);
        System.exit(exit);
    }

    // ---------------- 看门狗 ----------------

    private static volatile Timer watchdog;

    private static void startWatchdog(int timeoutSec, final String task, final String tool, final boolean textMode) {
        final long limitMs = (Math.max(timeoutSec, 1) + 5L) * 1000L;
        watchdog = new Timer("mdut-watchdog", true);
        watchdog.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!envelopePrinted) {
                    envelopePrinted = true;
                    emitRaw(textMode, Envelope.failure(task, tool, null,
                            "命令整体超时（看门狗 " + limitMs + "ms）",
                            "放宽 --timeout 或检查目标网络；Dao 连接超时与此上限同源"), ExitCode.TIMEOUT);
                    System.err.println(Utils.log("[!] 看门狗触发，强制退出(4)"));
                    Runtime.getRuntime().halt(ExitCode.TIMEOUT);
                }
            }
        }, limitMs);
    }

    private static void stopWatchdog() {
        if (watchdog != null) {
            watchdog.cancel();
        }
    }

    // ---------------- 主流程 ----------------

    static int run(String[] args) {
        final long begin = System.currentTimeMillis();
        envelopePrinted = false;
        auditHolder = null;
        storeHolder = null;
        lastExitCode = ExitCode.OK;
        // 深层杂散 System.out 一律转 stderr（信封走 STDOUT）
        System.setOut(System.err);

        final String[] taskHolder = {""};
        final String[] toolHolder = {""};
        final boolean[] textHolder = {false};
        try {
            int exit = runInner(args, taskHolder, toolHolder, textHolder);
            lastExitCode = exit;
            return exit;
        } catch (Args.UsageException ue) {
            lastExitCode = ExitCode.USAGE;
            return emitFail(taskHolder[0], toolHolder[0], textHolder[0], ExitCode.USAGE, ue.error, ue.hint);
        } catch (BaseDispatcher.UsageMsg um) {
            lastExitCode = ExitCode.USAGE;
            return emitFail(taskHolder[0], toolHolder[0], textHolder[0], ExitCode.USAGE, um.getMessage(),
                    "mdut list 查看当前连接");
        } catch (IllegalArgumentException iae) {
            lastExitCode = ExitCode.USAGE;
            return emitFail(taskHolder[0], toolHolder[0], textHolder[0], ExitCode.USAGE, iae.getMessage(), "修正参数后重试");
        } catch (Throwable t) {
            int code = ExitCode.classifyTargetException(t);
            lastExitCode = code;
            String msg = String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT);
            String hint;
            if (msg.contains("unknownhost") || msg.contains("no such host") || msg.contains("unresolved")) {
                hint = "目标主机名本地解析失败；经 --proxy 内网目标建议改用 IP，或在本机 hosts 落映射";
            } else if (code == ExitCode.TIMEOUT) {
                hint = "放宽 --timeout 后重试；或确认目标可达";
            } else {
                hint = "检查目标/凭据/网络；深排可用 --format text";
            }
            return emitFail(taskHolder[0], toolHolder[0], textHolder[0], code, firstLine(t), hint);
        } finally {
            try {
                if (auditHolder == null && !taskHolder[0].isEmpty()
                        && ConnectionStore.validTaskName(taskHolder[0])) {
                    // 前置失败（task 库未开）也落账：审计不断链；非法 task 名拒绝落账（路径穿越防线）
                    auditHolder = new AuditLog(ConnectionStore.tasksRoot(ConnectionStore.jarHome())
                            + File.separator + taskHolder[0]);
                }
                if (auditHolder != null) {
                    auditHolder.append(taskHolder[0], toolHolder[0], args, lastExitCode,
                            System.currentTimeMillis() - begin);
                }
            } catch (Throwable ignore) {
                // 审计兜底失败不再扰动退出码（AuditLog 内部已留痕 stderr）
            }
            if (storeHolder != null) {
                storeHolder.close();
            }
        }
    }

    private static AuditLog auditHolder;
    private static ConnectionStore storeHolder;
    private static int lastExitCode = ExitCode.OK;

    private static int runInner(String[] args, String[] taskHolder, String[] toolHolder, boolean[] textHolder) throws Exception {
        Args.Parsed p = Args.parse(args);

        // ---- 全局 flag ----
        boolean textMode = "text".equalsIgnoreCase(p.flags.getOrDefault("format", "json"));
        textHolder[0] = textMode;
        String timeoutStr = p.flags.getOrDefault("timeout", String.valueOf(5));
        final int timeoutSec;
        try {
            timeoutSec = Integer.parseInt(timeoutStr);
            if (timeoutSec <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            return emitFail(taskHolder[0], "", textMode, ExitCode.USAGE, "非法 --timeout: " + timeoutStr, "--timeout 取正整数秒");
        }
        String proxy = p.flags.getOrDefault("proxy", "");
        if (!proxy.isEmpty()) {
            applyProxy(proxy);
        }
        String taskName = ConnectionStore.resolveTaskName(p.flags.get("task"));
        taskHolder[0] = taskName;
        if (!ConnectionStore.validTaskName(taskName)) {
            return emitFail(taskName, "", textMode, ExitCode.USAGE, "非法 task 名: " + taskName, "允许 [A-Za-z0-9_-]{1,64}");
        }

        // ---- help / version（不需 task 库） ----
        if (p.flags.containsKey("help") || "help".equals(p.command)) {
            String target;
            if ("help".equals(p.command)) {
                target = p.positionals.isEmpty() ? null : p.positionals.get(0);
            } else {
                target = p.command; // <cmd> --help
            }
            String h = target == null ? Args.helpAll() : Args.helpCommand(target);
            if (h == null) {
                return emitFail(taskName, "help", textMode, ExitCode.USAGE, "未知命令: " + target, "mdut --help 查看命令清单");
            }
            STDOUT.print(h);
            return ExitCode.OK;
        }
        if (p.flags.containsKey("version") || "version".equals(p.command)) {
            JSONObject d = new JSONObject();
            d.put("version", VERSION);
            d.put("java", System.getProperty("java.version"));
            d.put("java_vendor", System.getProperty("java.vendor"));
            emit(taskName, "version", null, textMode,
                    Envelope.success(taskName, "version", null,
                            "mdut " + VERSION + " (Java " + System.getProperty("java.version") + ")", d));
            return ExitCode.OK;
        }

        // ---- 无命令 → 用法 + 免责（exit 2） ----
        if (p.command == null) {
            STDOUT.print(Args.helpAll());
            return ExitCode.USAGE;
        }
        toolHolder[0] = p.command;

        String jarHome = ConnectionStore.jarHome();

        if ("task".equals(p.command)) {
            String sub = p.positionals.get(0);
            if ("path".equals(sub)) {
                String dir = ConnectionStore.tasksRoot(jarHome) + File.separator + taskName;
                JSONObject d = new JSONObject();
                d.put("task", taskName);
                d.put("dir", dir);
                emit(taskName, "task path", null, textMode, Envelope.success(taskName, "task path", null, dir, d));
                return ExitCode.OK;
            }
            if ("list".equals(sub)) {
                JSONArray tasks = ConnectionStore.listTasks(ConnectionStore.tasksRoot(jarHome));
                JSONObject d = new JSONObject();
                d.put("tasks", tasks);
                emit(taskName, "task list", null, textMode,
                        Envelope.success(taskName, "task list", null, tasks.toString(), d));
                return ExitCode.OK;
            }
            return emitFail(taskName, "task", textMode, ExitCode.USAGE, "task 子命令未知: " + sub, "可用: task list | task path");
        }

        // ---- task 库 ----
        ConnectionStore store = new ConnectionStore(ConnectionStore.tasksRoot(jarHome), taskName);
        storeHolder = store;
        Ctx ctx = new Ctx();
        ctx.store = store;
        ctx.task = taskName;
        ctx.jarHome = jarHome;
        ctx.timeoutSec = timeoutSec;
        ctx.enc = p.flags.getOrDefault("c", p.flags.getOrDefault("enc", ""));
        ctx.textMode = textMode;
        ctx.reporter = new StderrReporter();
        AuditLog audit = new AuditLog(store.getTaskDir());
        auditHolder = audit;

        Args.Spec spec = Args.findSpec(p.command);
        if (spec == null) {
            return emitFail(taskName, p.command, textMode, ExitCode.USAGE, "未知命令: " + p.command, "mdut --help 查看命令清单");
        }
        Args.validate(spec, p);

        // ---- 写锁（变异类命令，docs/3 §5） ----
        File lockFile = new File(store.getTaskDir(), ".lock");
        java.io.RandomAccessFile lockRAF = null;
        java.nio.channels.FileLock lock = null;
        if (spec.mutating) {
            lockRAF = new java.io.RandomAccessFile(lockFile, "rw");
            lock = lockRAF.getChannel().tryLock();
            if (lock == null) {
                return emitFail(taskName, p.command, textMode, ExitCode.LOCKED,
                        "task 写锁被其它进程持有: " + lockFile.getPath(),
                        "等待其它进程结束，或用 --task 拆分任务");
            }
        }

        // 看门狗预算：redis 主从链（rogue 双 timeout sleep + 同步 + moduleLoad + eval）结构性超过
        // timeout+5s，按 3×timeout+15s 放宽（docs/3 §5 语义不变：看门狗仍是硬上限兜底）
        int watchdogSec = timeoutSec;
        if ("exec".equals(p.command)
                && (p.flags.containsKey("vps-host") || p.flags.containsKey("vps-port"))) {
            watchdogSec = timeoutSec * 3 + 15;
        }
        startWatchdog(watchdogSec, taskName, p.command, textMode);
        try {
            int exit = dispatch(p, spec, ctx, taskName);
            lastExitCode = exit;
            return exit;
        } catch (Throwable t) {
            int code = ExitCode.classifyTargetException(t);
            String hint = code == ExitCode.TIMEOUT
                    ? "放宽 --timeout 后重试；或确认目标可达"
                    : "检查目标/凭据/网络；深排可用 --format text";
            int exit = emitFail(taskName, p.command, textMode, code, firstLine(t), hint);
            lastExitCode = exit;
            return exit;
        } finally {
            stopWatchdog();
            if (lock != null) {
                lock.release();
            }
            if (lockRAF != null) {
                lockRAF.close();
            }
        }
    }

    // ---- 命令分发 ----

    private static int dispatch(Args.Parsed p, Args.Spec spec, Ctx ctx, String taskName) throws Exception {
        switch (p.command) {
            case "add":
                return doAdd(p, ctx, taskName);
            case "list": {
                JSONArray conns = ctx.store.listConnections(p.flags.get("group"));
                JSONObject d = new JSONObject();
                d.put("conns", conns);
                emit(taskName, "list", null, ctx.textMode, Envelope.success(taskName, "list", null, conns.toString(), d));
                return ExitCode.OK;
            }
            case "delete": {
                String id = p.positionals.get(0);
                int n = ctx.store.deleteById(id);
                if (n == 0) {
                    return emitFail(taskName, "delete", ctx.textMode, ExitCode.USAGE,
                            "连接不存在: id=" + id, "mdut list 查看当前连接");
                }
                emit(taskName, "delete", id, ctx.textMode,
                        Envelope.success(taskName, "delete", id, "已删除连接 " + id, null));
                return ExitCode.OK;
            }
            case "add-group": {
                ctx.store.addGroup(p.positionals.get(0));
                emit(taskName, "add-group", null, ctx.textMode,
                        Envelope.success(taskName, "add-group", null, "分组已建: " + p.positionals.get(0), null));
                return ExitCode.OK;
            }
            case "delete-group": {
                int n = ctx.store.deleteGroup(p.positionals.get(0));
                if (n == 0) {
                    return emitFail(taskName, "delete-group", ctx.textMode, ExitCode.USAGE,
                            "分组不存在: " + p.positionals.get(0), "确认分组名后重试");
                }
                emit(taskName, "delete-group", null, ctx.textMode,
                        Envelope.success(taskName, "delete-group", null, "分组已删: " + p.positionals.get(0), null));
                return ExitCode.OK;
            }
            case "doctor":
                return doDoctor(ctx, taskName);
            default: {
                // --id 类命令 → 库分发器
                String id = p.flags.get("id");
                Map<String, String> rec = ctx.store.findById(id);
                if (rec == null) {
                    return emitFail(taskName, p.command, ctx.textMode, ExitCode.USAGE,
                            "连接不存在: id=" + id, "mdut list 查看当前连接");
                }
                // 存量代理回放：add 时经 --proxy 登记的连接，后续命令自动套用（--proxy 显式传入时覆盖）
                if (!System.getProperties().containsKey("mdut.proxy")
                        && "1".equals(rec.get("isproxy")) && "socks5".equals(rec.get("proxytype"))
                        && !rec.get("proxyaddress").isEmpty()) {
                    String stored = "socks5://" + rec.get("proxyaddress") + ":" + rec.get("proxyport");
                    if (!rec.get("proxyusername").isEmpty()) {
                        stored = "socks5://" + rec.get("proxyusername") + ":" + rec.get("proxypassword")
                                + "@" + rec.get("proxyaddress") + ":" + rec.get("proxyport");
                    }
                    applyProxy(stored);
                }
                String type = rec.get("databasetype");
                Dispatcher d = Dispatcher.forType(type);
                if (d == null) {
                    return emitFail(taskName, p.command, ctx.textMode, ExitCode.USAGE,
                            "不支持的库类型: " + type, "支持: mysql|mssql|postgresql|oracle|redis|mongodb(M3a)");
                }
                Result r = d.handle(p.command, p, ctx).withId(id);
                emit(taskName, p.command, r.id == null ? id : r.id, ctx.textMode,
                        r.ok ? Envelope.success(taskName, p.command, id, r.text, r.data)
                                : Envelope.failure(taskName, p.command, id, r.error, r.hint));
                return r.exit;
            }
        }
    }

    // ---- add ----

    private static int doAdd(Args.Parsed p, Ctx ctx, String taskName) throws Exception {
        String type = p.positionals.get(0);
        String host = p.flags.get("host");
        String port = p.flags.get("port");
        String user = p.flags.getOrDefault("user", "");
        String pass = p.flags.getOrDefault("pass", "");
        String db = p.flags.getOrDefault("db", "");
        boolean serviceMode = "true".equals(p.flags.get("oracle-service"));

        // 类型归一 + 缺省值（docs/3 §2.1）
        if ("sqlserver".equals(type)) {
            type = "mssql";
        } else if ("postgres".equals(type) || "pg".equals(type)) {
            type = "postgresql";
        } else if ("mongo".equals(type)) {
            type = "mongodb";
        }
        String defPort;
        String defUser;
        String defDb;
        switch (type) {
            case "mysql":
                defPort = "3306";
                defUser = "root";
                defDb = "";
                break;
            case "mssql":
                defPort = "1433";
                defUser = "sa";
                defDb = "";
                break;
            case "postgresql":
                defPort = "5432";
                defUser = "postgres";
                defDb = "postgres";
                break;
            case "oracle":
                defPort = "1521";
                defUser = "system";
                defDb = "orcl";
                break;
            case "redis":
                defPort = "6379";
                defUser = "";
                defDb = "";
                break;
            case "mongodb":
                // M3a◆ Keep 项：add + info（只读探测面）
                defPort = "27017";
                defUser = "";
                defDb = "admin";
                break;
            default:
                return emitFail(taskName, "add", ctx.textMode, ExitCode.USAGE,
                        "未知库类型: " + type, "支持: mysql|mssql|postgresql|oracle|redis|mongodb(M3a)");
        }
        if (port == null || port.isEmpty()) {
            port = defPort;
        }
        if (user.isEmpty()) {
            user = defUser;
        }
        if (db.isEmpty()) {
            db = defDb;
        }
        String proxy = System.getProperty("mdut.proxy", "");
        String timeout = String.valueOf(ctx.timeoutSec);

        // 连通测试（失败 → exit 3/4，不保留记录；TC4 负控制验收依赖此语义）
        Reporter reporter = new StderrReporter();
        boolean tested;
        String testErr = null;
        Throwable testEx = null;
        try {
            if ("redis".equals(type)) {
                Dao.RedisDao dao = new Dao.RedisDao(host, port, pass, timeout);
                dao.setReporter(reporter);
                dao.testConnection();
            } else if ("mysql".equals(type)) {
                Dao.MysqlDao dao = new Dao.MysqlDao(host, port, db, user, pass, timeout);
                dao.setReporter(reporter);
                dao.testConnection();
            } else if ("mssql".equals(type)) {
                Dao.MssqlDao dao = new Dao.MssqlDao(host, port, db, user, pass, timeout);
                dao.setReporter(reporter);
                dao.testConnection();
            } else if ("postgresql".equals(type)) {
                Dao.PostgreSqlDao dao = new Dao.PostgreSqlDao(host, port, db, user, pass, timeout);
                dao.setReporter(reporter);
                dao.testConnection();
            } else if ("oracle".equals(type)) {
                Dao.OracleDao dao = new Dao.OracleDao(host, port, db, user, pass, timeout, serviceMode);
                dao.setReporter(reporter);
                dao.testConnection();
            } else if ("mongodb".equals(type)) {
                Dao.MongoDbDao dao = new Dao.MongoDbDao(host, port, user, pass, db, timeout);
                dao.testConnection();
            } else {
                throw new IllegalStateException("unreachable type " + type);
            }
            tested = true;
        } catch (Throwable t) {
            tested = false;
            testEx = t;
            testErr = firstLine(t);
        }
        if (!tested) {
            int code = ExitCode.classifyTargetException(testEx);
            String label = code == ExitCode.TIMEOUT ? "连接超时: " : "连接测试失败: ";
            return emitFail(taskName, "add", ctx.textMode, code,
                    label + testErr,
                    code == ExitCode.TIMEOUT ? "放宽 --timeout 或检查路由；内网目标考虑 --proxy socks5://"
                            : "核对凭据/库名/SID；内网目标考虑 --proxy socks5://");
        }

        // 入库
        java.util.Map<String, String> rec = new java.util.LinkedHashMap<>();
        rec.put("databasetype", type);
        rec.put("ipaddress", host);
        rec.put("port", port);
        rec.put("username", user);
        rec.put("password", pass);
        rec.put("database", db);
        rec.put("timeout", timeout);
        rec.put("memo", p.flags.getOrDefault("memo", ""));
        rec.put("ishttp", "0");
        rec.put("url", "");
        rec.put("encryptionkey", "");
        rec.put("isproxy", proxy.isEmpty() ? "0" : "1");
        rec.put("proxytype", proxy.isEmpty() ? "" : "socks5");
        String[] pp = parseProxyParts(proxy.isEmpty() ? "socks5://127.0.0.1:1080" : proxy);
        rec.put("proxyaddress", proxy.isEmpty() ? "" : pp[0]);
        rec.put("proxyport", proxy.isEmpty() ? "" : pp[1]);
        rec.put("proxyusername", proxy.isEmpty() ? "" : pp[2]);
        rec.put("proxypassword", proxy.isEmpty() ? "" : pp[3]);
        rec.put("httpheaders", "");
        rec.put("connecttype", proxy.isEmpty() ? "direct" : "proxy");
        rec.put("addtime", Utils.getCurrentTimeToString());
        rec.put("groupname", p.flags.getOrDefault("group", ""));

        long id = ctx.store.addConnection(rec);
        JSONObject d = new JSONObject();
        d.put("tested", true);
        d.put("type", type);
        d.put("host", host);
        d.put("port", port);
        emit(taskName, "add", String.valueOf(id), ctx.textMode,
                Envelope.success(taskName, "add", String.valueOf(id),
                        "已登记连接 id=" + id + " (" + type + "://" + host + ":" + port + (db.isEmpty() ? "" : "/" + db) + ")，连通测试通过", d));
        return ExitCode.OK;
    }

    // ---- doctor ----

    private static int doDoctor(Ctx ctx, String taskName) throws Exception {
        JSONArray checks = new JSONArray();
        boolean allOk = true;

        JSONObject java = new JSONObject();
        java.put("name", "java");
        java.put("ok", true);
        java.put("detail", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        checks.put(java);

        String jarHome = ctx.jarHome;
        File home = new File(jarHome);
        JSONObject h = new JSONObject();
        h.put("name", "home");
        h.put("ok", home.isDirectory() && home.canWrite());
        h.put("detail", jarHome + (home.canWrite() ? "" : " （不可写）"));
        checks.put(h);
        allOk &= h.getBoolean("ok");

        JSONObject drv = new JSONObject();
        drv.put("name", "drivers");
        StringBuilder missing = new StringBuilder();
        for (String jar : new String[]{"mysql.jar", "mssql.jar", "oracle.jar", "postgresql.jar"}) {
            File f = new File(jarHome + File.separator + "Driver" + File.separator + jar);
            if (!f.isFile()) {
                if (missing.length() > 0) {
                    missing.append(", ");
                }
                missing.append(jar);
            }
        }
        drv.put("ok", missing.length() == 0);
        drv.put("detail", missing.length() == 0 ? "Driver/*.jar 齐全" : "缺失: " + missing);
        checks.put(drv);
        allOk &= drv.getBoolean("ok");

        JSONObject plg = new JSONObject();
        plg.put("name", "plugins");
        StringBuilder missingP = new StringBuilder();
        for (String dir : new String[]{"Mysql", "Mssql", "Oracle", "PostgreSql", "Redis"}) {
            File f = new File(jarHome + File.separator + "Plugins" + File.separator + dir);
            if (!f.isDirectory()) {
                if (missingP.length() > 0) {
                    missingP.append(", ");
                }
                missingP.append(dir);
            }
        }
        plg.put("ok", missingP.length() == 0);
        plg.put("detail", missingP.length() == 0 ? "Plugins/* 齐全" : "缺失: " + missingP);
        checks.put(plg);
        allOk &= plg.getBoolean("ok");

        JSONObject tk = new JSONObject();
        tk.put("name", "task");
        try {
            String quick = ctx.store.quickCheck();
            tk.put("ok", "ok".equalsIgnoreCase(quick));
            tk.put("detail", ctx.task + " → " + ctx.store.getTaskDir() + " (quick_check=" + quick + ")");
        } catch (Exception e) {
            tk.put("ok", false);
            tk.put("detail", firstLine(e));
        }
        checks.put(tk);
        allOk &= tk.getBoolean("ok");

        String proxy = System.getProperty("mdut.proxy", "");
        JSONObject px = new JSONObject();
        px.put("name", "proxy");
        if (proxy.isEmpty()) {
            px.put("ok", true);
            px.put("detail", "未启用");
        } else {
            String[] parts = parseProxyParts(proxy);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])),
                        Math.max(ctx.timeoutSec, 1) * 1000);
                px.put("ok", true);
                px.put("detail", "SOCKS5 " + parts[0] + ":" + parts[1] + " 可达");
            } catch (Exception e) {
                px.put("ok", false);
                px.put("detail", "SOCKS5 " + parts[0] + ":" + parts[1] + " 不可达: " + firstLine(e));
            }
        }
        checks.put(px);
        allOk &= px.getBoolean("ok");

        JSONObject d = new JSONObject();
        d.put("checks", checks);
        emit(taskName, "doctor", null, ctx.textMode,
                allOk ? Envelope.success(taskName, "doctor", null, "全部检查通过", d)
                        : Envelope.failureData(taskName, "doctor", null, "存在未通过检查项（见 data.checks）",
                        "按 checks 明细修复；驱动缺失参照发布包附带的 Driver/ 目录", d));
        return allOk ? ExitCode.OK : ExitCode.TARGET;
    }

    // ---- 输出与杂项 ----

    private static void emit(String task, String tool, String id, boolean textMode, String envelope) {
        emitRaw(textMode, envelope, ExitCode.OK);
    }

    private static void emitRaw(boolean textMode, String envelope, int exitCode) {
        envelopePrinted = true;
        lastExitCode = exitCode;
        if (textMode) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(envelope);
                if (o.optBoolean("ok")) {
                    String text = o.optString("text", "");
                    if (!text.isEmpty()) {
                        STDOUT.println(text);
                    }
                    STDOUT.println("[+] done");
                } else {
                    System.err.println("[-] " + o.optString("error", "")
                            + (o.has("hint") ? "\n    hint: " + o.optString("hint") : ""));
                }
            } catch (Exception e) {
                STDOUT.println(envelope);
            }
        } else {
            STDOUT.println(envelope);
        }
    }

    private static int emitFail(String task, String tool, boolean textMode, int exit, String error, String hint) {
        emitRaw(textMode, Envelope.failure(task == null ? "" : task,
                tool == null ? "" : tool, null, error, hint), exit);
        return exit;
    }

    private static String firstLine(Throwable t) {
        String m = t.getMessage();
        if (m == null && t.getCause() != null) {
            m = t.getCause().getMessage();
        }
        if (m == null) {
            m = t.getClass().getName();
        }
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }

    /**
     * SOCKS5 入站代理（docs/3 §7）：进程级 socksProxy* + RFC1929 认证。
     * 必须在任何 socket 建立前设置；Oracle thin/jTDS 的生效矩阵记入 docs/3 §10。
     */
    static void applyProxy(String proxy) throws Exception {
        if (!proxy.startsWith("socks5://")) {
            throw new Args.UsageException("不支持的代理协议: " + proxy,
                    "仅支持 socks5://[user:pass@]host:port（HTTP scheme 不采纳，见 docs/2 §6）");
        }
        String[] parts = parseProxyParts(proxy);
        System.setProperty("socksProxyHost", parts[0]);
        System.setProperty("socksProxyPort", parts[1]);
        System.setProperty("socksProxyVersion", "5");
        System.setProperty("mdut.proxy", proxy);
        if (!parts[2].isEmpty()) {
            final String u = parts[2];
            final String pw = parts[3];
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    String proto = getRequestingProtocol() == null ? "" : getRequestingProtocol().toUpperCase(java.util.Locale.ROOT);
                    // 只应答 SOCKS 认证——勿把凭据交给进程内其它协议的认证请求
                    if (proto.contains("SOCKS")) {
                        return new PasswordAuthentication(u, pw.toCharArray());
                    }
                    return null;
                }
            });
        }
        System.err.println(Utils.log("入站代理: socks5://" + parts[0] + ":" + parts[1]
                + (parts[2].isEmpty() ? "" : " (auth " + parts[2] + ")")));
    }

    /** socks5://[user:pass@]host:port → {host, port, user, pass}；非法形态抛用法错误 */
    static String[] parseProxyParts(String proxy) throws Args.UsageException {
        String rest = proxy.substring("socks5://".length());
        String user = "";
        String pass = "";
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            String cred = rest.substring(0, at);
            int colon = cred.indexOf(':');
            user = colon >= 0 ? cred.substring(0, colon) : cred;
            pass = colon >= 0 ? cred.substring(colon + 1) : "";
            rest = rest.substring(at + 1);
        }
        String host = rest;
        String port = "1080";
        int colon = rest.lastIndexOf(':');
        if (colon >= 0) {
            host = rest.substring(0, colon);
            port = rest.substring(colon + 1);
        }
        if (host.isEmpty() || !port.matches("\\d{1,5}") || Integer.parseInt(port) > 65535) {
            throw new Args.UsageException("非法代理地址: " + proxy,
                    "用法: socks5://[user:pass@]host:port（port 1–65535）");
        }
        return new String[]{host, port, user, pass};
    }

    /** Dao 层 Reporter：log/error 一律 stderr（信封纯净） */
    static class StderrReporter implements Reporter {
        @Override
        public void log(String msg) {
            System.err.print(msg == null ? "" : msg);
        }

        @Override
        public void error(String msg, Exception ex) {
            System.err.println(Utils.log("[!] " + (msg == null ? "未知错误" : msg)));
            if (ex != null && Boolean.getBoolean("mdut.debug")) {
                ex.printStackTrace(System.err);
            }
        }

        @Override
        public void result(String msg) {
            // Dao 的 result 行也走 stderr（stdout 只留给信封/text 内容）
            System.err.print(msg == null ? "" : msg);
        }
    }
}
