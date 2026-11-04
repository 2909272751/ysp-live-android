package com.gary.ysplive.desktop;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 滚动流畅度基准：真实滚动频道列表 N 秒，统计帧间隔。
 *
 * <p>用户反馈"拖动也卡卡的"，需要可量化证据。这里用 {@link AnimationTimer}
 * 采帧，输出平均帧率、最差帧、以及掉帧（&gt;32ms 即低于 30fps）比例。
 * 优化前后对比同一指标才有意义——低端机/SW 管线下这个数字最能反映问题。
 */
public final class ScrollBench {

    public static void main(String[] args) throws Exception {
        final int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        final StringBuilder sb = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);

        Platform.startup(() -> {
            try {
                App app = new App();
                Stage stage = new Stage();
                app.start(stage);

                Thread worker = new Thread(() -> {
                    try {
                        Thread.sleep(8000);
                    } catch (InterruptedException ignored) {
                        // ignore
                    }
                    Platform.runLater(() -> {
                        try {
                            ScrollPane sp = findScroll(stage.getScene().getRoot());
                            if (sp == null) {
                                sb.append("scrollpane not found\n");
                                done.countDown();
                                return;
                            }
                            // 采样必须在 FX 线程上启动，但等待要放到后台线程，
                            // 否则会阻塞脉冲线程导致一帧都采不到（自锁）。
                            startSampling(sp, seconds, sb);
                        } catch (Throwable t) {
                            sb.append("bench error: ").append(t).append('\n');
                            done.countDown();
                        }
                    });

                    // 后台线程负责等待采样完成
                    Thread waiter = new Thread(() -> {
                        try {
                            long deadline = System.currentTimeMillis() + seconds * 1000L + 12000L;
                            while (System.currentTimeMillis() < deadline) {
                                if (sb.indexOf("avgFps") >= 0) {
                                    break;
                                }
                                Thread.sleep(100);
                            }
                            Thread.sleep(300);
                        } catch (InterruptedException ignored) {
                            // ignore
                        }
                        done.countDown();
                    }, "scroll-waiter");
                    waiter.setDaemon(true);
                    waiter.start();
                }, "scrollbench");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable t) {
                sb.append("start failed: ").append(t).append('\n');
                done.countDown();
            }
        });

        done.await(120, TimeUnit.SECONDS);
        System.out.println(sb);
        java.nio.file.Files.write(java.nio.file.Paths.get(
                        args.length > 1 ? args[1] : "scroll.txt"),
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.exit(0);
    }

    private static void startSampling(ScrollPane sp, int seconds, StringBuilder sb) {
        sb.append("=== SCROLL BENCH ===\n");
        sb.append("pipeline=").append(RenderQuality.describe()).append('\n');
        sb.append("durationSec=").append(seconds).append('\n');

        final double maxV = sp.getContent().getBoundsInLocal().getHeight()
                - sp.getViewportBounds().getHeight();
        sb.append("scrollableRange=").append((int) maxV).append('\n');
        if (maxV <= 0) {
            sb.append("nothing to scroll\n");
            return;
        }

        final List<Long> frameGaps = new ArrayList<>();
        final long startNs = System.nanoTime();
        final long durationNs = seconds * 1_000_000_000L;
        final long[] last = {0};
        final double[] phase = {0};
        final javafx.scene.Node content = sp.getContent();

        // AnimationTimer 只在 JavaFX 认为需要重绘时触发。窗口不可见时脉冲会很稀疏。
        // 这里除了滚动，还持续微调透明度强制每帧重绘，模拟真实滚动时的重绘负载。
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (last[0] != 0) {
                    frameGaps.add((now - last[0]) / 1_000_000L);
                }
                last[0] = now;

                long elapsed = System.nanoTime() - startNs;
                if (elapsed >= durationNs) {
                    stop();
                    report(frameGaps, seconds, sb);
                    return;
                }
                double t = (double) elapsed / durationNs;
                double v = (1 - Math.cos(t * Math.PI * 4)) / 2.0;   // 0..1 往返两次
                sp.setVvalue(v * 0.95);
                // 不要设置 opacity / 改其他容器属性：半透明会强制整棵子树离屏合成，
                // SW 管线下代价极高（实测把 33 FPS 拉到 8 FPS）。
                // 滚动 setVvalue 本身足以持续触发重绘。
            }
        };
        timer.start();
    }

    private static void report(List<Long> gaps, int seconds, StringBuilder sb) {
        if (gaps.isEmpty()) {
            sb.append("no frames captured\n");
            return;
        }
        long sum = 0;
        long worst = 0;
        int over32 = 0;
        int over50 = 0;
        for (long g : gaps) {
            sum += g;
            worst = Math.max(worst, g);
            if (g > 32) {
                over32++;
            }
            if (g > 50) {
                over50++;
            }
        }
        double avg = (double) sum / gaps.size();
        sb.append("frames=").append(gaps.size()).append('\n');
        sb.append("avgFps=").append(String.format(java.util.Locale.ROOT, "%.1f", 1000.0 / avg))
                .append('\n');
        sb.append("avgFrameMs=").append(String.format(java.util.Locale.ROOT, "%.2f", avg))
                .append('\n');
        sb.append("worstFrameMs=").append(worst).append('\n');
        sb.append("framesOver32ms=").append(over32)
                .append(" (").append(String.format(java.util.Locale.ROOT, "%.1f",
                        100.0 * over32 / gaps.size())).append("%)\n");
        sb.append("framesOver50ms=").append(over50)
                .append(" (").append(String.format(java.util.Locale.ROOT, "%.1f",
                        100.0 * over50 / gaps.size())).append("%)\n");
    }

    private static ScrollPane findScroll(javafx.scene.Node n) {
        if (n instanceof ScrollPane) {
            return (ScrollPane) n;
        }
        if (n instanceof javafx.scene.Parent) {
            for (javafx.scene.Node c : ((javafx.scene.Parent) n).getChildrenUnmodifiable()) {
                ScrollPane r = findScroll(c);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
