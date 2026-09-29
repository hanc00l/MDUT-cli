package cli;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 信封白盒：字段序固定、转义安全、失败必带 error。
 */
public class EnvelopeTest {

    @Test
    public void successFieldOrder() {
        String s = Envelope.success("eng1", "exec", "3", "uid=0(root)", new JSONObject().put("k", "v"));
        int ok = s.indexOf("\"ok\":true");
        int task = s.indexOf("\"task\"");
        int tool = s.indexOf("\"tool\"");
        int id = s.indexOf("\"id\"");
        int text = s.indexOf("\"text\"");
        int data = s.indexOf("\"data\"");
        assertTrue(ok >= 0 && ok < task && task < tool && tool < id && id < text && text < data);
    }

    @Test
    public void failureHasErrorAndHint() {
        String s = Envelope.failure("t", "add", null, "连接失败", "检查凭据");
        JSONObject o = new JSONObject(s);
        assertEquals(false, o.getBoolean("ok"));
        assertEquals("连接失败", o.getString("error"));
        assertEquals("检查凭据", o.getString("hint"));
    }

    @Test
    public void quotesEscaped() {
        String s = Envelope.success("t\"x", "tool", null, "he said \"hi\"\nline2", null);
        JSONObject o = new JSONObject(s);
        assertEquals("t\"x", o.getString("task"));
        assertEquals("he said \"hi\"\nline2", o.getString("text"));
    }

    @Test
    public void singleLine() {
        String s = Envelope.success("t", "sql", "1", "multi\nline", null);
        assertEquals(1, s.split("\n", -1).length);
    }
}
