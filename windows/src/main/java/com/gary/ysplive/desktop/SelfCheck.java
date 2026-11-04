package com.gary.ysplive.desktop;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 无头自检：真的启动 {@link App}，等界面构建完成后把「实际渲染出来的结构」写到文件。
 *
 * <p>为什么需要它：当前开发会话没有可交互桌面，系统级截图只能拿到黑屏
 * （{@code Robot.createScreenCapture} 返回全黑），这不代表界面没画出来。
 * 这个自检直接从 live 场景里读控件数量和尺寸，能证伪「UI 没构建」。
 */
public final class SelfCheck {

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "selfcheck.txt";
        final StringBuilder sb = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);

        Platform.startup(() -> {
            try {
                App app = new App();
                Stage stage = new Stage();
                app.start(stage);

                // 等引擎与布局稳定
                Thread worker = new Thread(() -> {
                    try {
                        Thread.sleep(12000L);
                    } catch (InterruptedException ignored) {
                        // ignore
                    }
                    Platform.runLater(() -> {
                        try {
                            collect(stage, sb);
                        } catch (Throwable t) {
                            sb.append("COLLECT ERROR: ").append(t).append('\n');
                            t.printStackTrace(new PrintWriter(new java.io.StringWriter()));
                        }
                        done.countDown();
                    });
                }, "selfcheck-wait");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable t) {
                sb.append("START ERROR: ").append(t).append('\n');
                done.countDown();
            }
        });

        boolean finished = done.await(40, TimeUnit.SECONDS);

        try (PrintWriter pw = new PrintWriter(new FileWriter(outPath, java.nio.charset.StandardCharsets.UTF_8))) {
            pw.print(sb);
            pw.println("finished=" + finished);
        } finally {
            System.exit(0);
        }
    }

    private static void collect(Stage stage, StringBuilder sb) {
        Scene scene = stage.getScene();
        sb.append("=== SELF CHECK ===\n");
        sb.append("stageTitle=").append(stage.getTitle()).append('\n');
        sb.append("showing=").append(stage.isShowing()).append('\n');
        if (scene == null) {
            sb.append("scene=NULL\n");
            return;
        }
        sb.append("sceneSize=").append((int) scene.getWidth()).append('x')
                .append((int) scene.getHeight()).append('\n');
        sb.append("stylesheets=").append(scene.getStylesheets()).append('\n');

        javafx.scene.Parent root = scene.getRoot();
        sb.append("rootClass=").append(root.getClass().getName()).append('\n');

        int labels = 0;
        int buttons = 0;
        int chips = 0;
        int cards = 0;
        sb.append("--- labels ---\n");
        for (javafx.scene.Node n : allNodes(root)) {
            if (n instanceof Label) {
                Label l = (Label) n;
                if (labels < 12 && l.getText() != null && !l.getText().isEmpty()) {
                    sb.append("  Label[").append((int) l.getWidth()).append('x')
                            .append((int) l.getHeight()).append("] \"")
                            .append(l.getText().replace('\n', ' ')).append("\"\n");
                }
                labels++;
            } else if (n instanceof javafx.scene.control.Button) {
                buttons++;
            } else if (n instanceof ToggleButton) {
                chips++;
            }
            String cls = String.valueOf(n.getStyleClass());
            if (cls.contains("channel-card")) {
                cards++;
            }
        }
        sb.append("labelCount=").append(labels).append('\n');
        sb.append("buttonCount=").append(buttons).append('\n');
        sb.append("chipCount=").append(chips).append('\n');
        sb.append("channelCardCount=").append(cards).append('\n');

        FlowPane grid = findFlowPane(root);
        if (grid != null) {
            sb.append("gridChildren=").append(grid.getChildren().size()).append('\n');
            sb.append("gridSize=").append((int) grid.getWidth()).append('x')
                    .append((int) grid.getHeight()).append('\n');
        } else {
            sb.append("grid=NULL\n");
        }
        sb.append("=== END ===\n");
    }

    private static java.util.List<javafx.scene.Node> allNodes(javafx.scene.Node root) {
        java.util.List<javafx.scene.Node> out = new java.util.ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(javafx.scene.Node n, java.util.List<javafx.scene.Node> out) {
        out.add(n);
        if (n instanceof javafx.scene.Parent) {
            for (javafx.scene.Node c : ((javafx.scene.Parent) n).getChildrenUnmodifiable()) {
                walk(c, out);
            }
        }
    }

    private static FlowPane findFlowPane(javafx.scene.Node n) {
        if (n instanceof FlowPane) {
            return (FlowPane) n;
        }
        if (n instanceof javafx.scene.Parent) {
            for (javafx.scene.Node c : ((javafx.scene.Parent) n).getChildrenUnmodifiable()) {
                FlowPane r = findFlowPane(c);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
