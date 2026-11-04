package com.gary.ysplive.desktop;

import javafx.application.Platform;
import javafx.stage.Stage;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 回归测试：主窗口尺寸不能因为「最大化」而被污染。
 *
 * <p>曾经的 bug：width/height 的监听器没有排除最大化状态，
 * 最大化一次就把屏幕尺寸写进了「普通尺寸」配置；下次启动用超大尺寸开窗，
 * 被系统挤到屏幕外；用户再最大化又存更大的值 —— 窗口一次比一次大、一路往左上飘。
 *
 * <p>测试方式：启动 App（它会装上监听器）→ 最大化 → 直接读 App 内存里的
 * config，断言 windowWidth/Height 仍是启动时的普通尺寸。
 * 不走 shutdown()，因为那里会强制 halt 结束进程、写不了报告。
 */
public final class WindowGeometryCheck {

    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        LogFile.start();

        DesktopConfig boot = DesktopConfig.load();
        boot.windowMaximized = false;
        boot.windowWidth = 1100;
        boot.windowHeight = 700;
        boot.windowX = 120;
        boot.windowY = 90;
        boot.save();
        sb.append("=== start point ===\n");
        sb.append("  normal size = 1100x700\n");

        final Stage[] holder = new Stage[1];
        final CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                App app = new App();
                Stage st = new Stage();
                holder[0] = st;
                app.start(st);
            } catch (Throwable t) {
                sb.append("  start error: ").append(t).append('\n');
            }
            ready.countDown();
        });
        ready.await(30, TimeUnit.SECONDS);
        Thread.sleep(4000);

        Stage stage = holder[0];
        sb.append("\n=== after launch ===\n");
        sb.append("  actual size = ").append((int) stage.getWidth())
                .append("x").append((int) stage.getHeight()).append('\n');

        Field cf = App.class.getDeclaredField("config");
        cf.setAccessible(true);
        DesktopConfig live = (DesktopConfig) cf.get(App.INSTANCE);

        int beforeW = live.windowWidth;
        int beforeH = live.windowHeight;
        sb.append("  config before maximize = ").append(beforeW).append("x").append(beforeH).append('\n');

        Platform.runLater(() -> stage.setMaximized(true));
        Thread.sleep(2500);

        int afterW = live.windowWidth;
        int afterH = live.windowHeight;
        sb.append("\n=== after maximize ===\n");
        sb.append("  window actual = ").append((int) stage.getWidth())
                .append("x").append((int) stage.getHeight()).append('\n');
        sb.append("  config        = ").append(afterW).append("x").append(afterH).append('\n');

        boolean notPolluted = afterW <= beforeW + 40 && afterH <= beforeH + 40;
        sb.append("  size not polluted by maximize = ").append(notPolluted).append('\n');

        boolean pass = notPolluted;
        sb.append("\nRESULT: ").append(pass ? "PASS" : "FAIL").append('\n');
        System.out.println(sb);
        if (args.length > 0) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        LogFile.flush();
        System.exit(pass ? 0 : 1);
    }
}
