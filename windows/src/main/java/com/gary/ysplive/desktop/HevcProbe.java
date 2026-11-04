package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.TrackDescription;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 4K/8K 专项诊断：区分「真的出画」和「只有音频轨」。
 *
 * <p>E2EPlayCheck 只看 state=PLAYING + trackCount>0，这对 1080p 够用，
 * 但对 HEVC 频道不够——libVLC 可能只解出音频轨就报 PLAYING。
 * 这里逐项检查：视频轨数量、实际解码分辨率、是否真的产生时间推进。
 */
public final class HevcProbe {

    public static void main(String[] args) throws Exception {
        String slug = args.length > 0 ? args[0] : "cctv4k";
        String mrl = args.length > 1 ? args[1] : null;

        System.out.println("=== HEVC PROBE: " + slug + " ===");

        if (mrl == null) {
            System.out.println("需要传入流地址：HevcProbe <slug> <mrl>");
            System.exit(2);
        }

        java.io.File vlcDir = VlcPlayer.libVlcDir();
        System.setProperty("jna.library.path", vlcDir.getAbsolutePath());

        MediaPlayerFactory factory = new MediaPlayerFactory(
                "--no-video-title-show", "--quiet", "--no-osd");
        final uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer player =
                factory.mediaPlayers().newEmbeddedMediaPlayer();

        final AtomicReference<String> lastErr = new AtomicReference<>("");
        final java.util.concurrent.atomic.AtomicLong maxTime =
                new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicInteger voutCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final CountDownLatch playing = new CountDownLatch(1);

        player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mp) {
                playing.countDown();
            }

            @Override
            public void timeChanged(MediaPlayer mp, long t) {
                if (t > maxTime.get()) {
                    maxTime.set(t);
                }
            }

            @Override
            public void videoOutput(MediaPlayer mp, int count) {
                voutCount.set(count);
                System.out.println("  videoOutput event: count=" + count);
            }

            @Override
            public void error(MediaPlayer mp) {
                lastErr.set("error event");
                System.out.println("  ERROR event");
            }
        });

        // 需要一个画布才能有 vout
        java.awt.Canvas canvas = new java.awt.Canvas();
        javax.swing.JFrame frame = new javax.swing.JFrame("hevc probe");
        frame.add(canvas);
        frame.setSize(960, 600);
        javax.swing.SwingUtilities.invokeLater(() -> frame.setVisible(true));
        Thread.sleep(500);
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas));

        player.media().play(mrl);
        playing.await(20, TimeUnit.SECONDS);

        // 等 12 秒积累数据
        for (int i = 0; i < 24; i++) {
            Thread.sleep(500);
        }

        System.out.println();
        System.out.println("--- 结果 ---");
        System.out.println("state        = " + player.status().state());
        System.out.println("maxTimeMs    = " + maxTime.get());
        System.out.println("voutCount    = " + voutCount.get());
        System.out.println("videoOutputs = " + player.status().videoOutputs());
        System.out.println("trackCount   = " + player.video().trackCount());
        System.out.println("dimension    = " + player.video().videoDimension());

        try {
            List<TrackDescription> vtd = player.video().trackDescriptions();
            System.out.println("videoTracks  = " + vtd.size());
            for (TrackDescription t : vtd) {
                System.out.println("   id=" + t.id() + " desc=" + t.description());
            }
        } catch (Throwable e) {
            System.out.println("videoTracks  = (读取失败 " + e + ")");
        }
        try {
            List<TrackDescription> atd = player.audio().trackDescriptions();
            System.out.println("audioTracks  = " + atd.size());
        } catch (Throwable e) {
            System.out.println("audioTracks  = (读取失败)");
        }

        java.awt.Dimension d = player.video().videoDimension();
        boolean hasVideo = d != null && d.width > 0 && d.height > 0;
        boolean progress = maxTime.get() > 1000;
        boolean hd = hasVideo && d.width >= 3000;

        System.out.println();
        System.out.println("hasVideoDecoded = " + hasVideo);
        System.out.println("is4kResolution  = " + hd + " (" + (d == null ? "null" : d.width + "x" + d.height) + ")");
        System.out.println("progressing     = " + progress);
        System.out.println("VERDICT: " + (hasVideo && progress
                ? (hd ? "OK-4K" : "OK-BUT-NOT-4K") : "FAIL-NO-VIDEO"));

        player.controls().stop();
        player.release();
        factory.release();
        javax.swing.SwingUtilities.invokeLater(frame::dispose);
        System.exit(0);
    }
}
