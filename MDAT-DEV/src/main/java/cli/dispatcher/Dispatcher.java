package cli.dispatcher;

import cli.Args;
import cli.Ctx;
import cli.Result;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每库命令分发器（docs/3 §3 行为细则的落地口）。
 * 实现类：Mysql/Mssql/PostgreSql/Oracle/Redis；MongoDB 于 M3a 接入。
 */
public interface Dispatcher {

    String dbType();

    /**
     * 处理一条 --id 类命令（tool ∈ info/exec/sql/clean/revshell/list-files/read/write/upload/download/rm/mkdir/recovery/deploy/crontab/sshkey/rdb）。
     * 参数级校验已在 CliMain 完成；此处只做「库 × 命令」支持性判定与业务调度。
     */
    Result handle(String tool, Args.Parsed p, Ctx ctx) throws Exception;

    /** 注册表（dbType 归一名 → dispatcher）；holder 惯用法初始化，无懒加载竞态 */
    Map<String, Dispatcher> REGISTRY = Holder.REGISTRY;

    static Dispatcher forType(String dbType) {
        return REGISTRY.get(dbType);
    }

    /** 静态持有者：类加载即注册，线程安全（JLS 初始化语义） */
    final class Holder {
        static final Map<String, Dispatcher> REGISTRY = new LinkedHashMap<String, Dispatcher>();

        static {
            register(new MysqlDispatcher());
            register(new MssqlDispatcher());
            register(new PostgreSqlDispatcher());
            register(new OracleDispatcher());
            register(new RedisDispatcher());
            register(new MongoDispatcher());
        }

        private Holder() {
        }

        private static void register(Dispatcher d) {
            REGISTRY.put(d.dbType(), d);
        }
    }
}
