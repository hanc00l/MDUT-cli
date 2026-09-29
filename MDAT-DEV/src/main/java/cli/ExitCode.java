package cli;

/**
 * 退出码矩阵（docs/3 §1.2，GSL5 同构）：
 * 0 成功 / 2 用法错误 / 3 目标连接或执行失败 / 4 超时 / 5 task 写锁竞争。
 */
public final class ExitCode {

    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int TARGET = 3;
    public static final int TIMEOUT = 4;
    public static final int LOCKED = 5;

    private ExitCode() {
    }

    /**
     * 目标侧异常 → 退出码归类：超时类（连接/读超时）→ 4，其余 → 3。
     * 判定：整个 cause 链中任一 SocketTimeoutException 实例/超时类异常名，或消息含超时关键词
     * （JDBC 各驱动措辞不一——mysql "Communications link failure" 的真因在链尾 SocketTimeoutException）。
     */
    public static int classifyTargetException(Throwable t) {
        StringBuilder all = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 16) {
            if (cur instanceof java.net.SocketTimeoutException) {
                return TIMEOUT;
            }
            String name = cur.getClass().getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("timeout")) {
                return TIMEOUT;
            }
            if (cur.getMessage() != null) {
                all.append(cur.getMessage().toLowerCase(java.util.Locale.ROOT)).append(' ');
            }
            cur = cur.getCause();
            depth++;
        }
        String msg = all.toString();
        if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("time out")) {
            return TIMEOUT;
        }
        return TARGET;
    }
}
