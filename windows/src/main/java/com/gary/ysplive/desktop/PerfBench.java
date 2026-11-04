package com.gary.ysplive.desktop;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 报告实际渲染管线、以及界面构建/筛选切换的耗时基准。
 *
 * <p>这个工具存在的意义：用户反馈「卡卡的、不跟手」，
 * 需要可量化的证据证明优化有效，而不是凭感觉说"应该快了"。
 */
public final class PerfBench {

    public static void main(String[] args) throws Exception {
        final StringBuilder sb = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);

        Platform.startup(() -> {
            try {
                long t0 = System.nanoTime();
                App app = new App();
                Stage stage = new Stage();
                app.start(stage);
                long tBuild = System.nanoTime() - t0;

                final long buildMs = tBuild / 1_000_000L;

                Thread worker = new Thread(() -> {
                    try {
                        Thread.sleep(7000);
                    } catch (InterruptedException ignored) {
                        // ignore
                    }
                    Platform.runLater(() -> {
                        try {
                            Object pipeline = com.sun.prism.GraphicsPipeline.getPipeline();
                            sb.append("=== PERF BENCH ===\n");
                            sb.append("pipeline=").append(pipeline.getClass().getSimpleName()).append('\n');
                            sb.append("hardwareAccel=")
                                    .append(!pipeline.getClass().getSimpleName().contains("SW"))
                                    .append('\n');
                            sb.append("startupBuildMs=").append(buildMs).append('\n');
                            sb.append("sceneSize=").append((int) stage.getScene().getWidth())
                                    .append('x').append((int) stage.getScene().getHeight()).append('\n');

                            // 筛选切换基准：连续切换 20 次，测平均耗时
                            // （旧实现每次都重建 64 个节点树，这里是关键对比项）
                            long t = System.nanoTime();
                            for (int i = 0; i < 20; i++) {
                                app.benchApplyFilter(i % 2 == 0 ? "全部" : "央视");
                            }
                            long tFilter = (System.nanoTime() - t) / 1_000_000L;
                            sb.append("filterSwitch20x=").append(tFilter).append("ms\n");
                            sb.append("filterSwitchAvg=")
                                    .append(String.format(java.util.Locale.ROOT, "%.2f",
                                            tFilter / 20.0)).append("ms\n");

                            // 频道数
                            sb.append("channels=").append(
                                    com.gary.ysplive.core.data.ChannelTable.all().size()).append('\n');
                        } catch (Throwable e) {
                            sb.append("bench failed: ").append(e).append('\n');
                        }
                        done.countDown();
                    });
                }, "bench");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable e) {
                sb.append("start failed: ").append(e).append('\n');
                done.countDown();
            }
        });

        done.await(90, TimeUnit.SECONDS);
        System.out.println(sb);
        java.nio.file.Files.write(java.nio.file.Paths.get(
                args.length > 0 ? args[0] : "perf.txt"),
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.exit(0);
    }
}
