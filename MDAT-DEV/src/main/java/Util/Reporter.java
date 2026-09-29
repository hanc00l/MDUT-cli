package Util;

/**
 * Dao 层统一输出口（CLI 化解耦注入点）。
 *
 * 背景：原版 Dao 直接持有 JavaFX Controller 引用（ControllersFactory + Platform.runLater +
 * TextArea.appendText）并以 MessageUtil 弹窗报错。本接口是唯一允许的替换口——
 * Dao 业务逻辑（SQL 模板、利用链、文件管道）零改动，只把「输出」改为回调。
 *
 * 实现方：
 *  - cli.output.CliReporter：JSON 信封 / text 模式渲染（CLI 用）
 *  - 测试桩：收集行做断言
 */
public interface Reporter {

    /** 进度/信息行（原 TextArea.appendText(Utils.log(msg)) 的落点；时间戳由实现方负责） */
    void log(String msg);

    /** 错误行（原 MessageUtil.showExceptionMessage / showErrorMessage 的落点） */
    void error(String msg, Exception ex);

    /** 结果行（最终回显内容，如命令输出；与 log 的区别：log 是过程，result 是结论） */
    void result(String msg);

    /** 空实现（收集到丢弃）：Dao 缺省构造时使用，保证无宿主也可运行 */
    Reporter NONE = new Reporter() {
        @Override
        public void log(String msg) {
        }

        @Override
        public void error(String msg, Exception ex) {
        }

        @Override
        public void result(String msg) {
        }
    };
}
