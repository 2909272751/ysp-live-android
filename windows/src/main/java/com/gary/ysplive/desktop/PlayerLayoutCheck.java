package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 验证播放器启动时控制条与侧栏的**真实**可见性与位置。
 *
 * <p>这是对用户反馈"打开播放看不到暂停/换台列表"的回归测试。
 * 不调用 pinControls（那是截图专用），完全是用户打开时看到的状态。
 */
public final class PlayerLayoutCheck {

    public static void main(String[] args) throws Exception {
        LogFile.start();
        DesktopConfig config = DesktopConfig.load();
        config.enginePort = 18970;

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

        // 刚打开时（还没到自动隐藏时间）检查
        Thread.sleep(1200);
        StringBuilder sb = new StringBuilder();
        sb.append("=== t=1.2s（刚打开）===\n");
        dump(w.frame.getContentPane(), sb);

        // 等过自动隐藏时间（2.8s）再看
        Thread.sleep(3500);
        sb.append("\n=== t=4.7s（自动隐藏后）===\n");
        dump(w.frame.getContentPane(), sb);

        // 模拟鼠标移动到视频区域（重量级 Canvas 上）
        sb.append("\n=== 模拟鼠标移到视频区 ===\n");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            Component video = findByName(w.frame.getContentPane(), "JPanel");
            if (video != null) {
                java.awt.event.MouseEvent me = new java.awt.event.MouseEvent(
                        video, java.awt.event.MouseEvent.MOUSE_MOVED,
                        System.currentTimeMillis(), 0, 100, 100, 0, false);
                for (java.awt.event.MouseMotionListener l
                        : video.getMouseMotionListeners()) {
                    l.mouseMoved(me);
                }
            }
        });
        Thread.sleep(500);
        dump(w.frame.getContentPane(), sb);

        System.out.println(sb);
        w.release();
        engine.stop();
        LogFile.flush();
        System.exit(0);
    }

    private static void dump(Container root, StringBuilder sb) {
        for (Component c : root.getComponents()) {
            String n = c.getClass().getSimpleName();
            if (n.equals("ControlBar") || n.equals("SidePanel")) {
                sb.append(String.format("  %-12s visible=%-5s bounds=%dx%d @%d,%d%n",
                        n, c.isVisible(), c.getWidth(), c.getHeight(), c.getX(), c.getY()));
            }
        }
    }

    private static Component findByName(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (c.getClass().getSimpleName().equals(name)) {
                return c;
            }
            if (c instanceof Container) {
                Component r = findByName((Container) c, name);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
