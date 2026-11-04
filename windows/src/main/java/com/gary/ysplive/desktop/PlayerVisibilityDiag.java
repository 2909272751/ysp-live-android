package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 诊断播放器面板在各种状态下的可见性，找出用户"看不到侧栏和控制条"的原因。
 *
 * <p>覆盖场景：启动 → 长时间运行 → 用户拖拽改变窗口大小 → 全屏切换 → 切回。
 */
public final class PlayerVisibilityDiag {

    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        LogFile.start();
        DesktopConfig config = DesktopConfig.load();
        config.enginePort = 18990;
        config.playerSidePanelOpen = true;
        config.playerAutoHideControls = false;

        EngineController engine = new EngineController(config);
        CountDownLatch started = engine.startAsync(s -> { });
        started.await(60, TimeUnit.SECONDS);
        if (!engine.isRunning()) {
            System.out.println("engine failed");
            System.exit(1);
        }
        String origin = engine.localOrigin();
        ChannelSpec a = ChannelTable.bySlug("cctv1");

        VlcPlayer.Window w = VlcPlayer.open(origin + "/" + a.slug + ".m3u8", a,
                ChannelTable.all(), config, spec -> { }, () -> { });

        Thread.sleep(2000);
        sb.append("=== A. 启动 2s ===\n");
        dump(w.frame, sb);

        Thread.sleep(12000);
        sb.append("\n=== B. 运行 14s（远超原自动隐藏时间）===\n");
        dump(w.frame, sb);

        // 模拟用户拖拽放大窗口
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            w.frame.setSize(1600, 900);
            w.frame.validate();
        });
        Thread.sleep(1200);
        sb.append("\n=== C. 拖拽放大到 1600x900 ===\n");
        dump(w.frame, sb);

        // 模拟用户最大化
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            w.frame.setExtendedState(javax.swing.JFrame.MAXIMIZED_BOTH);
            w.frame.validate();
        });
        Thread.sleep(1500);
        sb.append("\n=== D. 最大化 ===\n");
        dump(w.frame, sb);

        // 恢复普通大小
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            w.frame.setExtendedState(javax.swing.JFrame.NORMAL);
            w.frame.setSize(1280, 760);
            w.frame.validate();
        });
        Thread.sleep(1200);
        sb.append("\n=== E. 恢复普通大小 ===\n");
        dump(w.frame, sb);

        System.out.println(sb);
        w.release();
        engine.stop();
        LogFile.flush();
        System.exit(0);
    }

    private static void dump(javax.swing.JFrame frame, StringBuilder sb) {
        sb.append("  frame=").append(frame.getWidth()).append('x').append(frame.getHeight())
                .append(" content=").append(frame.getContentPane().getWidth())
                .append('x').append(frame.getContentPane().getHeight()).append('\n');
        Container root = frame.getContentPane();
        for (Component c : root.getComponents()) {
            String n = c.getClass().getSimpleName();
            sb.append(String.format("    %-12s vis=%-5s %dx%d @%d,%d%n",
                    n, c.isVisible(), c.getWidth(), c.getHeight(), c.getX(), c.getY()));
        }
    }
}
