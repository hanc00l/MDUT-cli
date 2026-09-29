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

    public void append(String task, String tool, String[] argv, int exit, long ms) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", Utils.getCurrentTimeToString());
            o.put("task", task);
            o.put("tool", tool == null ? "" : tool);
            o.put("argv", argv);
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
