package cli;

import org.json.JSONObject;
import Util.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * task 审计账（docs/3 §6）：每命令一条 JSONL 追加到 <task>/logs/audit.jsonl。
 * 多 agent 协作回溯依据；不得绕过（AGENTS.md §1.5/§6）。
 */
public class AuditLog {

    private final File file;

    public AuditLog(String taskDir) {
        this.file = new File(taskDir + File.separator + "logs" + File.separator + "audit.jsonl");
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            System.err.println(Utils.log("[audit] 审计目录创建失败: " + parent));
        }
    }

    /** 敏感 flag 值打码（凭据不原样落账；R9 已声明 data.db 明文，但审计账不再二次扩散） */
    static String[] maskArgv(String[] argv) {
        if (argv == null) {
            return new String[0];
        }
        java.util.List<String> out = new java.util.ArrayList<>(argv.length);
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (("--pass".equals(a) || "--proxypassword".equals(a)) && i + 1 < argv.length) {
                out.add(a);
                out.add("***");
                i++;
                continue;
            }
            if (a.startsWith("--pass=") || a.startsWith("--proxypassword=")) {
                int eq = a.indexOf('=');
                out.add(a.substring(0, eq + 1) + "***");
                continue;
            }
            if (a.startsWith("--proxy=") || "--proxy".equals(a)) {
                String v = "--proxy".equals(a) ? (i + 1 < argv.length ? argv[i + 1] : null) : a.substring(8);
                if (v != null && v.contains("@")) {
                    int at = v.indexOf("://") + 3;
                    int atSign = v.indexOf('@');
                    out.add("--proxy".equals(a) ? a : a.substring(0, 8) + v.substring(0, at) + "***:***" + v.substring(atSign));
                    if ("--proxy".equals(a)) {
                        out.add(v.substring(0, at) + "***:***" + v.substring(atSign));
                        i++;
                    }
                    continue;
                }
            }
            out.add(a);
        }
        return out.toArray(new String[0]);
    }

    public void append(String task, String tool, String[] argv, int exit, long ms) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", Utils.getCurrentTimeToString());
            o.put("task", task);
            o.put("tool", tool == null ? "" : tool);
            o.put("argv", maskArgv(argv));
            o.put("exit", exit);
            o.put("ms", ms);
            o.put("ok", exit == ExitCode.OK);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(o.toString());
                w.write("\n");
            }
        } catch (Exception e) {
            // 审计失败不吞命令结果，但必须留痕到 stderr
            System.err.println(Utils.log("[audit] 落账失败: " + e.getMessage()));
        }
    }
}
