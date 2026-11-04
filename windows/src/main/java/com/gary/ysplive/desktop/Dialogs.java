package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;

/** 桌面版的各种对话框：诊断 / 设置 / 日志。 */
final class Dialogs {

    private final Stage owner;
    private final DesktopConfig config;
    private final EngineController engine;
    private final Runnable onConfigChanged;
    private final Runnable onRestartEngine;

    Dialogs(Stage owner, DesktopConfig config, EngineController engine,
            Runnable onConfigChanged, Runnable onRestartEngine) {
        this.owner = owner;
        this.config = config;
        this.engine = engine;
        this.onConfigChanged = onConfigChanged;
        this.onRestartEngine = onRestartEngine;
    }

    // ------------------------------------------------------------------ 通用

    private Stage newWindow(String title, double w, double h) {
        Stage s = new Stage();
        s.initOwner(owner);
        s.initModality(Modality.WINDOW_MODAL);
        s.setTitle(title);
        s.setWidth(w);
        s.setHeight(h);
        return s;
    }

    private void applyStyles(Scene scene) {
        try {
            java.net.URL css = Dialogs.class.getResource("/ui/app.css");
            if (css != null) {
                scene.getStylesheets().add(css.toExternalForm());
            }
            // 对话框窗口也铺同样的模糊底图，玻璃卡片才有内容可透
            java.net.URL bg = Dialogs.class.getResource("/ui/backdrop.jpg");
            if (bg != null) {
                scene.getRoot().setStyle(
                        "-fx-background-color: rgba(14,18,26,0.94);"
                        + "-fx-background-image: url('" + bg.toExternalForm() + "');"
                        + "-fx-background-size: cover;"
                        + "-fx-background-position: center center;"
                        + "-fx-background-radius: 12;");
                scene.getRoot().getStyleClass().add("dialog-root");
            }
        } catch (RuntimeException ignored) {
            // 样式缺失不影响功能
        }
    }

    void showError(String title, String message) {
        Stage s = newWindow(title, 520, 300);
        Label head = new Label(title);
        head.getStyleClass().add("dialog-title");
        Label body = new Label(message);
        body.setWrapText(true);
        body.getStyleClass().add("dialog-body");
        Button ok = new Button("知道了");
        ok.setDefaultButton(true);
        ok.setOnAction(e -> s.close());
        HBox actions = new HBox(ok);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(14, head, body, actions);
        root.setPadding(new Insets(20));
        VBox.setVgrow(body, Priority.ALWAYS);
        Scene scene = new Scene(root);
        applyStyles(scene);
        s.setScene(scene);
        s.show();
    }

    // ------------------------------------------------------------------ 诊断

    void showDiagnostics(String chromiumPath, String webHost) {
        Stage s = newWindow("诊断", 720, 620);

        StringBuilder sb = new StringBuilder();
        Gateway.Snapshot snap = engine.snapshot();
        sb.append("引擎状态：").append(engine.isRunning() ? "运行中" : "未运行").append('\n');
        if (!engine.lastError().isEmpty()) {
            sb.append("最近错误：").append(engine.lastError()).append('\n');
        }
        if (snap != null) {
            sb.append("本地服务：").append(snap.localOrigin).append('\n');
            sb.append("端口：").append(snap.port).append('\n');
            sb.append("频道数：").append(snap.channelCount).append('\n');
            sb.append("高码率协议：").append(snap.highBitrateReady ? "已就绪" : "未就绪（1080p）")
                    .append('\n');
            sb.append("运行时长：").append(snap.uptimeMs / 1000L).append(" 秒\n");
            sb.append("分片缓存：").append(snap.cacheEntries).append(" 片，命中率 ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f%%", snap.cacheHitRate))
                    .append('\n');
            sb.append("云端 GUID：").append(snap.cloudGuid).append('\n');
            sb.append("心跳次数：").append(snap.heartbeatCount).append('\n');
        }
        sb.append('\n').append("播放页宿主：").append(webHost).append('\n');
        sb.append("浏览器内核：").append(chromiumPath == null ? "未找到（Edge/Chrome）" : chromiumPath)
                .append('\n');
        sb.append("配置目录：").append(DesktopConfig.dataDir()).append('\n');
        sb.append("日志文件：").append(LogFile.path() == null ? "未启用" : LogFile.path())
                .append('\n');
        sb.append("Java：").append(System.getProperty("java.version"))
                .append("  ").append(System.getProperty("java.vendor")).append('\n');
        sb.append("系统：").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append('\n');
        sb.append('\n').append("订阅地址（可给 TiviMate / PotPlayer 等用）：\n")
                .append(snap == null ? "（引擎未就绪）" : snap.localOrigin + "/all.m3u");

