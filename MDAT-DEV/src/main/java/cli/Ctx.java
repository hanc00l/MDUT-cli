package cli;

import Util.Reporter;

/**
 * 一次调用的运行上下文（全局 flag 已解析；dispatcher 只读）。
 */
public class Ctx {
    public ConnectionStore store;
    public String task;
    public String jarHome;
    public int timeoutSec = 5;
    /** 回显编码；"" = 沿用 Dao 内建缺省（mssql GB2312 等） */
    public String enc = "";
    public boolean textMode;
    public Reporter reporter;
}
