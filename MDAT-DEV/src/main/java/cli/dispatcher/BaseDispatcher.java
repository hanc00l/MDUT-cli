package cli.dispatcher;

import cli.Ctx;
import cli.Result;
import cli.Args;

import java.util.Map;

/**
 * 分发器公共件：连接记录装载 / 类型守卫 / 不支持组合的统一语义（exit 2 + hint）。
 */
public abstract class BaseDispatcher implements Dispatcher {

    /** M3a 之前的未实现项统一提示 */
    protected static Result m3a(String tool, String dbType) {
        return Result.usage("命令 " + tool + " 暂不支持 " + dbType + "（M3a 里程碑提供）",
                "进度见仓库 docs/2.CLI二开实施方案.md §7.1；当前可用面见 mdut help " + tool);
    }

    protected Map<String, String> loadConn(Ctx ctx, String id, String expectType) throws Exception {
        Map<String, String> rec = ctx.store.findById(id);
        if (rec == null) {
            throw new UsageMsg("连接不存在: id=" + id + "（task=" + ctx.task + "）；mdut list 查看");
        }
        String t = rec.get("databasetype");
        if (!dbType().equals(t)) {
            throw new UsageMsg("连接 id=" + id + " 是 " + t + "，不能用 " + dbType() + " 命令操作");
        }
        return rec;
    }

    /** 供 loadConn 抛出（CliMain 转 exit 2 信封） */
    public static class UsageMsg extends Exception {
        public UsageMsg(String msg) {
            super(msg);
        }
    }

    protected String flag(Args.Parsed p, String name, String def) {
        String v = p.flags.get(name);
        return v == null ? def : v;
    }
}