        TextArea area = new TextArea(sb.toString());
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("mono");
        VBox.setVgrow(area, Priority.ALWAYS);

        Button copy = new Button("复制全部");
        copy.setOnAction(e -> {
            javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(area.getText());
            cb.setContent(content);
            copy.setText("已复制");
        });
        Button openWeb = new Button("打开订阅地址");
        openWeb.setDisable(snap == null);
        openWeb.setOnAction(e -> {
            if (snap != null) {
                Browser.open(snap.localOrigin + "/all.m3u");
            }
        });
        Button logs = new Button("查看日志");
        logs.setOnAction(e -> showLogs());
        Button close = new Button("关闭");
        close.setOnAction(e -> s.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, copy, openWeb, logs, spacer, close);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, area, actions);
        root.setPadding(new Insets(16));
        Scene scene = new Scene(root);
        applyStyles(scene);
        s.setScene(scene);
        s.show();
    }

    // ------------------------------------------------------------------ 设置

    private TextField portField;
    private CheckBox lanBox;
    private Spinner<Integer> cacheSpinner;
    private Spinner<Integer> linksSpinner;
    private TextField zapField;
    private ComboBox<String> highBitrateBox;
    private CheckBox autoStartBox;
    private CheckBox fullScreenBox;

    void showSettings() {
        Stage s = newWindow("设置", 640, 620);

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.setPadding(new Insets(4));

        int row = 0;
        grid.add(new Label("引擎端口"), 0, row);
        portField = new TextField(String.valueOf(config.enginePort));
        portField.setPrefWidth(120);
        grid.add(portField, 1, row++);

        grid.add(new Label("允许局域网访问"), 0, row);
        lanBox = new CheckBox("同网段的其它设备可用本机作为订阅源");
        lanBox.setSelected(config.allowLan);
        grid.add(lanBox, 1, row++);

        grid.add(new Label("分片缓存（MB）"), 0, row);
        cacheSpinner = new Spinner<>(16, 1024, config.cacheMb, 16);
        cacheSpinner.setPrefWidth(120);
        grid.add(cacheSpinner, 1, row++);

        grid.add(new Label("单设备并发路数"), 0, row);
        linksSpinner = new Spinner<>(0, 32, config.linksPerDevice, 1);
        linksSpinner.setPrefWidth(120);
        grid.add(linksSpinner, 1, row++);

        grid.add(new Label("换台防抖（秒）"), 0, row);
        zapField = new TextField(String.valueOf(config.zapDebounceSec));
        zapField.setPrefWidth(120);
        grid.add(zapField, 1, row++);

        grid.add(new Label("高码率模式"), 0, row);
        highBitrateBox = new ComboBox<>();
        highBitrateBox.getItems().addAll("all", "only4k", "off");
        highBitrateBox.setValue(config.highBitrateMode);
        highBitrateBox.setPrefWidth(200);
        grid.add(highBitrateBox, 1, row++);

        grid.add(new Label("启动时自动运行引擎"), 0, row);
        autoStartBox = new CheckBox("打开程序即开始拉流");
        autoStartBox.setSelected(config.autoStartEngine);
        grid.add(autoStartBox, 1, row++);

        grid.add(new Label("播放窗口全屏"), 0, row);
        fullScreenBox = new CheckBox("打开播放器时直接全屏");
        fullScreenBox.setSelected(config.fullScreenPlayer);
        grid.add(fullScreenBox, 1, row++);

        Label note = new Label("修改「引擎参数」相关项后需要重启引擎才会生效。");
        note.getStyleClass().add("hint");
        note.setWrapText(true);

        Button save = new Button("保存");
        save.setDefaultButton(true);
        Button cancel = new Button("取消");
        cancel.setOnAction(e -> s.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, note, spacer, cancel, save);
        actions.setAlignment(Pos.CENTER_LEFT);

        save.setOnAction(e -> {
            int oldPort = config.enginePort;
            boolean oldLan = config.allowLan;
            int oldCache = config.cacheMb;
            int oldLinks = config.linksPerDevice;
            double oldZap = config.zapDebounceSec;
            String oldHb = config.highBitrateMode;

            try {
                config.enginePort = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                config.enginePort = oldPort;
            }
            config.allowLan = lanBox.isSelected();
            config.cacheMb = cacheSpinner.getValue();
            config.linksPerDevice = linksSpinner.getValue();
            try {
                config.zapDebounceSec = Double.parseDouble(zapField.getText().trim());
            } catch (NumberFormatException ex) {
                config.zapDebounceSec = oldZap;
            }
            config.highBitrateMode = highBitrateBox.getValue();
            config.autoStartEngine = autoStartBox.isSelected();
            config.fullScreenPlayer = fullScreenBox.isSelected();
            config.save();

            boolean engineChanged = oldPort != config.enginePort || oldLan != config.allowLan
                    || oldCache != config.cacheMb || oldLinks != config.linksPerDevice
                    || Math.abs(oldZap - config.zapDebounceSec) > 0.001
                    || !oldHb.equals(config.highBitrateMode);
            s.close();
            if (engineChanged) {
                engine.stop();
                onRestartEngine.run();
            }
            onConfigChanged.run();
        });

        VBox root = new VBox(16, grid, actions);
        root.setPadding(new Insets(20));
        Scene scene = new Scene(root);
        applyStyles(scene);
        s.setScene(scene);
        s.setResizable(true);
        s.show();
    }

    // ------------------------------------------------------------------ 日志

    void showLogs() {
        Stage s = newWindow("运行日志", 820, 620);
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("mono");
        VBox.setVgrow(area, Priority.ALWAYS);

        Runnable refresh = () -> {
            StringBuilder sb = new StringBuilder();
            List<Log.Entry> entries = Log.recent(600);
            for (Log.Entry e : entries) {
                sb.append(e.format()).append('\n');
            }
            area.setText(sb.toString());
            area.setScrollTop(Double.MAX_VALUE);
        };
        refresh.run();

        Button reload = new Button("刷新");
        reload.setOnAction(e -> refresh.run());
        Button copy = new Button("复制全部");
        copy.setOnAction(e -> {
            javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
            c.putString(area.getText());
            cb.setContent(c);
            copy.setText("已复制");
        });
        // 安装版没有控制台，日志文件是唯一的排查入口，必须能一键打开
        Button openFile = new Button("打开日志文件");
        openFile.setOnAction(e -> {
            java.nio.file.Path p = LogFile.path();
            if (p != null && java.nio.file.Files.isRegularFile(p)) {
                Browser.openFile(p.toFile());
            } else {
                area.setText(area.getText() + "\n（日志文件尚未生成："
                        + DesktopConfig.dataDir() + "\\logs\\）");
            }
        });
        Button close = new Button("关闭");
        close.setOnAction(e -> s.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, reload, copy, openFile, spacer, close);

        VBox root = new VBox(10, area, actions);
        root.setPadding(new Insets(14));
        Scene scene = new Scene(root);
        applyStyles(scene);
        s.setScene(scene);
        s.show();
    }
}
