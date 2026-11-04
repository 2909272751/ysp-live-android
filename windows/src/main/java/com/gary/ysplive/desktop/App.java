package com.gary.ysplive.desktop;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Windows 桌面版主界面。
 *
 * <p>布局：顶部标题 + 引擎状态 + 工具按钮；中间筛选 chip；主体频道网格。
 * 双击频道（或点「播放」）打开独立播放窗口。
 */
public final class App extends Application {

    private static final String TAG = "app";
    private static final String[] FILTERS = {"全部", "央视", "卫视", "4K", "收藏"};

    private Stage stage;
    private DesktopConfig config;
    private EngineController engine;
    private LocalWebServer webServer;

    private FlowPane channelGrid;
    private Label statusLabel;
    private Label detailLabel;
    private Button continueButton;
    private final ToggleGroup filterGroup = new ToggleGroup();
    private final Map<String, Image> logoCache = new HashMap<>();

    private PlayerLauncher.Session playerSession;
    private VlcPlayer.Window vlcWindow;
    private String chromiumPath;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        // 安装版是 GUI 子系统（无控制台），日志必须落文件才能排查问题。
        LogFile.start();
        this.config = DesktopConfig.load();
        this.engine = new EngineController(config);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");

        // 毛玻璃效果的关键：底层铺一张带色块的背景图，上层控件用半透明白/黑
        // 叠加并加边框高光，视觉上才有「磨砂玻璃」质感。
        // 纯 JavaFX 没有真正的背景模糊（不像 CSS backdrop-filter），
        // 所以用「模糊底图 + 半透明层 + 1px 亮边」做出等效观感。
        StackPane layered = new StackPane();
        layered.getStyleClass().add("layered-root");

        Region backdrop = new Region();
        backdrop.getStyleClass().add("backdrop");
        try {
            java.net.URL bg = App.class.getResource("/ui/backdrop.jpg");
            if (bg != null) {
                backdrop.setStyle("-fx-background-image: url('" + bg.toExternalForm() + "');"
                        + "-fx-background-size: cover;"
                        + "-fx-background-position: center center;");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "加载背景图失败：" + e);
        }

        root.setTop(buildHeader());
        root.setCenter(buildBody());

        layered.getChildren().addAll(backdrop, root);

        Scene scene = new Scene(layered, config.windowWidth, config.windowHeight);
        try {
            java.net.URL css = App.class.getResource("/ui/app.css");
            if (css != null) {
                scene.getStylesheets().add(css.toExternalForm());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "加载样式失败：" + e);
        }

        primaryStage.setTitle("央视频全频道直播 · Windows 版");
        primaryStage.setScene(scene);
        // 场景透明，让圆角与玻璃层生效
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        primaryStage.setMinWidth(880);
        primaryStage.setMinHeight(600);
        primaryStage.setOnCloseRequest(e -> shutdown());

        // 记住窗口尺寸
        primaryStage.widthProperty().addListener((o, a, b) -> {
            if (b != null && b.doubleValue() > 200) {
                config.windowWidth = b.intValue();
            }
        });
        primaryStage.heightProperty().addListener((o, a, b) -> {
            if (b != null && b.doubleValue() > 200) {
                config.windowHeight = b.intValue();
            }
        });

        primaryStage.show();

        chromiumPath = PlayerLauncher.findChromium();
        if (chromiumPath == null) {
            Log.w(TAG, "未检测到 Edge/Chrome，播放功能不可用");
        }

