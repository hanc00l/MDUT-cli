package cli;

import org.junit.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

import static org.junit.Assert.assertEquals;

/**
 * 退出码归类白盒（超时链穿透判定）。
 */
public class ExitCodeTest {

    @Test
    public void directTimeout() {
        assertEquals(ExitCode.TIMEOUT, ExitCode.classifyTargetException(
                new SQLTimeoutException("Read timed out")));
    }

    @Test
    public void wrappedMysqlStyleTimeout() {
        // mysql 驱动风格：外层 Communications link failure，链尾 SocketTimeoutException
        RuntimeException inner = new RuntimeException(new java.net.SocketTimeoutException("Read timed out"));
        RuntimeException outer = new RuntimeException("Communications link failure", inner);
        assertEquals(ExitCode.TIMEOUT, ExitCode.classifyTargetException(outer));
    }

    @Test
    public void authFailureIsTarget() {
        SQLException auth = new SQLException("Access denied for user 'root'@'localhost' (using password: YES)");
        assertEquals(ExitCode.TARGET, ExitCode.classifyTargetException(auth));
    }

    @Test
    public void refusedIsTarget() {
        assertEquals(ExitCode.TARGET, ExitCode.classifyTargetException(
                new RuntimeException("Connection refused")));
    }

    @Test
    public void messageKeywordDeepInChain() {
        Exception e = new Exception("outer",
                new Exception("middle", new Exception("connection timed out")));
        assertEquals(ExitCode.TIMEOUT, ExitCode.classifyTargetException(e));
    }
}
