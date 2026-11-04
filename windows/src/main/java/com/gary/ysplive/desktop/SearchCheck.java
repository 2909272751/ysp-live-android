package com.gary.ysplive.desktop;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 验证搜索过滤真的生效。
 *
 * <p>做法：真启动 App，往搜索框里输入关键词，检查网格里剩下的频道数
 * 和内容是否符合预期。同时验证「清空后恢复」。
 */
public final class SearchCheck {

    public static void main(String[] args) throws Exception {
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
                            runChecks(app, sb);
                        } catch (Throwable t) {
                            sb.append("check error: ").append(t).append('\n');
                        }
                        done.countDown();
                    });
                }, "search-check");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable t) {
                sb.append("start failed: ").append(t).append('\n');
                done.countDown();
            }
        });

        done.await(90, TimeUnit.SECONDS);
        System.out.println(sb);
        if (args.length > 0) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        System.exit(0);
    }

    private static void runChecks(App app, StringBuilder sb) throws Exception {
        Field f = App.class.getDeclaredField("searchField");
        f.setAccessible(true);
        TextField search = (TextField) f.get(app);
        Field g = App.class.getDeclaredField("virtualGrid");
        g.setAccessible(true);
        VirtualChannelGrid grid = (VirtualChannelGrid) g.get(app);

        sb.append("=== SEARCH CHECK ===\n");
        sb.append("initialCount=").append(grid.items().size()).append('\n');

        // 1) 搜 "6"：应命中 CCTV-6 / CCTV-16 / 16-4K 等
        search.setText("6");
        boolean sixOk = grid.items().size() > 0 && grid.items().size() < 64;
        sb.append("search6.count=").append(grid.items().size())
                .append(" narrowed=").append(sixOk).append('\n');

        // 2) 搜 "CCTV-6"：应精确一些
        search.setText("CCTV-6");
        sb.append("searchCCTV6.count=").append(grid.items().size()).append('\n');
        boolean cctv6Hit = false;
        for (com.gary.ysplive.core.data.ChannelSpec s : grid.items()) {
            if (s.name.contains("CCTV-6")) {
                cctv6Hit = true;
                break;
            }
        }
        sb.append("searchCCTV6.containsTarget=").append(cctv6Hit).append('\n');

        // 3) 搜不存在的词：应为 0
        search.setText("zzzz不存在");
        sb.append("searchNone.count=").append(grid.items().size()).append('\n');

        // 4) 清空：应恢复全部 64
        search.clear();
        boolean restored = grid.items().size() == 64;
        sb.append("afterClear.count=").append(grid.items().size())
                .append(" restored=").append(restored).append('\n');

        boolean pass = sixOk && cctv6Hit && restored;
        sb.append("RESULT: ").append(pass ? "PASS" : "FAIL").append('\n');
    }
}
