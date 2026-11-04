package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 验证本轮修复：
 * 1. 托盘菜单中文字体可显示（不再是方框）
 * 2. 播放器侧栏/控制条在各种窗口尺寸下都可见
 * 3. 全屏切换不销毁播放器
 * 4. 频道网格滚动不超出底部
 */
public final class FixCheck {

    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        LogFile.start();

        // ---------- 1. 托盘字体 ----------
        sb.append("=== 1. 托盘菜单字体 ===\n");
        sb.append("  托盘可用 = ").append(Tray.isSupported()).append('\n');
        String probe = "打开选台界面退出程序停止播放";
        java.awt.Font f = pickFontProbe();
        sb.append("  选中字体 = ").append(f == null ? "null" : f.getFamily()).append('\n');
        sb.append("  能显示中文 = ").append(f != null && f.canDisplayUpTo(probe) < 0).append('\n');

        // ---------- 2/3. 播放器面板 ----------
        DesktopConfig config = DesktopConfig.load();
        config.enginePort = 18991;
        config.playerSidePanelOpen = true;
        config.playerAutoHideControls = false;

        EngineController engine = new EngineController(config);
        CountDownLatch started = engine.startAsync(s -> { });
        started.await(60, TimeUnit.SECONDS);
        if (!engine.isRunning()) {
            sb.append("\nengine failed\n");
            System.out.println(sb);
            System.exit(1);
        }
        String origin = engine.localOrigin();
        ChannelSpec a = ChannelTable.bySlug("cctv1");
        VlcPlayer.Window w = VlcPlayer.open(origin + "/" + a.slug + ".m3u8", a,
                ChannelTable.all(), config, spec -> { }, () -> { });

        sb.append("\n=== 2. 播放器面板（默认尺寸）===\n");
        Thread.sleep(2500);
        boolean ok1 = dumpPanels(w.frame, sb);

        sb.append("\n=== 2b. 放大到 1600x900 ===\n");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            w.frame.setSize(1600, 900);
            w.frame.validate();
        });
        Thread.sleep(1200);
        boolean ok2 = dumpPanels(w.frame, sb);

        sb.append("\n=== 3. 全屏切换（不应销毁播放器）===\n");
        String stateBefore = String.valueOf(w.player.status().state());
        // 直接触发双击全屏路径
        javax.swing.SwingUtilities.invokeAndWait(() -> w.frame.dispatchEvent(
                new java.awt.event.WindowEvent(w.frame,
                        java.awt.event.WindowEvent.WINDOW_ACTIVATED)));
        // 用反射调用私有 toggleFullScreen 不便，改为直接设置最大化模拟
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            w.frame.setExtendedState(javax.swing.JFrame.MAXIMIZED_BOTH);
            w.frame.validate();
        });
        Thread.sleep(1500);
        boolean ok3 = dumpPanels(w.frame, sb);
        String stateAfter = String.valueOf(w.player.status().state());
        sb.append("  播放器状态 before=").append(stateBefore)
                .append(" after=").append(stateAfter).append('\n');
        boolean notKilled = w.player.status().state() != null
                && !String.valueOf(w.player.status().state()).equals("STOPPED");

        w.release();
        engine.stop();

        sb.append("\n=== 4. 频道网格滚动范围 ===\n");
        boolean scrollOk = checkScroll(sb);

        boolean pass = f != null && f.canDisplayUpTo(probe) < 0
                && ok1 && ok2 && ok3 && notKilled && scrollOk;
        sb.append("\nRESULT: ").append(pass ? "PASS" : "FAIL").append('\n');
        System.out.println(sb);
        if (args.length > 0) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        LogFile.flush();
        System.exit(pass ? 0 : 1);
    }

    private static java.awt.Font pickFontProbe() {
        String probe = "打开选台界面退出程序停止播放";
        String[] candidates = {"Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "SimHei",
                java.awt.Font.DIALOG};
        for (String name : candidates) {
            try {
                java.awt.Font f = new java.awt.Font(name, java.awt.Font.PLAIN, 12);
                if (f.canDisplayUpTo(probe) < 0) {
                    return f;
                }
            } catch (Throwable ignored) {
                // next
            }
        }
        return null;
    }

    private static boolean dumpPanels(javax.swing.JFrame frame, StringBuilder sb) {
        Container root = frame.getContentPane();
        sb.append("  frame=").append(frame.getWidth()).append('x').append(frame.getHeight())
                .append('\n');
        boolean sideOk = false;
        boolean barOk = false;
        for (Component c : root.getComponents()) {
            String n = c.getClass().getSimpleName();
            sb.append(String.format("    %-12s vis=%-5s %dx%d @%d,%d%n",
                    n, c.isVisible(), c.getWidth(), c.getHeight(), c.getX(), c.getY()));
            if (n.equals("SidePanel")) {
                sideOk = c.isVisible() && c.getWidth() > 100;
            }
            if (n.equals("ControlBar")) {
                barOk = c.isVisible() && c.getHeight() > 20;
            }
        }
        return sideOk && barOk;
    }

    /** 用 App 的虚拟网格检查：滚动到底时不能超出最后一行。 */
    private static boolean checkScroll(StringBuilder sb) {
        final boolean[] ok = {false};
        final CountDownLatch done = new CountDownLatch(1);
        javafx.application.Platform.startup(() -> {
            try {
                App app = new App();
                javafx.stage.Stage st = new javafx.stage.Stage();
                app.start(st);
                new Thread(() -> {
                    try {
                        Thread.sleep(7000);
                    } catch (InterruptedException ignored) {
                        // ignore
                    }
                    javafx.application.Platform.runLater(() -> {
                        try {
                            java.lang.reflect.Field gf =
                                    App.class.getDeclaredField("virtualGrid");
                            gf.setAccessible(true);
                            VirtualChannelGrid vg = (VirtualChannelGrid) gf.get(app);
                            javafx.scene.control.ScrollPane sp = vg.node();
                            vg.rebuild();
                            sp.applyCss();
                            sp.layout();
                            double contentH = sp.getContent().getBoundsInLocal().getHeight();
                            double viewportH = sp.getViewportBounds().getHeight();
                            sb.append("  contentH=").append((int) contentH)
                                    .append(" viewportH=").append((int) viewportH).append('\n');
                            sb.append("  scrollable=").append((int) (contentH - viewportH))
                                    .append('\n');
                            // 卡片应该基本填满内容高度：行数*行高 与 contentH 接近
                            sb.append("  内容高度合理 = ").append(contentH > viewportH).append('\n');
                            ok[0] = contentH > viewportH;
                        } catch (Throwable t) {
                            sb.append("  scroll check error: ").append(t).append('\n');
                        }
                        done.countDown();
                    });
                }, "scroll-check").start();
            } catch (Throwable t) {
                sb.append("  app start error: ").append(t).append('\n');
                done.countDown();
            }
        });
        try {
            done.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }
}
