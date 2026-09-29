# MDUT-CLI — Multiple Database Utilization Tools（CLI 化发行版）

> 上游 [SafeGroceryStore/MDUT](https://github.com/SafeGroceryStore/MDUT) v2.1.1（JavaFX GUI）的 **纯 CLI 二开**：零 JavaFX、JDK 8 基线、`--task` 隔离、单行 JSON 信封、零配置文件。
> 分支模型：`main` = 上游镜像；**本发行版全部工作在 `cli` 分支**。

## 功能面

| 库 | 命令执行 | 文件管理 | 反弹 Shell | 备注 |
|---|---|---|---|---|
| MySQL | `exec`（UDF sys_eval 自动部署链） | list-files/read/write/upload/download/rm/mkdir | `revshell`（uwx DLL，Win） | |
| MSSQL | `exec --method xpcmdshell\|oap\|agent\|clr\|badpotato\|godpotato\|efspotato\|efspotato_shellcode\|sweetpotato` | 五件套 + upload/download | — | `recovery` 一键恢复；potato 系需 Windows 目标 |
| PostgreSQL | `exec`（low/udf/cve 自动选路） | 五件套（pg_ls_dir/pg_read_binary_file/lo_export） | `revshell`（nohup 包装） | |
| Oracle | `exec --method java\|scheduler` | 五件套（FileUtil） | `revshell`（connectback） | `--oracle-service` 服务名模式 |
| Redis | `exec`（system.exec，模块经 `--vps-*`） | 五件套（system.exec） | `revshell`/crontab/sshkey/rdb | `info` 附带 CVE 扫描 |
| MongoDB | —（仅 `info` 探测） | — | — | Keep 项：版本/认证态/库名枚举 |

- **入站代理**：`--proxy socks5://[user:pass@]h:p`（仅入站；出站枢轴与 HTTP scheme 不做，见 docs/2 §6）。
- **明确不做**：MCP、deepstrike 插件适配（交接参考 docs/4）、HTTP 隧道 CLI 面（Dao 保留后置）。

## 构建

```bash
mvn -f MDAT-DEV/pom.xml clean package
# 产物：MDAT-DEV/target/mdut-jar-with-dependencies.jar（发布名 mdut.jar）
# 单测：surefire 自动跑（JUnit）；黑盒回归：./cli/tests/run.sh
```

本机中央仓库不稳时：`mvn -s .m2settings.xml -Dmaven.repo.local=.m2repo ...`（仓库已附Aliyun 镜像配置）。

## 布局与运行

```
mdut-cli-<ver>-dist.zip
├── mdut.jar          # uber-jar：全部运行依赖；⚠️ 不含 Driver/Plugins（运行期外置）
├── cli/mdut          # 真实入口 wrapper（task 解析/chdir/守卫）
├── cli/SKILL.md      # Agent 通用集成手册
├── Driver/           # JDBC 驱动（mysql/mssql/oracle/postgresql）
└── Plugins/          # 载荷资产（Mysql/Mssql(含 potato hex)/Oracle/PostgreSql/Redis）
```

```bash
./cli/mdut --task eng1 add mysql --host 10.0.0.1 --port 3306 --user root --pass '123456' --db mysql
./cli/mdut --task eng1 info --id 1
./cli/mdut --task eng1 exec --id 1 'id'
java -jar mdut.jar version      # 直跑 jar 亦可（task 缺省 default）
```

- task 数据：`<jar目录>/tasks/<task>/data.db`（WAL）+ `logs/audit.jsonl` 审计账。
- 退出码：`0` 成功 / `2` 用法 / `3` 目标失败 / `4` 超时 / `5` 写锁竞争。
- Agent 集成：把 `cli/SKILL.md` 交给 agent 即可（wrapper + JSON 信封 + 分层 help，零宿主改造）。

## 文档链

- [docs/1.现状与差异分析.md](docs/1.现状与差异分析.md) — 三方现状分析
- [docs/2.CLI二开实施方案.md](docs/2.CLI二开实施方案.md) — 实施规格 v3（里程碑/风险/决策链）
- [docs/3.CLI命令面规格.md](docs/3.CLI命令面规格.md) — 命令面契约 + 实测矩阵
- [docs/附录A...md](docs/附录A.MDUT原版功能分析报告-全量证据.md) — 原版全量证据
- [docs/4.deepstrike迁移指南.md](docs/4.deepstrike迁移指南.md) — deepstrike 交接参考
- [AGENTS.md](AGENTS.md) — 协作准则 + 实验室验收用例 TC1–TC4

## 合规

仅供授权安全测试与安全研究（沿用上游免责声明）。审计日志不得绕过；`data.db` 明文凭据为上游继承行为——任务目录不外发。
