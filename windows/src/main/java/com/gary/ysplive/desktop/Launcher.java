package com.gary.ysplive.desktop;

/**
 * 单文件 jar 的入口。
 *
 * <p><b>为什么需要这个类：</b>JavaFX 的 {@code Application} 子类不能直接作为
 * {@code Main-Class}——当 JavaFX 不在 module-path 上时，JVM 启动器会检测到
 * 主类是 {@code javafx.application.Application} 的子类而报
 * 「JavaFX runtime components are missing」。
 * 用一个普通类做跳板调用 {@code Application.launch()} 就能绕开这个检测。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
