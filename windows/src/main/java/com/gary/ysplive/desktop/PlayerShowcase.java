package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 启动一个真实播放窗口用于人工/截图检查（保持运行 N 秒后自动退出），
 * 并把界面组件结构 dump 到文件——这样即使没有交互桌面也能验证控件确实存在。
 *
 * <p>用法：{@code PlayerShowcase <slug> <seconds> [outPng] [outTxt]}
 */
public final class PlayerShowcase {

    public static void main(String[] args) throws Exception {
        String slug = args.length > 0 ? args[0] : "cctv6";
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        String outPng = args.length > 2 ? args[2] : null;
        String outTxt = args.length > 3 ? args[3] : null;

        LogFile.start();
        DesktopConfig config = DesktopConfig.load();
        config.enginePort = 18930;
        config.playerVolume = 80;

        EngineController engine = new EngineController(config);
        CountDownLatch started = engine.startAsync(s -> System.out.println("stage: " + s));
        started.await(60, TimeUnit.SECONDS);
        if (!engine.isRunning()) {
            System.out.println("engine failed: " + engine.lastError());
            System.exit(1);
        }
        String origin = engine.localOrigin();
        ChannelSpec spec = ChannelTable.bySlug(slug);
        if (spec == null) {
            System.out.println("unknown channel " + slug);
            System.exit(1);
        }
        String mrl = origin + "/" + spec.slug + ".m3u8";
        System.out.println("playing " + spec.name + " -> " + mrl);

        VlcPlayer.Window w = VlcPlayer.open(mrl, spec, ChannelTable.all(), config,
                s -> System.out.println("switched to " + s.name),
                () -> System.out.println("player closed"));

        Thread.sleep(9000L);

        // 主动把控制条与侧边栏显示出来，再自截图 —— 不依赖外部模拟输入
        if (w.frame != null) {
            w.pinControls();
            Thread.sleep(400);
            w.pinControls();
            SwingUtilities.invokeAndWait(() -> dumpAndSnapshot(w.frame, outPng, outTxt));
        }

        Thread.sleep(Math.max(1000L, (seconds - 9) * 1000L));
        w.release();
        engine.stop();
        LogFile.flush();
        System.exit(0);
    }

    private static void dumpAndSnapshot(JFrame frame, String outPng, String outTxt) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PLAYER UI DUMP ===\n");
        sb.append("title=").append(frame.getTitle()).append('\n');
        sb.append("showing=").append(frame.isShowing()).append('\n');
        sb.append("size=").append(frame.getWidth()).append('x').append(frame.getHeight()).append('\n');

        List<String> classes = new ArrayList<>();
        collect(frame.getContentPane(), classes, 0);
        sb.append("componentCount=").append(classes.size()).append('\n');
        for (String c : classes) {
            sb.append("  ").append(c).append('\n');
        }

        // 关键控件存在性判定
        String all = classes.toString();
        sb.append("hasSidePanel=").append(all.contains("SidePanel")).append('\n');
        sb.append("hasControlBar=").append(all.contains("ControlBar")).append('\n');
        sb.append("hasOsd=").append(all.contains("Osd")).append('\n');
        sb.append("hasCanvas=").append(all.contains("java.awt.Canvas")).append('\n');

        // 把控制条/侧栏设为可见并截图。
        // 注意：控制条有"静止 2.8 秒自动隐藏"的定时器，截图前必须重新显示，
        // 否则拍到的是隐藏态（看起来像"没有控制条"）。
        forceVisible(frame.getContentPane());
        frame.validate();
        frame.repaint();

        if (outPng != null) {
            try {
                Thread.sleep(800);
                // 再次强制显示（定时器可能又把它藏了）
                forceVisible(frame.getContentPane());
                frame.validate();
                frame.doLayout();
                Thread.sleep(300);

                Rectangle r = frame.getContentPane().getBounds();
                BufferedImage img = new BufferedImage(
                        Math.max(1, r.width), Math.max(1, r.height),
                        BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                g.setColor(java.awt.Color.BLACK);
                g.fillRect(0, 0, r.width, r.height);
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

                // Swing 的 printAll/paintAll 在带 native peer 的窗口上不可靠
                // （实测输出全黑）。改成自己递归：对每个可见组件做坐标平移后
                // 调用它的 paint()，这样控制条、侧栏、按钮都会真实绘制出来。
                paintTree(frame.getContentPane(), g);
                g.dispose();
                javax.imageio.ImageIO.write(img, "png", new File(outPng));
                sb.append("snapshotSaved=").append(outPng).append('\n');

                // 统计非黑像素比例，判断是否真的画出了 UI
                int nonBlack = 0;
                for (int y = 0; y < img.getHeight(); y += 3) {
                    for (int x = 0; x < img.getWidth(); x += 3) {
                        int rgb = img.getRGB(x, y);
                        if ((rgb & 0xFFFFFF) != 0) {
                            nonBlack++;
                        }
                    }
                }
                int total = (img.getWidth() / 3 + 1) * (img.getHeight() / 3 + 1);
                sb.append("nonBlackRatio=")
                        .append(String.format(java.util.Locale.ROOT, "%.1f%%",
                                100.0 * nonBlack / Math.max(1, total))).append('\n');
            } catch (Throwable t) {
                sb.append("snapshotFailed=").append(t).append('\n');
            }
        }

        System.out.println(sb);
        if (outTxt != null) {
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get(outTxt),
                        sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    /**
     * 递归绘制组件树。
     * <p>Swing 的 printAll/paintAll 在带 native peer 的窗口上不可靠（实测全黑），
     * 这里手动做坐标平移后调用每个组件的 paint()。
     * Canvas 是 libVLC 的 native 视频表面，paint() 抓不到，直接跳过。
     */
    private static void paintTree(java.awt.Component c, java.awt.Graphics2D g) {
        if (!c.isVisible() || c.getWidth() <= 0 || c.getHeight() <= 0) {
            return;
        }
        if (c.getClass().getSimpleName().equals("Canvas")) {
            return;
        }
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
        g2.translate(c.getX(), c.getY());
        try {
            c.paint(g2);
        } catch (Throwable ignored) {
            // 单个组件绘制失败不影响其它
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                paintTree(child, g2);
            }
        }
        g2.dispose();
    }

    /** 递归把控制条和侧栏设为可见（模拟"鼠标移入"的效果）。 */
    private static void forceVisible(Container c) {
        for (Component child : c.getComponents()) {
            String n = child.getClass().getSimpleName();
            if (n.equals("ControlBar") || n.equals("SidePanel")) {
                child.setVisible(true);
            }
            if (child instanceof Container) {
                forceVisible((Container) child);
            }
        }
    }

    private static void collect(Container c, List<String> out, int depth) {
        for (Component child : c.getComponents()) {
            String indent = depth == 0 ? "" : String.format("%" + (depth * 2) + "s", "");
            out.add(indent + child.getClass().getSimpleName()
                    + " visible=" + child.isVisible()
                    + " bounds=" + child.getBounds().width + "x" + child.getBounds().height);
            if (child instanceof Container && depth < 4) {
                collect((Container) child, out, depth + 1);
            }
        }
    }
}

