package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 端到端播放自检：起引擎 -> 拿某频道的 m3u8 -> 用内置 libVLC 播放 -> 判定是否真的在播。
 *
 * <p>这是「自带播放器到底能不能用」的唯一可信判据，不看界面、不依赖系统浏览器。
 */
public final class E2EPlayCheck {

    public static void main(String[] args) throws Exception {
        String slug = args.length > 0 ? args[0] : "cctv1";
        String outPath = args.length > 1 ? args[1] : "e2e.txt";

        StringBuilder sb = new StringBuilder();
        final AtomicReference<String> verdict = new AtomicReference<>("timeout");
        final CountDownLatch done = new CountDownLatch(1);

        sb.append("=== E2E PLAY CHECK ===\n");
        sb.append("slug=").append(slug).append('\n');

        File vlcDir = VlcPlayer.libVlcDir();
        sb.append("libVlcDir=").append(vlcDir).append('\n');
        sb.append("available=").append(VlcPlayer.isAvailable()).append('\n');
        if (!VlcPlayer.isAvailable()) {
            finish(outPath, sb, "FAIL: libVLC missing");
            return;
        }

        DesktopConfig config = DesktopConfig.load();
        config.enginePort = 18920;
        EngineController engine = new EngineController(config);

        sb.append("starting engine...\n");
        CountDownLatch started = engine.startAsync(stage -> sb.append("  stage: ").append(stage).append('\n'));
        started.await(60, TimeUnit.SECONDS);

        if (!engine.isRunning()) {
            finish(outPath, sb, "FAIL: engine not ready: " + engine.lastError());
            return;
        }
        String origin = engine.localOrigin();
        sb.append("engine ready: ").append(origin).append('\n');

        String mrl = origin + "/" + slug + ".m3u8";
        sb.append("mrl=").append(mrl).append('\n');

        // 先用 HTTP 确认 m3u8 真的有分片
        String pl = httpGet(mrl, 45000);
        int segs = countSegments(pl);
        sb.append("playlistOk=").append(pl != null && pl.startsWith("#EXTM3U"))
                .append(" segments=").append(segs).append('\n');
        if (segs == 0) {
            engine.stop();
            finish(outPath, sb, "FAIL: playlist empty");
            return;
        }

        // 播放
        sb.append("opening libVLC player...\n");
        try {
            ChannelSpec spec = ChannelTable.bySlug(slug);
            if (spec == null) {
                engine.stop();
                finish(outPath, sb, "FAIL: unknown channel " + slug);
                return;
            }
            VlcPlayer.Window w = VlcPlayer.open(mrl, spec, ChannelTable.all(),
                    config, s -> { }, () -> { });
            sb.append("player opened\n");

            // 等 18 秒，看是否进入 PLAYING 且有分片推进
            long deadline = System.currentTimeMillis() + 18_000L;
            String lastState = "";
            long maxTime = 0;
            int playingHits = 0;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
                try {
                    String st = String.valueOf(w.player.status().state());
                    long t = w.player.status().time();
                    int tracks = w.player.video().trackCount();
                    if (t > maxTime) {
                        maxTime = t;
                    }
                    if (!st.equals(lastState)) {
                        sb.append("  state=").append(st)
                                .append(" t=").append(t)
                                .append(" tracks=").append(tracks).append('\n');
                        lastState = st;
                    }
                    if ("PLAYING".equals(st) && tracks > 0) {
                        playingHits++;
                    }
                } catch (Throwable e) {
                    sb.append("  read state failed: ").append(e).append('\n');
                }
                if (playingHits >= 4) {
                    break;
                }
            }

            sb.append("finalState=").append(lastState).append('\n');
            sb.append("maxProgressMs=").append(maxTime).append('\n');
            sb.append("playingHits=").append(playingHits).append('\n');

            // hls 直播的 time() 常为 0（无 seek 窗口），
            // 所以判据用 state==PLAYING + 有视频轨 + 拿到分片。
            boolean ok = "PLAYING".equals(lastState) && playingHits > 0;
            try {
                int tracks = w.player.video().trackCount();
                sb.append("videoTracks=").append(tracks).append('\n');
                ok = ok && tracks > 0;
            } catch (Throwable ignored) {
                // 忽略
            }
            w.release();
            engine.stop();
            finish(outPath, sb, ok ? "PASS" : "FAIL: not playing");
        } catch (Throwable e) {
            engine.stop();
            sb.append("EXCEPTION: ").append(e).append('\n');
            finish(outPath, sb, "FAIL: " + e.getClass().getSimpleName());
        }
    }

    private static void finish(String outPath, StringBuilder sb, String result) {
        sb.append("RESULT: ").append(result).append('\n');
        System.out.println(sb);
        try (PrintWriter pw = new PrintWriter(new FileWriter(outPath,
                java.nio.charset.StandardCharsets.UTF_8))) {
            pw.print(sb);
        } catch (Exception ignored) {
            // ignore
        }
        System.exit(result.startsWith("PASS") ? 0 : 1);
    }

    private static String httpGet(String url, int timeoutMs) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(10000);
            c.setReadTimeout(timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) {
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

    private static int countSegments(String m3u) {
        if (m3u == null) {
            return 0;
        }
        int n = 0;
        for (String line : m3u.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                n++;
            }
        }
        return n;
    }
}