        loadChannelGrid();
        startEngineAndPlayerHost();
    }

    // ------------------------------------------------------------------ 顶部

    private Region buildHeader() {
        Label title = new Label("央视频直播");
        title.getStyleClass().add("title");

        Label subtitle = new Label("Windows");
        subtitle.getStyleClass().add("subtitle");

        statusLabel = new Label("引擎未启动");
        statusLabel.getStyleClass().add("status-text");

        detailLabel = new Label("");
        detailLabel.getStyleClass().add("status-detail");

        VBox statusBox = new VBox(2, statusLabel, detailLabel);
        statusBox.setAlignment(Pos.CENTER_LEFT);

        Button diagBtn = new Button("诊断");
        diagBtn.setOnAction(e -> showDiagnostics());
        Button settingsBtn = new Button("设置");
        settingsBtn.setOnAction(e -> showSettings());
        Button logsBtn = new Button("日志");
        logsBtn.setOnAction(e -> showLogs());
        Button refreshBtn = new Button("重载列表");
        refreshBtn.setOnAction(e -> loadChannelGrid());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, title, subtitle, spacer, statusBox,
                refreshBtn, logsBtn, settingsBtn, diagBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14, 18, 10, 18));
        row.getStyleClass().add("header");

        continueButton = new Button("继续观看");
        continueButton.getStyleClass().add("primary");
        continueButton.setVisible(false);
        continueButton.setOnAction(e -> {
            ChannelSpec spec = ChannelTable.bySlug(config.lastChannel);
            if (spec != null) {
                openPlayer(spec);
            }
        });

        HBox chips = new HBox(8);
        chips.setAlignment(Pos.CENTER_LEFT);
        chips.setPadding(new Insets(0, 18, 10, 18));
        for (String f : FILTERS) {
            ToggleButton tb = new ToggleButton(f);
            tb.setToggleGroup(filterGroup);
            tb.getStyleClass().add("chip");
            tb.setSelected(f.equals(config.filter));
            tb.setOnAction(e -> {
                // ToggleGroup 允许取消选中，这里强制至少选中一个
                if (!tb.isSelected()) {
                    tb.setSelected(true);
                    return;
                }
                config.filter = f;
                config.save();
                loadChannelGrid();
            });
            chips.getChildren().add(tb);
        }
        Region chipSpacer = new Region();
        HBox.setHgrow(chipSpacer, Priority.ALWAYS);
        chips.getChildren().addAll(chipSpacer, continueButton);

        VBox top = new VBox(row, chips);
        top.getStyleClass().add("top");
        return top;
    }

    // ------------------------------------------------------------------ 主体

    private Region buildBody() {
        channelGrid = new FlowPane();
        channelGrid.setHgap(12);
        channelGrid.setVgap(12);
        channelGrid.setPadding(new Insets(14, 18, 18, 18));
        channelGrid.getStyleClass().add("grid");

        ScrollPane scroll = new ScrollPane(channelGrid);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("grid-scroll");
        return scroll;
    }

    private void loadChannelGrid() {
        List<ChannelSpec> list = config.filteredChannels();
        channelGrid.getChildren().clear();

        if (list.isEmpty()) {
            Label empty = new Label("收藏".equals(config.filter)
                    ? "还没有收藏的频道\n在频道卡片上点右键即可收藏"
                    : "没有匹配的频道");
            empty.getStyleClass().add("empty");
            channelGrid.getChildren().add(empty);
            return;
        }

        for (ChannelSpec spec : list) {
            channelGrid.getChildren().add(buildChannelCard(spec));
        }

        boolean showContinue = ChannelTable.bySlug(config.lastChannel) != null;
        continueButton.setVisible(showContinue);
        if (showContinue) {
            ChannelSpec last = ChannelTable.bySlug(config.lastChannel);
            continueButton.setText("继续观看 · " + last.name);
        }
    }

    private Region buildChannelCard(ChannelSpec spec) {
        // 台标底衬：央视频台标多为深色/彩色 logo，直接贴深色卡片会糊成一片
        // （实测台标区平均亮度 ~61，卡片底 ~32，对比度不足）。
        // 给它加一块浅色圆角底衬，这是深色 UI 里保证台标可读的标准做法。
        Label logoText = new Label(initialOf(spec.name));
        logoText.getStyleClass().add("logo-text");

        StackPane logoInner = new StackPane(logoText);
        logoInner.getStyleClass().add("logo-plate");

        StackPane logoBox = new StackPane(logoInner);
        logoBox.getStyleClass().add("logo-box");
        logoBox.setPrefSize(Double.MAX_VALUE, 66);
        StackPane.setMargin(logoInner, new Insets(3, 3, 3, 3));

        Image cached = logoCache.get(spec.logo);
        if (cached != null) {
            showLogo(logoBox, logoText, cached);
        } else {
            loadLogoAsync(spec, logoBox, logoText);
        }

        Label name = new Label(spec.name);
        name.getStyleClass().add("channel-name");

        Label badge = new Label(ChannelTable.isTrue4k(spec.slug) ? "4K" : spec.defnLabel());
        badge.getStyleClass().add("badge");

        Label group = new Label(ChannelTable.isTrue4k(spec.slug) ? "超高清" : spec.group);
        group.getStyleClass().add("channel-group");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox meta = new HBox(6, badge, spacer, group);
        meta.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(7, logoBox, name, meta);
        card.getStyleClass().add("channel-card");
        card.setPrefWidth(166);
        card.setMaxWidth(166);

        boolean fav = config.isFavorite(spec.slug);
        if (fav) {
            Label star = new Label("★");
            star.getStyleClass().add("star");
            StackPane.setAlignment(star, Pos.TOP_RIGHT);
            StackPane.setMargin(star, new Insets(4, 6, 0, 0));
            logoBox.getChildren().add(star);
        }

        card.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                boolean added = config.toggleFavorite(spec.slug);
                Log.i(TAG, (added ? "已收藏 " : "已取消收藏 ") + spec.name);
                loadChannelGrid();
                return;
            }
            if (e.getClickCount() >= 1) {
                openPlayer(spec);
            }
        });
        card.setOnMouseEntered(e -> card.getStyleClass().add("hover"));
        card.setOnMouseExited(e -> card.getStyleClass().remove("hover"));
        Tooltip.install(card, new Tooltip(
                spec.name + "\n" + spec.defnLabel() + " · " + spec.group
                        + "\n单击播放，右键收藏/取消收藏"));

        return card;
    }

    private static String initialOf(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        String t = name.trim();
        if (t.length() >= 2 && t.charAt(0) < 0x80 && t.charAt(1) < 0x80) {
            return t.substring(0, 2).toUpperCase(java.util.Locale.ROOT);
        }
        return t.substring(0, 1);
    }

    private void showLogo(StackPane box, Label fallback, Image img) {
        if (img == null || img.isError()) {
            return;
        }
        ImageView iv = new ImageView(img);
        iv.setFitWidth(62);
        iv.setFitHeight(44);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        // 台标贴到浅色底衬上（bg 由 .logo-plate 提供），
        // 深色/彩色 logo 才能与卡片背景拉开对比度。
        StackPane plate = null;
        if (!box.getChildren().isEmpty()
                && box.getChildren().get(0) instanceof StackPane) {
            plate = (StackPane) box.getChildren().get(0);
        }
        fallback.setVisible(false);
        if (plate != null) {
            if (!plate.getChildren().contains(iv)) {
                plate.getChildren().add(iv);
            }
        } else if (!box.getChildren().contains(iv)) {
            box.getChildren().add(0, iv);
        }
    }

    private void loadLogoAsync(ChannelSpec spec, StackPane box, Label fallback) {
        Thread t = new Thread(() -> {
            Image img = null;
            try {
                img = new Image(spec.logo, true);
                if (img.isError()) {
                    img = null;
                }
            } catch (RuntimeException e) {
                img = null;
            }
            final Image got = img;
            if (got != null) {
                logoCache.put(spec.logo, got);
            }
            Platform.runLater(() -> {
                if (got != null) {
                    showLogo(box, fallback, got);
                }
            });
        }, "logo-" + spec.slug);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // ------------------------------------------------------------------ 引擎

    private void startEngineAndPlayerHost() {
        setStatus("正在启动引擎…", "");

        // 先把播放页宿主服务起起来（与引擎无关，快速完成）
        try {
            String html = PlayerLauncher.readResourceText("/web/player.html");
            String hls = PlayerLauncher.readResourceText("/web/hls.min.js");
            webServer = LocalWebServer.start(html, hls);
            Log.i(TAG, "播放页服务: " + webServer.baseUrl());
        } catch (Exception e) {
            Log.e(TAG, "播放页服务启动失败：" + e);
        }

        Thread starter = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            engine.startAsync(stage -> Platform.runLater(() -> setStatus(stage, "")));
            // 等待就绪
            while (System.currentTimeMillis() - t0 < 95_000L) {
                if (engine.isRunning()) {
                    Gateway.Snapshot snap = engine.snapshot();
                    final String origin = snap == null ? "" : snap.localOrigin;
                    final int port = snap == null ? 0 : snap.port;
                    final boolean high = snap != null && snap.highBitrateReady;
                    Platform.runLater(() -> {
                        setStatus("引擎已就绪", "端口 " + port + " · "
                                + ChannelTable.all().size() + " 个频道 · "
                                + (high ? "高码率已就绪" : "1080p 模式"));
                    });
                    return;
                }
                if (!engine.lastError().isEmpty() && !engine.isRunning()) {
                    final String err = engine.lastError();
                    Platform.runLater(() -> setStatus("引擎启动失败", err));
                    return;
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            Platform.runLater(() -> setStatus("引擎启动超时", "可到「诊断」查看日志"));
        }, "engine-watch");
        starter.setDaemon(true);
        starter.start();
    }

    private void setStatus(String main, String detail) {
        if (statusLabel != null) {
            statusLabel.setText(main);
        }
        if (detailLabel != null) {
            detailLabel.setText(detail == null ? "" : detail);
        }
    }

    // ------------------------------------------------------------------ 播放

    private void openPlayer(ChannelSpec spec) {
        if (!engine.isRunning()) {
            dialogs().showError("引擎未就绪", "引擎还在启动或启动失败，请稍候或查看「诊断」。");
            return;
        }
        String origin = engine.localOrigin();
        if (origin == null || origin.isEmpty()) {
            origin = "http://127.0.0.1:" + config.enginePort;
        }
        final String streamUrl = origin + "/" + spec.slug + ".m3u8";

        config.lastChannel = spec.slug;
        config.save();
        loadChannelGrid();
        setStatus("正在播放 " + spec.name, spec.defnLabel() + " · " + spec.group);

        // 优先用内置 libVLC（真正自带播放器，无外部依赖）
        if (VlcPlayer.isAvailable()) {
            try {
                if (vlcWindow != null) {
                    vlcWindow.release();
                    vlcWindow = null;
                }
                vlcWindow = VlcPlayer.open(streamUrl, spec.name, config.fullScreenPlayer);
                Log.i(TAG, "内置播放器播放 " + spec.name + " -> " + streamUrl);
                return;
            } catch (Throwable e) {
                Log.e(TAG, "内置播放器失败，回退浏览器方案：" + e);
            }
        } else {
            Log.w(TAG, "未找到内置 libVLC，回退浏览器方案");
        }

        // 兜底：用系统 Chromium + hls.js
        if (chromiumPath == null) {
            dialogs().showError("无法播放",
                    "内置播放器不可用（缺少 vlc 目录），且系统未找到 Edge/Chrome。\n\n"
                            + "请确认程序目录下存在 vlc\\libvlc.dll，"
                            + "或安装 Microsoft Edge。\n\n诊断信息：\n"
                            + "libVLC 目录 = " + VlcPlayer.libVlcDir());
            return;
        }
        if (webServer == null) {
            dialogs().showError("无法播放", "播放页服务未启动，请查看「日志」。");
            return;
        }
        String pageUrl = webServer.baseUrl() + "/player.html?src="
                + java.net.URLEncoder.encode(streamUrl, java.nio.charset.StandardCharsets.UTF_8)
                + "&title=" + java.net.URLEncoder.encode(spec.name, java.nio.charset.StandardCharsets.UTF_8);
        try {
            if (playerSession != null) {
                playerSession.close();
                playerSession = null;
            }
            Path profile = DesktopConfig.dataDir().resolve("browser-profile");
            playerSession = PlayerLauncher.open(chromiumPath, pageUrl, spec.name,
                    config.fullScreenPlayer, profile);
            Log.i(TAG, "浏览器兜底播放 " + spec.name + " -> " + streamUrl);
        } catch (Exception e) {
            Log.e(TAG, "打开播放窗口失败：" + e);
            dialogs().showError("无法打开播放窗口", String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ 对话框

    private Dialogs dialogs() {
        return new Dialogs(stage, config, engine, this::loadChannelGrid,
                this::startEngineAndPlayerHost);
    }

    private void showDiagnostics() {
        dialogs().showDiagnostics(chromiumPath, webServer == null ? "" : webServer.baseUrl());
    }

    private void showSettings() {
        dialogs().showSettings();
    }

    private void showLogs() {
        dialogs().showLogs();
    }

    // ------------------------------------------------------------------ 生命周期

    private void shutdown() {
        config.save();
        if (vlcWindow != null) {
            vlcWindow.release();
            vlcWindow = null;
        }
        if (playerSession != null) {
            playerSession.close();
            playerSession = null;
        }
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        engine.stop();
        LogFile.flush();
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
