package Util;

import java.io.File;

/**
 * 零配置连接档案（取代 config.yaml / YamlConfigs，docs/3 §7 冻结值）。
 *
 * 路径解析基准 = jar 自身目录（Utils.getSelfPath()）：
 *   <home>/Driver/*.jar      JDBC 驱动（发布 zip 分发，不进 uber-jar）
 *   <home>/Plugins/**        载荷资产（同上）
 * 常量只存「文件名与模板」，绝对路径由各 Dao 构造时拼接，保持与原版行为一致。
 */
public final class JdbcProfiles {

    private JdbcProfiles() {
    }

    // ---- 驱动 jar 文件名（相对 <home>/Driver/） ----
    public static final String MYSQL_JAR = "mysql.jar";
    public static final String MSSQL_JAR = "mssql.jar";
    public static final String ORACLE_JAR = "oracle.jar";
    public static final String POSTGRESQL_JAR = "postgresql.jar";

    // ---- 驱动类名 ----
    public static final String MYSQL_CLASS = "com.mysql.cj.jdbc.Driver";
    public static final String MSSQL_CLASS = "net.sourceforge.jtds.jdbc.Driver";
    public static final String ORACLE_CLASS = "oracle.jdbc.driver.OracleDriver";
    public static final String POSTGRESQL_CLASS = "org.postgresql.Driver";

    // ---- URL 模板（MessageFormat 槽位：{0}=host {1}=port {2}=db {3}=timeout） ----
    public static final String MYSQL_URL =
            "jdbc:mysql://{0}:{1}/{2}?connectTimeout={3}&socketTimeout={3}&characterEncoding=utf-8&useSSL=false&serverTimezone=UTC&rewriteBatchedStatements=true";
    public static final String MSSQL_URL =
            "jdbc:jtds:sqlserver://{0}:{1}/{2};loginTimeout={3};socketTimeout={3}";
    public static final String ORACLE_URL_SID = "jdbc:oracle:thin:@{0}:{1}:{2}";
    public static final String ORACLE_URL_SERVICE = "jdbc:oracle:thin:@{0}:{1}/{2}";
    public static final String POSTGRESQL_URL =
            "jdbc:postgresql://{0}:{1}/{2}?loginTimeout={3}&socketTimeout={3}";

    // ---- 缺省值（docs/3 §2.1） ----
    public static final String DEFAULT_TIMEOUT_SECONDS = "5";

    /**
     * 拼驱动 jar 绝对路径：<home>/Driver/<jarName>
     */
    public static String driverPath(String jarName) throws java.io.IOException {
        return Utils.getSelfPath() + File.separator + "Driver" + File.separator + jarName;
    }

    /**
     * 拼插件资产绝对路径：<home>/Plugins/<first>/<second...>
     */
    public static String pluginPath(String first, String... more) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(Utils.getSelfPath());
        sb.append(File.separator).append("Plugins").append(File.separator).append(first);
        for (String m : more) {
            sb.append(File.separator).append(m);
        }
        return sb.toString();
    }
}
