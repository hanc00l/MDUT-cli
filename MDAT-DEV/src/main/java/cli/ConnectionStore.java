package cli;

import org.json.JSONArray;
import org.json.JSONObject;
import Util.Utils;

import java.io.File;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * task 级连接库（docs/3 §6）：tasks-root/<task>/data.db。
 *
 * - tasks-root 冻结为 <jar 目录>/tasks/（无论 cwd 在哪，落点确定可测；wrapper chdir 只影响相对路径输出）
 * - 首用即建库：schema 与原版 data 表一致（22 列）+ 追加 groupname 列 + groups 表
 * - WAL + busy_timeout=5000（并发读写安全）；符号链接拒判（隔离破坏红线，AGENTS.md §1.5）
 */
public class ConnectionStore {

    public static final String[] COLUMNS = {
            "id", "databasetype", "ipaddress", "port", "username", "password", "database", "timeout",
            "memo", "ishttp", "url", "encryptionkey", "isproxy", "proxytype", "proxyaddress",
            "proxyport", "proxyusername", "proxypassword", "httpheaders", "connecttype", "addtime", "groupname"
    };

    private static final String SCHEMA_DATA =
            "CREATE TABLE IF NOT EXISTS data (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "databasetype TEXT NOT NULL DEFAULT '', ipaddress TEXT NOT NULL DEFAULT '', " +
                    "port TEXT NOT NULL DEFAULT '', username TEXT NOT NULL DEFAULT '', " +
                    "password TEXT NOT NULL DEFAULT '', database TEXT NOT NULL DEFAULT '', " +
                    "timeout TEXT NOT NULL DEFAULT '', memo TEXT NOT NULL DEFAULT '', " +
                    "ishttp TEXT NOT NULL DEFAULT '', url TEXT NOT NULL DEFAULT '', " +
                    "encryptionkey TEXT NOT NULL DEFAULT '', isproxy TEXT NOT NULL DEFAULT '', " +
                    "proxytype TEXT NOT NULL DEFAULT '', proxyaddress TEXT NOT NULL DEFAULT '', " +
                    "proxyport TEXT NOT NULL DEFAULT '', proxyusername TEXT NOT NULL DEFAULT '', " +
                    "proxypassword TEXT NOT NULL DEFAULT '', httpheaders TEXT NOT NULL DEFAULT '', " +
                    "connecttype TEXT NOT NULL DEFAULT '', addtime TEXT NOT NULL DEFAULT '', " +
                    "groupname TEXT NOT NULL DEFAULT '')";
    private static final String SCHEMA_GROUPS = "CREATE TABLE IF NOT EXISTS groups (name TEXT PRIMARY KEY)";

    private final String taskName;
    private final File taskDir;
    private final File dbFile;
    private Connection conn;

    public ConnectionStore(String tasksRoot, String taskName) throws Exception {
        this.taskName = taskName;
        this.taskDir = new File(tasksRoot + File.separator + taskName);
        this.dbFile = new File(taskDir, "data.db");
        init();
    }

    /**
     * tasks-root 解析：-Dmdut.tasks-root > $MDUT_TASKS_ROOT > <jar目录>/tasks（docs/3 §6）。
     * wrapper 已 chdir + 传同名系统属性，两侧落点一致。
     */
    public static String tasksRoot(String jarHome) {
        String r = System.getProperty("mdut.tasks-root");
        if (r == null || r.isEmpty()) {
            r = System.getenv("MDUT_TASKS_ROOT");
        }
        if (r == null || r.isEmpty()) {
            r = jarHome + File.separator + "tasks";
        }
        return r;
    }

    // ---------------- task 解析与守卫 ----------------

