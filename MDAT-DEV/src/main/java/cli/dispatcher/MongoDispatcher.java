package cli.dispatcher;

import cli.Args;
import cli.Ctx;
import cli.Result;
import Dao.MongoDbDao;
import org.json.JSONObject;

import java.util.Map;

/**
 * MongoDB 分发（M3a◆ Keep 项）：仅 add + info（版本/认证态/库名枚举，Extend 行为面）；
 * 深利用保持 Plugins 外部资产形态（docs/2 §0.2），clean 无痕可清。
 */
public class MongoDispatcher extends BaseDispatcher {

    @Override
    public String dbType() {
        return "mongodb";
    }

    @Override
    public Result handle(String tool, Args.Parsed p, Ctx ctx) throws Exception {
        String id = flag(p, "id", "");
        Map<String, String> rec = loadConn(ctx, id, dbType());
        MongoDbDao dao = new MongoDbDao(rec.get("ipaddress"), rec.get("port"), rec.get("username"),
                rec.get("password"), rec.get("database"), String.valueOf(ctx.timeoutSec));
        if (!"info".equals(tool)) {
            return Result.usage("mongodb 仅支持 info（Keep 项范围：info 探测）",
                    "深利用走 Plugins 外部资产；命令面见 mdut --help").withId(id);
        }
        try {
            Map<String, String> info = dao.getInfo();
            JSONObject d = new JSONObject();
            d.put("version", info.getOrDefault("version", ""));
            d.put("auth", info.getOrDefault("auth", ""));
            d.put("readOnly", info.getOrDefault("readOnly", "false"));
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String s : info.getOrDefault("databases", "").split(",")) {
                if (!s.isEmpty()) {
                    arr.put(s);
                }
            }
            d.put("databases", arr);
            return Result.ok("MongoDB " + d.get("version") + " / auth=" + d.get("auth")
                    + " / 库数=" + arr.length(), d).withId(id);
        } finally {
            dao.closeConnection();
        }
    }
}
