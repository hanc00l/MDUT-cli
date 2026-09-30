package cli;

import org.json.JSONObject;

/**
 * 单行 JSON 信封（docs/3 §1.3，与 GSL5 逐字同构）。
 *
 * 字段序固定：ok, task, tool, id, text, error, hint, data（存在才出）。
 * 手工序列化保证字段序稳定（org.json JSONObject 底层 HashMap 无序）；
 * 字符串统一走 JSONObject.quote 转义。stdout 恰好打印一行。
 */
public final class Envelope {

    private Envelope() {
    }

    public static String success(String task, String tool, String id, String text, JSONObject data) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"ok\":true");
        sb.append(",\"task\":").append(JSONObject.quote(nvl(task)));
        sb.append(",\"tool\":").append(JSONObject.quote(nvl(tool)));
        if (id != null && !id.isEmpty()) {
            sb.append(",\"id\":").append(JSONObject.quote(id));
        }
        if (text != null) {
            sb.append(",\"text\":").append(JSONObject.quote(text));
        }
        if (data != null && data.length() > 0) {
            sb.append(",\"data\":").append(data.toString());
        }
        sb.append("}");
        return sb.toString();
    }

    public static String failureData(String task, String tool, String id, String error, String hint, JSONObject data) {
        String base = failure(task, tool, id, error, hint);
        if (data == null || data.length() == 0) {
            return base;
        }
        // 在结尾 '}' 前插入 data 段（字段序固定，保持单行）
        return base.substring(0, base.length() - 1) + ",\"data\":" + data.toString() + "}";
    }

    public static String failure(String task, String tool, String id, String error, String hint) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"ok\":false");
        sb.append(",\"task\":").append(JSONObject.quote(nvl(task)));
        sb.append(",\"tool\":").append(JSONObject.quote(nvl(tool)));
        if (id != null && !id.isEmpty()) {
            sb.append(",\"id\":").append(JSONObject.quote(id));
        }
        sb.append(",\"error\":").append(JSONObject.quote(error == null ? "未知错误" : error));
        if (hint != null && !hint.isEmpty()) {
            sb.append(",\"hint\":").append(JSONObject.quote(hint));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
