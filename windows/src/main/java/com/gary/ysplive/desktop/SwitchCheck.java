package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 端到端验证「播放器内换台」：真的去点侧栏里的频道行，看视频流是否切换。
 *
 * <p>这是对之前那个 bug 的回归测试：侧栏点击只更新标题、不换流。
 * 测试方式：找到 SidePanel 里的 JPanel 行，对目标行派发 mouseClicked，
 * 然后检查 window title 与解码状态是否都变成新频道。
 */
public final class SwitchCheck {

    public static void main(String[] args) throws Exception {
        LogFile.start();
        DesktopConfig config = DesktopConfig.load();
        config.enginePort = 18961;

        EngineController engine = new EngineController(config);
        CountDownLatch started = engine.startAsync(s -> { });
        started.await(60, TimeUnit.SECONDS);
        if (!engine.isRunning()) {
            System.out.println("engine failed: " + engine.lastError());
            System.exit(1);
        }
        String origin = engine.localOrigin();

        ChannelSpec a = ChannelTable.bySlug("cctv1");
        ChannelSpec b = ChannelTable.bySlug("cctv6");
        if (a == null || b == null) {
            System.out.println("channel missing");
            System.exit(1);
        }

        AtomicReference<ChannelSpec> notified = new AtomicReference<>();
        VlcPlayer.Window w = VlcPlayer.open(origin + "/" + a.slug + ".m3u8", a,
                ChannelTable.all(), config,
                spec -> notified.set(spec),
                () -> { });

        Thread.sleep(7000);
        String titleBefore = w.frame.getTitle();
        System.out.println("=== BEFORE ===");
        System.out.println("  title = " + titleBefore);
        System.out.println("  state = " + w.player.status().state());

        // 找到侧栏并点 cctv6 那一行
        Component side = findComponent(w.frame.getContentPane(), "SidePanel");
        if (side == null) {
            System.out.println("RESULT: FAIL-NO-SIDEPANEL");
            System.exit(1);
        }
        int targetIndex = indexOfChannel(b);
        if (targetIndex < 0) {
            System.out.println("RESULT: FAIL-NO-TARGET-INDEX");
            System.exit(1);
        }
        System.out.println("  target row index = " + targetIndex + " (" + b.name + ")");

        List<Container> rows = findRows(side);
        System.out.println("  rows found = " + rows.size());
        if (targetIndex >= rows.size()) {
            System.out.println("RESULT: FAIL-ROW-OUT-OF-RANGE");
            System.exit(1);
        }
        Container target = rows.get(targetIndex);
        // 在 EDT 上派发点击
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            MouseEvent ev = new MouseEvent(target, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 5, 5, 1, false);
            for (java.awt.event.MouseListener ml : target.getMouseListeners()) {
                ml.mouseClicked(ev);
            }
        });

        Thread.sleep(7000);
        String titleAfter = w.frame.getTitle();
        System.out.println();
        System.out.println("=== AFTER ===");
        System.out.println("  title     = " + titleAfter);
        System.out.println("  state     = " + w.player.status().state());
        System.out.println("  dim       = " + w.player.video().videoDimension());
        System.out.println("  notified  = " + (notified.get() == null ? "null" : notified.get().name));

        // 严格验证「流真的换了」：直接向引擎请求两个频道的播放列表，
        // 比对分片地址前缀。如果换台只改了标题没换流，分片地址会一模一样。
        String pa = httpGet(origin + "/" + a.slug + ".m3u8");
        String pb = httpGet(origin + "/" + b.slug + ".m3u8");
        String segA = firstSeg(pa);
        String segB = firstSeg(pb);
        boolean streamDiffers = segA != null && segB != null && !segA.equals(segB);
        System.out.println("  segA head = " + head(segA));
        System.out.println("  segB head = " + head(segB));
        System.out.println("  streamDiffers = " + streamDiffers);

        boolean titleChanged = titleAfter.contains(b.name);
        boolean notifyOk = notified.get() != null && b.slug.equals(notified.get().slug);
        boolean playing = w.player.status().isPlaying();

        System.out.println();
        System.out.println("titleChanged  = " + titleChanged);
        System.out.println("notifyOk      = " + notifyOk);
        System.out.println("playing       = " + playing);
        System.out.println("streamDiffers = " + streamDiffers);
        String verdict = (titleChanged && notifyOk && playing && streamDiffers)
                ? "PASS" : "FAIL";
        System.out.println("RESULT: " + verdict);

        w.release();
        engine.stop();
        LogFile.flush();
        System.exit("PASS".equals(verdict) ? 0 : 1);
    }

    private static int indexOfChannel(ChannelSpec spec) {
        List<ChannelSpec> all = ChannelTable.all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).slug.equals(spec.slug)) {
                return i;
            }
        }
        return -1;
    }

    /** 侧栏里每个频道行是 JPanel(BorderLayout)，标题是 JLabel。 */
    private static List<Container> findRows(Component side) {
        List<Container> out = new ArrayList<>();
        collectRows(side, out);
        return out;
    }

    private static void collectRows(Component c, List<Container> out) {
        if (c instanceof Container) {
            Container ct = (Container) c;
            if (ct.getClass().getSimpleName().equals("JPanel")
                    && ct.getLayout() instanceof java.awt.BorderLayout
                    && ct.getMouseListeners().length > 0
                    && ct.getComponentCount() >= 2) {
                out.add(ct);
            }
            for (Component child : ct.getComponents()) {
                collectRows(child, out);
            }
        }
    }

    private static Component findComponent(Container root, String simpleName) {
        for (Component c : root.getComponents()) {
            if (c.getClass().getSimpleName().equals(simpleName)) {
                return c;
            }
            if (c instanceof Container) {
                Component r = findComponent((Container) c, simpleName);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static String httpGet(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            if (c.getResponseCode() != 200) {
                return null;
            }
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                }
            }
            return new String(b.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取 m3u8 里第一个非注释行（分片地址）。 */
    private static String firstSeg(String m3u8) {
        if (m3u8 == null) {
            return null;
        }
        for (String line : m3u8.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                return t;
            }
        }
        return null;
    }

    private static String head(String s) {
        if (s == null) {
            return "null";
        }
        // 分片地址很长，取尾部（含频道 sid 与时间戳）更有区分度
        int from = Math.max(0, s.length() - 60);
        return "..." + s.substring(from);
    }
}
