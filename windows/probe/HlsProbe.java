import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 决定性实验：JavaFX MediaPlayer 能否播放央视频的 HLS 直播流？
 * <p>这决定 Windows 版的播放器架构（JavaFX Media vs WebView+hls.js vs 外部播放器）。
 */
public final class HlsProbe {

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://127.0.0.1:18900/cctv1.m3u8";
        System.out.println("probe url = " + url);

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> verdict = new AtomicReference<>("timeout");
        final AtomicBoolean ready = new AtomicBoolean(false);

        Platform.startup(() -> {
            try {
                System.out.println("JavaFX started, creating Media...");
                Media media = new Media(url);
                System.out.println("Media created, duration=" + media.getDuration());
                media.errorProperty().addListener((o, a, b) -> {
                    System.out.println("MEDIA ERROR: " + b);
                    verdict.set("media-error: " + b);
                    done.countDown();
                });

                MediaPlayer player = new MediaPlayer(media);
                MediaView view = new MediaView(player);

                Stage stage = new Stage();
                stage.setScene(new Scene(new StackPane(view), 640, 360));
                stage.setTitle("hls probe");
                stage.show();

                player.setOnReady(() -> System.out.println("onReady: status=" + player.getStatus()));

                player.statusProperty().addListener((o, a, b) -> {
                    System.out.println("status -> " + b);
                });

                player.setOnPlaying(() -> {
                    System.out.println(">>> PLAYING! currentTime=" + player.getCurrentTime());
                    ready.set(true);
                });

                player.currentTimeProperty().addListener((o, a, b) -> {
                    if (b != null && b.greaterThan(Duration.ZERO)) {
                        ready.set(true);
                    }
                });

                player.setOnError(() -> {
                    System.out.println("PLAYER ERROR: " + player.getError());
                    verdict.set("player-error: " + player.getError());
                    done.countDown();
                });

                player.play();
                System.out.println("play() called");

                // 给 12 秒观察
                new Thread(() -> {
                    for (int i = 0; i < 24; i++) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (ready.get()) {
                            verdict.set("OK: playing at " + player.getCurrentTime());
                            done.countDown();
                            return;
                        }
                    }
                    if (!ready.get()) {
                        verdict.set("FAIL: 12s 内未开始播放, status=" + player.getStatus()
                                + " time=" + player.getCurrentTime());
                        done.countDown();
                    }
                }, "probe-watch").start();

            } catch (Throwable t) {
                System.out.println("EXCEPTION: " + t);
                t.printStackTrace();
                verdict.set("exception: " + t);
                done.countDown();
            }
        });

        try {
            done.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            // fallthrough
        }
        System.out.println();
        System.out.println("VERDICT: " + verdict.get());
        Platform.exit();
        System.exit(verdict.get().startsWith("OK") ? 0 : 1);
    }
}
