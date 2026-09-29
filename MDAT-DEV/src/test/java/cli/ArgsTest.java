package cli;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * 参数解析白盒（表驱动/别名/兼容形式/必填校验）。
 */
public class ArgsTest {

    @Test
    public void parseBasicCommand() throws Exception {
        Args.Parsed p = Args.parse(new String[]{"--task", "eng1", "add", "mysql", "--host", "10.0.0.1", "--port", "3306"});
        assertEquals("add", p.command);
        assertEquals("eng1", p.flags.get("task"));
        assertEquals("10.0.0.1", p.flags.get("host"));
        assertEquals("mysql", p.positionals.get(0));
    }

    @Test
    public void booleanFlagsAndInlineValue() throws Exception {
        Args.Parsed p = Args.parse(new String[]{"add", "oracle", "--oracle-service", "--host=1.2.3.4"});
        assertEquals("true", p.flags.get("oracle-service"));
        assertEquals("1.2.3.4", p.flags.get("host"));
    }

    @Test
    public void sqlCompatForm() throws Exception {
        Args.Parsed p = Args.parse(new String[]{"--sql", "--id", "3", "select version()"});
        assertEquals("sql", p.command);
        assertEquals("select version()", p.positionals.get(0));
        assertEquals("3", p.flags.get("id"));
    }

    @Test
    public void unknownCommandFails() {
        try {
            Args.parse(new String[]{"frobnicate"});
            fail("应抛 UsageException");
        } catch (Args.UsageException ue) {
            assertEquals(ExitCode.USAGE, ExitCode.USAGE);
        }
    }

    @Test
    public void missingFlagValueFails() {
        try {
            Args.parse(new String[]{"add", "mysql", "--host"});
            fail("应抛 UsageException");
        } catch (Args.UsageException ue) {
            // expected
        }
    }

    @Test
    public void validateRequires() throws Exception {
        Args.Parsed p = Args.parse(new String[]{"info"});
        Args.Spec s = Args.findSpec("info");
        try {
            Args.validate(s, p);
            fail("缺少 --id 应失败");
        } catch (Args.UsageException ue) {
            // expected
        }
        Args.Parsed p2 = Args.parse(new String[]{"info", "--id", "1"});
        Args.validate(s, p2); // 不抛
    }

    @Test
    public void mutatingCommandsTakeLock() {
        assertEquals(true, Args.findSpec("exec").mutating);
        assertEquals(true, Args.findSpec("clean").mutating);
        assertEquals(false, Args.findSpec("list").mutating);
        assertEquals(false, Args.findSpec("info").mutating);
    }

    @Test
    public void helpGeneratedFromTable() {
        String all = Args.helpAll();
        for (Args.Spec s : Args.SPECS) {
            if (!s.name.equals("help") && !s.name.equals("version")) {
                org.junit.Assert.assertTrue("help 缺命令 " + s.name, all.contains(s.name));
            }
        }
        org.junit.Assert.assertTrue(Args.helpCommand("exec").contains("--method"));
        assertNull(Args.helpCommand("no-such"));
    }
}
