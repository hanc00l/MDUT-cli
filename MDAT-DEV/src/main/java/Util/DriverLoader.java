package Util;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.util.Properties;

/**
 * JDBC 驱动加载器（JDK 8+ 唯一合法路线）。
 *
 * 为什么不用 DriverManager：原版用 ((URLClassLoader) getSystemClassLoader()).addURL + Class.forName，
 * 在 JDK 9+ 上强转必崩；且 DriverManager#connect 会做「调用方类加载器」信任检查（CallerSensitiveAPI），
 * 本类（app classloader）不被驱动 jar 认可时 drivers 会拒绝服务。故：
 *   1. 每次连接用「子 URLClassLoader」装驱动 jar；
 *   2. Class.forName(name, true, loader) 实例化驱动；
 *   3. 直接 driver.connect(url, props)——绕开 DriverManager 注册与信任检查。
 *
 * 附带消灭：Utils.regroupDrivers 排序补丁（及其 "ostgresql" 拼写 bug）随 DriverManager 路线一并退役。
 *
 * 线程与复用：一次 connect 一个 loader，连接关闭后 loader 可被 GC；不缓存（驱动 jar 允许运行期热替换）。
 */
public final class DriverLoader {

    private DriverLoader() {
    }

    /**
     * 加载驱动并建立连接。
     *
     * @param driverJar   驱动 jar 绝对/相对路径（如 <home>/Driver/mysql.jar）
     * @param driverClass 驱动类名（如 com.mysql.cj.jdbc.Driver）
     * @param url         JDBC URL
     * @param props       连接属性（user/password 等；可为 null）
     * @return 已建立的 Connection（失败抛异常，错误信息含驱动文件与类名便于 doctor 排障）
     */
    public static Connection connect(String driverJar, String driverClass, String url, Properties props) throws Exception {
        File jar = new File(driverJar);
        if (!jar.isFile()) {
            throw new Exception("驱动文件不存在: " + jar.getAbsolutePath()
                    + "（发布包须附带 Driver/ 目录；可执行 mdut doctor 自检）");
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, DriverLoader.class.getClassLoader());
        try {
            Class<?> cls = Class.forName(driverClass, true, loader);
            Driver driver = (Driver) cls.getDeclaredConstructor().newInstance();
            Connection conn = driver.connect(url, props);
            if (conn == null) {
                // 驱动不认识该 URL（driver.connect 约定返回 null 表示 URL 不归它管）
                throw new Exception("驱动 " + driverClass + " 拒绝 URL（返回 null）: " + url);
            }
            return conn;
        } catch (Exception e) {
            try {
                loader.close();
            } catch (Exception ignore) {
            }
            throw e;
        }
    }
}