    /** task 名守卫（wrapper 同款正则；Java 侧兜底） */
    public static boolean validTaskName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,64}");
    }

    /** 解析优先级：系统属性(-Dmdut.task，wrapper 注入) > --task flag > $MDUT_TASK > default */
    public static String resolveTaskName(String taskFlag) {
        String t = System.getProperty("mdut.task");
        if (t == null || t.isEmpty()) {
            t = taskFlag;
        }
        if (t == null || t.isEmpty()) {
            t = System.getenv("MDUT_TASK");
        }
        if (t == null || t.isEmpty()) {
            t = "default";
        }
        return t;
    }

    // ---------------- 初始化 ----------------

    private void init() throws Exception {
        if (!validTaskName(taskName)) {
            throw new IllegalArgumentException("非法 task 名: " + taskName + "（允许 [A-Za-z0-9_-]{1,64}）");
        }
        if (!taskDir.isDirectory() && !taskDir.mkdirs()) {
            throw new Exception("task 目录创建失败: " + taskDir.getAbsolutePath());
        }
        if (dbFile.exists() && isSymlink(dbFile)) {
            throw new IllegalArgumentException("data.db 是符号链接，拒绝打开（task 隔离红线）: " + dbFile.getPath());
        }
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute(SCHEMA_DATA);
            st.execute(SCHEMA_GROUPS);
        }
    }

    private static boolean isSymlink(File f) throws Exception {
        File canon = f.getParentFile() == null ? f : new File(f.getParentFile().getCanonicalFile(), f.getName());
        return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
    }

    public String getTaskName() {
        return taskName;
    }

    public String getTaskDir() {
        return taskDir.getAbsolutePath();
    }

    public void close() {
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (Exception ignore) {
        }
    }

    // ---------------- CRUD ----------------

    /** 库健康检查（doctor）：PRAGMA quick_check 返回串 */
    public String quickCheck() throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** 插入连接记录，返回自增 id */
    public long addConnection(Map<String, String> rec) throws Exception {
        StringBuilder cols = new StringBuilder();
        StringBuilder qs = new StringBuilder();
        for (String c : COLUMNS) {
            if (c.equals("id")) {
                continue;
            }
            if (cols.length() > 0) {
                cols.append(',');
                qs.append(',');
            }
            cols.append(c).append(c.equals("database") ? "" : "");
            qs.append('?');
        }
        // 注意: database 非保留字，SQLite 可裸用
        String sql = "INSERT INTO data(" + cols + ") VALUES(" + qs + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            for (String c : COLUMNS) {
                if (c.equals("id")) {
                    continue;
                }
                String v = rec.get(c);
                ps.setString(i++, v == null ? "" : v);
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new Exception("插入连接记录失败（未取到自增 id）");
    }

    /** 连接列表（不含密码）；group 非空时按 groupname 过滤 */
    public JSONArray listConnections(String group) throws Exception {
        JSONArray arr = new JSONArray();
        String sql = "SELECT id,databasetype,ipaddress,port,username,database,memo,groupname,addtime FROM data";
        if (group != null && !group.isEmpty()) {
            sql += " WHERE groupname=?";
        }
        sql += " ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (group != null && !group.isEmpty()) {
                ps.setString(1, group);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject o = new JSONObject();
                    o.put("id", rs.getString("id"));
                    o.put("type", rs.getString("databasetype"));
                    o.put("host", rs.getString("ipaddress"));
                    o.put("port", rs.getString("port"));
                    o.put("user", rs.getString("username"));
                    o.put("db", rs.getString("database"));
                    o.put("memo", rs.getString("memo"));
                    o.put("group", rs.getString("groupname"));
                    o.put("addtime", rs.getString("addtime"));
                    arr.put(o);
                }
            }
        }
        return arr;
    }

    /** 按 id 取整行（含凭据，仅供 Dao 构造） */
    public Map<String, String> findById(String id) throws Exception {
        Map<String, String> rec = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM data WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    rec.put(md.getColumnName(i), rs.getString(i));
                }
                return rec;
            }
        }
    }

    public int deleteById(String id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM data WHERE id=?")) {
            ps.setString(1, id);
            return ps.executeUpdate();
        }
    }

    public void addGroup(String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO groups(name) VALUES(?)")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
    }

    public int deleteGroup(String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM groups WHERE name=?")) {
            ps.setString(1, name);
            return ps.executeUpdate();
        }
    }

    // ---------------- task 枚举 ----------------

    /** 枚举 tasks-root 下所有任务（doctor/task list 用） */
    public static JSONArray listTasks(String tasksRoot) {
        JSONArray arr = new JSONArray();
        File root = new File(tasksRoot);
        File[] dirs = root.listFiles();
        if (dirs == null) {
            return arr;
        }
        java.util.Arrays.sort(dirs);
        for (File d : dirs) {
            if (!d.isDirectory()) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("task", d.getName());
            File db = new File(d, "data.db");
            o.put("db_size", db.isFile() ? db.length() : 0);
            o.put("locked", isLocked(d));
            arr.put(o);
        }
        return arr;
    }

    /** 探测某 task 的写锁是否被进程持有（tryLock 试取；绝不删除他人锁文件） */
    private static boolean isLocked(File taskDir) {
        File lock = new File(taskDir, ".lock");
        if (!lock.isFile()) {
            return false;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lock, "rw");
             java.nio.channels.FileLock fl = raf.getChannel().tryLock()) {
            return fl == null;
        } catch (Exception e) {
            // 取不到锁（含重叠锁冲突）按「被持有」报告
            return true;
        }
    }

    /** jar 自身所在目录（发布布局基准：<home>/mdut.jar、<home>/Driver、<home>/Plugins、<home>/tasks） */
    public static String jarHome() throws Exception {
        String p = Utils.getSelfPath();
        if (p == null) {
            throw new Exception("无法解析 jar 自身目录（getSelfPath=null）");
        }
        return p;
    }
}
