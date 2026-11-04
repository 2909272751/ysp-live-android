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

        // 把控制条设为可见并截图
        forceVisible(frame.getContentPane());
        frame.validate();
        frame.repaint();

        if (outPng != null) {
            try {
                // 等一帧渲染
                Thread.sleep(600);
                Rectangle r = frame.getContentPane().getBounds();
                BufferedImage img = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                frame.getContentPane().paintAll(g);
                g.dispose();
                // 判断是否抓到了真实视频（视频是 native surface，paintAll 抓不到，
                // 但 UI chrome 会画上去，足够验证控制条/侧栏）
                javax.imageio.ImageIO.write(img, "png", new File(outPng));
                sb.append("snapshotSaved=").append(outPng).append('\n');
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

