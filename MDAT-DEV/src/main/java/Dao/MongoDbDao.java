package Dao;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB info 探测 Dao（M3a Keep 项，Extend MongoDbDao 路线；mongodb-driver-sync 4.x）。
 * 范围：连通测试 / hello(buildInfo) 探测 / 认证态判定 / 库名枚举 —— 只读，不做深利用。
 */
public class MongoDbDao {

    private final String ip;
    private final String port;
    private final String username;
    private final String password;
    private final String database;
    private final int timeoutMs;
    private MongoClient mongoClient;

    public MongoDbDao(String ip, String port, String username, String password, String database, String timeout) {
        this.ip = ip;
        this.port = port;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.database = (database == null || database.isEmpty()) ? "admin" : database;
        this.timeoutMs = Integer.parseInt(timeout) * 1000;
    }

    private MongoClient createClient() throws Exception {
        // 凭据百分号编码后入 URI（密码含 @:/%? 等特殊字符时防解析错位）
        String cred = username.isEmpty() ? "" :
                java.net.URLEncoder.encode(username, "UTF-8") + ":" + java.net.URLEncoder.encode(password, "UTF-8") + "@";
        ConnectionString cs = new ConnectionString("mongodb://" + cred + ip + ":" + port + "/"
                + this.database + "?serverSelectionTimeoutMS=" + timeoutMs + "&connectTimeoutMS=" + timeoutMs);
        return MongoClients.create(cs);
    }

    public void testConnection() throws Exception {
        MongoClient c = createClient();
        try {
            // serverSelection 在超时内不可达即抛
            c.getDatabase(this.database).runCommand(new Document("ping", 1));
            c.close();
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    public synchronized MongoClient getConnection() throws Exception {
        if (mongoClient == null) {
            mongoClient = createClient();
        }
        return mongoClient;
    }

    /**
     * info 结构（docs/3 §3.1 mongodb 行）：version / auth / databases[]
     */
    public Map<String, String> getInfo() throws Exception {
        java.util.Map<String, String> res = new java.util.LinkedHashMap<>();
        MongoDatabase db = getConnection().getDatabase(this.database);
        Document hello;
        try {
            hello = db.runCommand(new Document("hello", 1));
        } catch (Exception e) {
            hello = db.runCommand(new Document("isMaster", 1)); // 老版本兜底
        }
        Document buildInfo = db.runCommand(new Document("buildInfo", 1));
        res.put("version", String.valueOf(buildInfo.get("version")));
        res.put("auth", hello.getBoolean("isWritablePrimary", Boolean.FALSE)
                ? "ok" : String.valueOf(hello.get("msg")));
        java.util.List<String> dbs = new java.util.ArrayList<>();
        for (Document d : getConnection().listDatabases()) {
            Object name = d.get("name");
            if (name != null) {
                dbs.add(String.valueOf(name));
            }
        }
        res.put("databases", String.join(",", dbs));
        res.put("readOnly", String.valueOf(hello.getBoolean("readOnly", Boolean.FALSE)));
        return res;
    }

    public synchronized void closeConnection() {
        if (mongoClient != null) {
            mongoClient.close();
            mongoClient = null;
        }
    }
}
