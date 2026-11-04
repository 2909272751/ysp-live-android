import java.io.File;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurface;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 决定性实验：libVLC 能否播放央视频的 HLS 直播流？
 * <p>如果行，Windows 版就可以真正自带播放器（不依赖系统 Edge/Chrome）。
 */
public final class VlcProbe {

    public static void main(String[] args) throws Exception {
        String vlcDir = args.length > 0 ? args[0] : null;
        String url = args.length > 1 ? args[1] : "http://127.0.0.1:18900/cctv1.m3u8";

        System.out.println("vlcDir = " + vlcDir);
        System.out.println("url    = " + url);

        final AtomicBoolean playing = new AtomicBoolean(false);
        final AtomicReference<String> verdict = new AtomicReference<>("timeout");
        final AtomicReference<String> lastError = new AtomicReference<>("");

        MediaPlayerFactory factory = new MediaPlayerFactory("--no-video-title-show", "--quiet");
        EmbeddedMediaPlayer player = factory.mediaPlayers().newEmbeddedMediaPlayer();

        JFrame frame = new JFrame("vlc probe");
        Canvas canvas = new Canvas();
        canvas.setBackground(java.awt.Color.BLACK);
        frame.getContentPane().add(canvas, BorderLayout.CENTER);
        frame.setSize(900, 560);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas));
        player.events().addMediaPlayerEventListener(new uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mp) {
                System.out.println(">>> PLAYING");
                playing.set(true);
                verdict.set("OK:playing");
            }

            @Override
            public void timeChanged(MediaPlayer mp, long time) {
                if (time > 500 && !playing.get()) {
                    System.out.println(">>> TIME " + time + "ms");
                    playing.set(true);
                    verdict.set("OK:time=" + time);
                }
            }

            @Override
            public void error(MediaPlayer mp) {
                String e = String.valueOf(mp.status().videoOutputs());
                System.out.println("ERROR event, vouts=" + e);
                lastError.set("error event");
            }

            @Override
            public void buffering(MediaPlayer mp, float newCache) {
                // 只打印偶尔几次，避免刷屏
                if (newCache > 0 && newCache < 100 && newCache % 25 < 1) {
                    System.out.println("  buffering " + newCache + "%");
                }
            }

            @Override
            public void lengthChanged(MediaPlayer mp, long length) {
                System.out.println("  length=" + length);
            }
        });

        SwingUtilities.invokeLater(() -> frame.setVisible(true));
        Thread.sleep(600);

        System.out.println("calling play()...");
        boolean started = player.media().play(url);
        System.out.println("play() returned " + started);

        long deadline = System.currentTimeMillis() + 25_000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            if (playing.get()) {
                break;
            }
        }

        System.out.println();
        System.out.println("--- stats ---");
        try {
            System.out.println("state    = " + player.status().state());
            System.out.println("time     = " + player.status().time());
            System.out.println("playable = " + player.status().isPlayable());
            System.out.println("tracks   = " + player.video().trackCount());
            System.out.println("size     = " + player.video().videoDimension());
        } catch (Throwable t) {
            System.out.println("stats failed: " + t);
        }

        System.out.println();
        System.out.println("VERDICT: " + verdict.get());

        Thread.sleep(1500);
        player.controls().stop();
        player.release();
        factory.release();
        SwingUtilities.invokeLater(frame::dispose);
        System.exit(verdict.get().startsWith("OK") ? 0 : 1);
    }
}
