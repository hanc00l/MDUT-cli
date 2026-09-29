package cli;

import org.json.JSONObject;

/**
 * 命令执行结果（dispatcher → CliMain）：由 CliMain 统一渲染信封/退出码/审计。
 */
public class Result {

    public boolean ok;
    public int exit;
    public String id;
    public String text;
    public JSONObject data;
    public String error;
    public String hint;

    public static Result ok(String text) {
        Result r = new Result();
        r.ok = true;
        r.exit = ExitCode.OK;
        r.text = text;
        return r;
    }

    public static Result ok(String text, JSONObject data) {
        return okData(text, data);
    }

    public static Result okData(String text, JSONObject data) {
        Result r = ok(text);
        r.data = data;
        return r;
    }

    public static Result fail(int exit, String error, String hint) {
        Result r = new Result();
        r.ok = false;
        r.exit = exit;
        r.error = error;
        r.hint = hint;
        return r;
    }

    public static Result usage(String error, String hint) {
        return fail(ExitCode.USAGE, error, hint);
    }

    public static Result target(String error, String hint) {
        return fail(ExitCode.TARGET, error, hint);
    }

    public Result withId(String connId) {
        this.id = connId;
        return this;
    }
}
