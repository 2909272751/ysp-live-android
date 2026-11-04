package com.gary.ysplive.desktop;

import javafx.application.Platform;
import javafx.stage.Stage;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 端到端验证「退出程序后进程真的结束」。
 *
 * <p>背景：用户反馈"怎么点都退不出后台"。根因是 shutdown() 只调了
 * Platform.exit()，而播放器是 Swing/AWT 窗口，AWT 线程不会因此结束，
 * 进程就一直挂着。
 *
 * <p>测试方式：真的启动 App，再通过反射调用私有的 shutdown()，
 * 然后等 10 秒。如果进程还在，说明修复无效 —— 打印 FAIL；
 * 如果 shutdown() 里的强制 halt 生效，这个 JVM 会被直接结束，
 * 外部（父进程）会观察到它在 10 秒内退出，即为 PASS。
 */
public final class ExitCheck {

    public static void main(String[] args) throws Exception {
        LogFile.start();
        CountDownLatch started = new CountDownLatch(1);
        final App[] appRef = new App[1];

        Platform.startup(() -> {
            try {
                App app = new App();
                appRef[0] = app;
                app.start(new Stage());
            } catch (Throwable t) {
                System.out.println("start failed: " + t);
            }
            started.countDown();
        });
        started.await(30, TimeUnit.SECONDS);

        // 等引擎与服务启动完
        Thread.sleep(9000);

        System.out.println("=== 调用 shutdown() ===");
        Method m = App.class.getDeclaredMethod("shutdown");
        m.setAccessible(true);
        Platform.runLater(() -> {
            try {
                m.invoke(appRef[0]);
            } catch (Throwable t) {
                System.out.println("shutdown invoke failed: " + t);
            }
        });

        // 如果 10 秒后还活着，说明退出失败了
        Thread.sleep(10000);
        System.out.println("RESULT: FAIL (进程仍在运行，退出无效)");
        System.exit(1);
    }
}
