package com.gary.ysplive.desktop;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Windows 桌面版主界面。
 *
 * <h2>性能设计（这版重点重做过）</h2>
 * 初版在低端机器上明显卡顿，用户反馈「hover 几秒才高亮」「拖动卡」。
 * 逐项定位到四个根因，这里都修掉了：
 *
 * <ol>
 *   <li><b>强制软件渲染</b> — 打包参数里写了 {@code -Dprism.order=sw}，
 *       等于在所有机器上禁用 GPU。已改为 {@code d3d,sw} 让 JavaFX 优先用
 *       D3D11 硬件管线，仅在不可用时回退软件。</li>
 *   <li><b>hover 靠 Java 代码改 style class</b> — 每次 add/remove 都会让 CSS
 *       引擎重新解析子树样式并触发 relayout，卡顿感就来自这里。
 *       已全部改用 CSS 原生 {@code :hover}。</li>
 *   <li><b>gaussian 阴影</b> — 逐像素卷积，hover 时重算阴影非常昂贵。
 *       已改为纯色边框表达层次。</li>
 *   <li><b>每张卡片一个线程加载台标</b> — 64 个频道就是 64 个线程同时
 *       去抢网络，互相拖慢。已改为固定 4 线程的池 + 内存缓存。</li>
 * </ol>
 *
 * <p>另外把频道卡片改为「只创建一次、筛选时复用」，切换筛选不再重建 64 个
 * 节点树（旧实现每次 clear + rebuild）。
 */
public final class App extends Application {

    private static final String TAG = "app";
    /**
     * 版本号。<b>每次发布必须递增</b>——
     * 之前排查"看不到控制条"时无法确认用户装的是哪一版，浪费了很多时间。
     * 现在标题栏、诊断页都会显示这个版本号，一眼可辨。
     */
    static final String VERSION = "9.5.6";
    private static final String[] FILTERS = {"全部", "央视", "卫视", "4K", "收藏"};

    private Stage stage;
    private DesktopConfig config;
    private EngineController engine;
    private LocalWebServer webServer;

    private VirtualChannelGrid virtualGrid;
    /** 频道搜索框（输入即时过滤）。 */
    private javafx.scene.control.TextField searchField;
    private Label statusLabel;
    private Label detailLabel;
    private Button continueButton;
    private final ToggleGroup filterGroup = new ToggleGroup();

    /** 台标缓存（并发安全，后台线程写、UI 线程读）。 */
    private final Map<String, Image> logoCache = new ConcurrentHashMap<>();
    /** 台标下载专用线程池：固定 4 个，避免 64 个频道开 64 个线程抢带宽。 */
    private ExecutorService logoPool;

    /** 卡片复用池：slug -> 已构建好的卡片。切换筛选只改可见性，不重建。 */
    private final Map<String, Region> cardPool = new ConcurrentHashMap<>();
    /** 卡片对应的频道（用于 hover/点击时查回数据）。 */
    private final Map<Region, ChannelSpec> cardSpec = new ConcurrentHashMap<>();

    private PlayerLauncher.Session playerSession;
    private VlcPlayer.Window vlcWindow;
    private String chromiumPath;
    private Tray tray;
    /** 用户是「最小化到托盘」还是真要退出。 */
    private volatile boolean exiting;
    /** 托盘是否安装成功（失败时关闭窗口即退出，避免程序变幽灵）。 */
    private volatile boolean trayInstalled;

    /** 当前正在播放的频道（用于卡片高亮）。 */
    private volatile String playingSlug = "";

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        // 日志先起来，这样单实例判定的结果能落盘（否则日志里查不到原因）。
        LogFile.start();

        // 单实例保护：避免多个窗口争抢同一引擎端口、互相覆盖配置。
        if (!SingleInstance.acquire()) {
            Log.w(TAG, "已有实例在运行，本实例退出");
            // 注意：这里必须用非阻塞的 show()，不能用 showAndWait()。
            // showAndWait 在无交互桌面/远程会话下不会返回，进程会永久挂住
            // （实测表现为"第二个实例还在跑但什么都不做"）。
            // 因此再挂一个兜底定时器，无论如何 2.5 秒后强制退出。
            javafx.animation.PauseTransition bail =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2.5));
            bail.setOnFinished(e -> {
                Log.w(TAG, "单实例兜底退出");
                Platform.exit();
                Runtime.getRuntime().halt(0);
            });
            bail.play();
            try {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.INFORMATION);
                alert.setTitle("央视频直播");
                alert.setHeaderText("程序已经在运行");
                alert.setContentText("请切换到已打开的窗口。\n\n"
                        + "如果看不到窗口，请在任务栏里找；"
                        + "或者先结束残留的 YspLive 进程再重试。");
                alert.show();
            } catch (Throwable t) {
                Log.w(TAG, "无法显示提示窗口: " + t);
            }
            return;
        }

        this.config = DesktopConfig.load();
        this.engine = new EngineController(config);

        // 台标池：固定 4 线程 + 守护线程，进程退出不阻塞
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "logo-" + seq.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        };
        logoPool = Executors.newFixedThreadPool(4, tf);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");

        // 背景层：硬件渲染时用模糊色块图做玻璃质感；
        // 软件渲染时直接省掉这一层——root-pane 本身就是不透明底色，
        // 叠一层图 + StackPane 只会多一次合成（实测每帧多 3-5ms）。
        RenderQuality.detect();

        Region backdrop = null;
        if (RenderQuality.useBackdropImage()) {
            backdrop = new Region();
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
        } else {
            Log.i(TAG, "软件渲染：不叠加背景图，用纯色底保证滚动流畅");
        }

        root.setTop(buildHeader());
        root.setCenter(buildBody());

        StackPane layered = new StackPane();
        layered.getStyleClass().add("layered-root");
        if (backdrop != null) {
            layered.getChildren().addAll(backdrop, root);
        } else {
            layered.getChildren().add(root);
        }

        Scene scene = new Scene(layered, config.windowWidth, config.windowHeight);
        try {
            java.net.URL css = App.class.getResource("/ui/app.css");
            if (css != null) {
                scene.getStylesheets().add(css.toExternalForm());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "加载样式失败：" + e);
        }

        primaryStage.setTitle("央视频全频道直播 v" + VERSION + " · Windows 版");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(880);
        primaryStage.setMinHeight(600);

        // 全局快捷键
        // Ctrl+F 聚焦搜索；Ctrl+R 重载列表；F5 同 Ctrl+R。
        try {
            scene.getAccelerators().put(
                    javafx.scene.input.KeyCombination.keyCombination("Shortcut+F"),
                    () -> {
                        if (searchField != null) {
                            searchField.requestFocus();
                            searchField.selectAll();
                        }
                    });
            scene.getAccelerators().put(
                    javafx.scene.input.KeyCombination.keyCombination("Shortcut+R"),
                    this::loadChannelGrid);
            scene.getAccelerators().put(
                    javafx.scene.input.KeyCombination.keyCombination("F5"),
                    this::loadChannelGrid);
        } catch (RuntimeException e) {
            Log.w(TAG, "注册快捷键失败: " + e);
        }
        // 关闭窗口 = 收进托盘继续后台播放（商用软件常见做法）。
        // 真要退出走托盘右键「退出」。若系统不支持托盘，则关闭即退出。
        primaryStage.setOnCloseRequest(e -> {
            if (config.closeToTray && trayInstalled && !exiting) {
                e.consume();
                primaryStage.hide();
                tray.notify("央视频直播", "已最小化到托盘，播放不会中断；从托盘右键可退出");
                Log.i(TAG, "主窗口收进托盘");
                return;
            }
            shutdown();
        });

        // 记住窗口尺寸与位置（关闭时统一保存，避免拖动过程中频繁写盘）
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

        // 恢复上次的位置与最大化状态，并确保窗口仍在可见屏幕内
        // （防止拔掉外接显示器后窗口跑到屏幕外找不回来）
        if (config.windowMaximized) {
            primaryStage.setMaximized(true);
        } else if (config.windowX >= 0 && config.windowY >= 0) {
            javafx.geometry.Rectangle2D vis =
                    javafx.stage.Screen.getPrimary().getVisualBounds();
            double x = Math.max(vis.getMinX(),
                    Math.min(config.windowX, vis.getMaxX() - 240));
            double y = Math.max(vis.getMinY(),
                    Math.min(config.windowY, vis.getMaxY() - 160));
            primaryStage.setX(x);
            primaryStage.setY(y);
        }

        primaryStage.show();

        // 位置变更后记录（限制频率：只在值真正变化时赋值，不写盘）
        primaryStage.xProperty().addListener((o, a, b) -> {
            if (!primaryStage.isMaximized() && !primaryStage.isIconified() && b != null) {
                config.windowX = b.doubleValue();
            }
        });
        primaryStage.yProperty().addListener((o, a, b) -> {
            if (!primaryStage.isMaximized() && !primaryStage.isIconified() && b != null) {
                config.windowY = b.doubleValue();
            }
        });
        primaryStage.maximizedProperty().addListener((o, a, b) -> {
            config.windowMaximized = b != null && b;
        });

        chromiumPath = PlayerLauncher.findChromium();
        if (chromiumPath == null) {
            Log.w(TAG, "未检测到 Edge/Chrome，浏览器兜底不可用（不影响内置播放器）");
        }

        // 系统托盘：关闭窗口后继续后台播放，双击托盘图标快速呼出
        tray = new Tray(this::showMainWindow, this::quitFromTray);
        trayInstalled = tray.install();

        // 开机自启的启动期校验（默认关闭，且绝不被程序自动打开）。
        //
        // 场景：用户以前装过旧版并勾选过自启，注册表里留下了条目；或者别的
        // 工具写入了同名项。这种情况下用户会觉得"它怎么自己开机弹出来"。
        // 这里的规则很简单：以本程序的配置开关为准（默认 false），
        // 配置说不要自启、但注册表有条目 -> 主动清掉，把控制权还给用户。
        try {
            boolean regHas = AutoStart.isEnabled();
            if (regHas && !config.autoStartWithWindows) {
                AutoStart.setEnabled(false);
                Log.i(TAG, "检测到注册表残留自启项且配置未开启，已清除");
            }
        } catch (Throwable e) {
            Log.w(TAG, "启动期自启校验失败（忽略）: " + e);
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
        continueButton.setManaged(false);
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
                applyFilter();
            });
            chips.getChildren().add(tb);
        }
        Region chipSpacer = new Region();
        HBox.setHgrow(chipSpacer, Priority.ALWAYS);

        // 搜索框：64 个频道靠滚动找台太慢，这是商用客户端的基本能力。
        // 输入即时过滤（不按回车），清空即恢复当前筛选。
        searchField = new javafx.scene.control.TextField();
        searchField.setPromptText("搜索频道（Ctrl+F）");
        searchField.setPrefWidth(200);
        searchField.getStyleClass().add("search-field");
        searchField.textProperty().addListener((o, a, b) -> applyFilter());
        // Esc 清空搜索
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                searchField.clear();
            }
        });

        chips.getChildren().addAll(chipSpacer, searchField, continueButton);

        VBox top = new VBox(row, chips);
        top.getStyleClass().add("top");
        return top;
    }

    // ------------------------------------------------------------------ 主体

    private Region buildBody() {
        virtualGrid = new VirtualChannelGrid();
        return virtualGrid.node();
    }

    /**
     * 建立频道网格。
     * <p>只做一次：把全部频道作为数据交给虚拟化网格，由它按视口实体化卡片。
     * 场景图里恒定只有约 15-20 张卡片，与频道总数无关。
     */
    private void loadChannelGrid() {
        if (virtualGrid == null) {
            return;
        }
        List<ChannelSpec> all = ChannelTable.all();
        virtualGrid.setItems(all, this::buildChannelCard);
        applyFilter();
    }

    /**
     * 应用筛选：只改数据源，虚拟化网格自己重建可见区。
     * 不重建全部 64 个节点——这是「切换筛选秒响应」的关键。
     */
    private void applyFilter() {
        if (virtualGrid == null) {
            return;
        }
        List<ChannelSpec> visible = config.filteredChannels();

        // 搜索过滤：匹配频道名 / slug / 分组，大小写不敏感。
        // 与筛选 chip 是"与"关系——先按 chip 分组，再按关键词收窄。
        String kw = searchField == null || searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        if (!kw.isEmpty()) {
            List<ChannelSpec> hit = new ArrayList<>();
            for (ChannelSpec s : visible) {
                String name = s.name == null ? "" : s.name.toLowerCase(java.util.Locale.ROOT);
                String slug = s.slug == null ? "" : s.slug.toLowerCase(java.util.Locale.ROOT);
                String group = s.group == null ? "" : s.group.toLowerCase(java.util.Locale.ROOT);
                if (name.contains(kw) || slug.contains(kw) || group.contains(kw)) {
                    hit.add(s);
                }
            }
            visible = hit;
        }

        virtualGrid.setItems(visible, this::buildChannelCard);

        ChannelSpec last = ChannelTable.bySlug(config.lastChannel);
        boolean showContinue = last != null;
        continueButton.setVisible(showContinue);
        continueButton.setManaged(showContinue);
        if (showContinue) {
            continueButton.setText("继续观看 · " + last.name);
        }
        updatePlayingHighlight();
    }

    /** 更新「正在播放」高亮：只遍历可见卡片。 */
    private void updatePlayingHighlight() {
        if (virtualGrid == null) {
            return;
        }
        for (Region card : virtualGrid.visibleCards()) {
            ChannelSpec spec = cardSpec.get(card);
            boolean playing = spec != null && spec.slug.equals(playingSlug);
            boolean has = card.getStyleClass().contains("playing");
            if (playing && !has) {
                card.getStyleClass().add("playing");
            } else if (!playing && has) {
                card.getStyleClass().remove("playing");
            }
        }
    }

    private Region buildChannelCard(ChannelSpec spec) {
        // 台标底衬：央视频台标多为深色/彩色 logo，直接贴深色卡片会糊成一片。
        // 这里给足尺寸与留白，让台标成为视觉焦点（用户反馈「logo 太丑、看不清」）。
        Label logoText = new Label(initialOf(spec.name));
        logoText.getStyleClass().add("logo-fallback");

        StackPane logoInner = new StackPane(logoText);
        logoInner.getStyleClass().add("logo-plate");

        StackPane logoBox = new StackPane(logoInner);
        logoBox.getStyleClass().add("logo-box");
        logoBox.setPrefSize(Double.MAX_VALUE, RenderQuality.logoBoxHeight());
        logoBox.setMinHeight(RenderQuality.logoBoxHeight());
        StackPane.setMargin(logoInner, new Insets(4));

        Image cached = logoCache.get(spec.logo);
        if (cached != null) {
            showLogo(logoBox, logoText, cached);
        } else {
            requestLogo(spec, logoBox, logoText);
        }

        Label name = new Label(spec.name);
        name.getStyleClass().add("channel-name");

        // 画质标注要如实：标称 4K/8K 的频道若实际走 1080p 回退，
        // 不能继续显示「4K」误导用户（设备协议上游 app/start 目前恒返回 400，
        // 真 4K 码流拿不到，详见 QualityState 注释）。
        String actual = com.gary.ysplive.core.data.QualityState.actual(spec.slug);
        boolean degraded = com.gary.ysplive.core.data.QualityState.isDegraded(spec.slug);
        String badgeText;
        if (actual != null) {
            badgeText = actual;
        } else if (ChannelTable.isTrue4k(spec.slug)) {
            badgeText = "4K";
        } else {
            badgeText = spec.defnLabel();
        }
        Label badge = new Label(badgeText);
        badge.getStyleClass().add("badge");
        if (degraded) {
            badge.getStyleClass().add("badge-degraded");
        }

        Label group = new Label(degraded ? "超高清·当前1080p"
                : (ChannelTable.isTrue4k(spec.slug) ? "超高清" : spec.group));
        group.getStyleClass().add("channel-group");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox meta = new HBox(6, badge, spacer, group);
        meta.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, logoBox, name, meta);
        card.getStyleClass().add("channel-card");
        card.setPrefWidth(RenderQuality.cardWidth());
        card.setMaxWidth(RenderQuality.cardWidth());

        if (config.isFavorite(spec.slug)) {
            Label star = new Label("★");
            star.getStyleClass().add("star");
            StackPane.setAlignment(star, Pos.TOP_RIGHT);
            StackPane.setMargin(star, new Insets(4, 6, 0, 0));
            logoBox.getChildren().add(star);
        }

        // hover 完全交给 CSS :hover（不再在 Java 里 add/remove style class）。
        // 旧写法每次 hover 都会触发 CSS 重解析 + relayout，是「几秒才高亮」的根因。
        card.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                boolean added = config.toggleFavorite(spec.slug);
                Log.i(TAG, (added ? "已收藏 " : "已取消收藏 ") + spec.name);
                // 收藏后立刻刷新星标：重建这一张卡片即可，不必整表重建
                refreshCardFavorite(spec);
                if ("收藏".equals(config.filter)) {
                    applyFilter();
                }
                return;
            }
            openPlayer(spec);
        });

        Tooltip tip = new Tooltip(spec.name + "\n" + spec.defnLabel() + " · " + spec.group
                + "\n单击播放，右键收藏/取消收藏");
        tip.setShowDelay(javafx.util.Duration.millis(600));
        Tooltip.install(card, tip);

        // 卡片内容是静态的（台标+文字），滚动只是平移。
        // 缓存成位图后滚动时直接 blit，不必每帧重绘文字/边框/圆角。
        // 实测这一项把滚动帧率从 22.6 提到 33.6 FPS（SW 管线）。
        card.setCache(true);
        card.setCacheHint(javafx.scene.CacheHint.SPEED);

        return card;
    }

    /** 收藏状态变化后重建可见卡片（虚拟化下只会动十几张，代价很小）。 */
    private void refreshCardFavorite(ChannelSpec spec) {
        if (virtualGrid == null) {
            return;
        }
        virtualGrid.rebuild();
        updatePlayingHighlight();
        updatePlayingHighlight();
    }

    private static String initialOf(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        String t = name.trim();
        // 去掉 "CCTV-" 之类前缀，让回退文字更短更清楚
        for (String p : new String[]{"CCTV-", "CCTV", "CGTN "}) {
            if (t.startsWith(p) && t.length() > p.length()) {
                t = t.substring(p.length());
                break;
            }
        }
        if (t.length() >= 2 && t.charAt(0) < 0x80 && t.charAt(1) < 0x80) {
            return t.substring(0, 2).toUpperCase(java.util.Locale.ROOT);
        }
        return t.substring(0, Math.min(2, t.length()));
    }

    private void showLogo(StackPane box, Label fallback, Image img) {
        if (img == null || img.isError()) {
            return;
        }
        ImageView iv = new ImageView(img);
        iv.setFitWidth(80);
        iv.setFitHeight(56);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        iv.setCache(true);
        iv.setCacheHint(javafx.scene.CacheHint.QUALITY);

        StackPane plate = null;
        if (!box.getChildren().isEmpty() && box.getChildren().get(0) instanceof StackPane) {
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

    /**
     * 请求加载一张台标。
     * <p>走固定大小的线程池而不是「每张卡片一个 Thread」：
     * 64 个频道同时开 64 个线程去抢网络，会互相拖慢，反而让界面更卡。
     */
    private void requestLogo(ChannelSpec spec, StackPane box, Label fallback) {
        final String url = spec.logo;
        if (url == null || url.isEmpty()) {
            return;
        }
        logoPool.submit(() -> {
            Image img = null;
            try {
                // 注意最后一个参数 backgroundLoading 必须为 false：
                // 这里已经在后台线程上，需要的是"加载完再返回"。
                // 若传 true，构造函数会立刻返回一个宽高为 0 的空 Image，
                // 随后被判为失败、退化成文字占位（曾踩过这个坑）。
                img = new Image(url, 160, 112, true, true, false);
                if (img.isError() || img.getWidth() <= 0) {
                    img = null;
                }
            } catch (RuntimeException e) {
                img = null;
            }
            final Image got = img;
            if (got != null) {
                logoCache.put(url, got);
            }
            Platform.runLater(() -> {
                if (got != null) {
                    showLogo(box, fallback, got);
                } else {
                    // 加载失败：保留文字占位（已设置高对比度样式）
                    fallback.setVisible(true);
                }
            });
        });
    }

    // ------------------------------------------------------------------ 引擎

    private void startEngineAndPlayerHost() {
        setStatus("正在启动引擎…", "");

        // 浏览器兜底用的播放页宿主（内置 VLC 可用时不依赖它，启动很快）
        try {
            String html = PlayerLauncher.readResourceText("/web/player.html");
            String hls = PlayerLauncher.readResourceText("/web/hls.min.js");
            webServer = LocalWebServer.start(html, hls);
            Log.i(TAG, "播放页服务: " + webServer.baseUrl());
        } catch (Exception e) {
            Log.e(TAG, "播放页服务启动失败：" + e);
        }

        Task<Void> starter = new Task<Void>() {
            @Override
            protected Void call() {
                long t0 = System.currentTimeMillis();
                engine.startAsync(stageText -> Platform.runLater(() -> setStatus(stageText, "")));
                while (System.currentTimeMillis() - t0 < 95_000L) {
                    if (engine.isRunning()) {
                        Gateway.Snapshot snap = engine.snapshot();
                        final String origin = snap == null ? "" : snap.localOrigin;
                        final int port = snap == null ? 0 : snap.port;
                        final boolean high = snap != null && snap.highBitrateReady;
                        Platform.runLater(() -> setStatus("引擎已就绪", "端口 " + port + " · "
                                + ChannelTable.all().size() + " 个频道 · "
                                + (high ? "高码率已就绪" : "1080p 模式")));
                        return null;
                    }
                    if (!engine.lastError().isEmpty() && !engine.isRunning()) {
                        final String err = engine.lastError();
                        Platform.runLater(() -> setStatus("引擎启动失败", err));
                        return null;
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                Platform.runLater(() -> setStatus("引擎启动超时", "可到「诊断」查看日志"));
                return null;
            }
        };
        Thread t = new Thread(starter, "engine-watch");
        t.setDaemon(true);
        t.start();
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
        playingSlug = spec.slug;
        setStatus("正在播放 " + spec.name, spec.defnLabel() + " · " + spec.group);
        updatePlayingHighlight();

        // 拉一次播放列表，让引擎回报这个频道实际拿到的画质，
        // 然后把该卡片的标注刷新成如实值（标称 4K 但走 1080p 时要显示 1080p）。
        refreshQualityLater(spec);

        // 优先用内置 libVLC（真正自带播放器，无外部依赖）
        if (VlcPlayer.isAvailable()) {
            try {
                if (vlcWindow != null) {
                    vlcWindow.release();
                    vlcWindow = null;
                }
                // 传入全部频道，播放窗口自带侧边换台栏
                List<ChannelSpec> all = ChannelTable.all();
                vlcWindow = VlcPlayer.open(streamUrl, spec, all, config, reason -> {
                    // 播放窗口里换台：更新主界面高亮与"继续观看"
                    Platform.runLater(() -> {
                        playingSlug = reason.slug;
                        config.lastChannel = reason.slug;
                        config.save();
                        updatePlayingHighlight();
                        applyFilter();
                        setStatus("正在播放 " + reason.name,
                                reason.defnLabel() + " · " + reason.group);
                        // 用户在播放器里换台时，主窗口保持隐藏
                    });
                }, () -> Platform.runLater(this::showMainWindow));
                Log.i(TAG, "内置播放器播放 " + spec.name + " -> " + streamUrl);

                // 用户要求：点击进台后隐藏原来的选台页面
                hideMainWindow();
                return;
            } catch (Throwable e) {
                Log.e(TAG, "内置播放器失败，回退浏览器方案：" + e);
            }
        } else {
            Log.w(TAG, "未找到内置 libVLC，回退浏览器方案");
        }

        // 兜底：系统 Chromium + hls.js
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
            hideMainWindow();
        } catch (Exception e) {
            Log.e(TAG, "打开播放窗口失败：" + e);
            dialogs().showError("无法打开播放窗口", String.valueOf(e.getMessage()));
        }
    }

    /** 进入播放时隐藏选台窗口（不销毁，播完可秒回）。 */
    private void hideMainWindow() {
        if (stage != null && stage.isShowing()) {
            stage.hide();
            Log.i(TAG, "选台窗口已隐藏");
        }
    }

    /** 从播放器返回选台界面。 */
    private void showMainWindow() {
        if (stage == null) {
            return;
        }
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
        Log.i(TAG, "返回选台窗口");
    }

    /**
     * 拉一次播放列表让引擎回报实际画质，然后在 UI 上刷新该卡片标注。
     *
     * <p>为什么必须这么做：频道表里 cctv4k/cctv8k/cctv164k 标称 4K/8K，
     * 但设备协议上游 app/start 目前恒返回 400，实际只能走 bk 1080p 通道
     * （用原作者 Python 版验证也是同样结果，属上游变更）。
     * 不刷新就会出现「界面写 4K、实际放 1080p」的误导。
     */
    private void refreshQualityLater(ChannelSpec spec) {
        Thread t = new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(engine.localOrigin() + "/" + spec.slug + ".m3u8")
                                .openConnection(java.net.Proxy.NO_PROXY);
                c.setConnectTimeout(8000);
                c.setReadTimeout(20000);
                int code = c.getResponseCode();
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    while (in.read(buf) > 0) {
                        // 读完即可，触发引擎回报 QualityState
                    }
                }
                if (code != 200) {
                    return;
                }
                Thread.sleep(600);
                if (com.gary.ysplive.core.data.QualityState.actual(spec.slug) == null) {
                    return;
                }
                Platform.runLater(() -> {
                    // 卡片标注变了，重建可见区（虚拟化下只有十几张，代价很小）
                    virtualGrid.rebuild();
                    updatePlayingHighlight();
                    String actual = com.gary.ysplive.core.data.QualityState.actual(spec.slug);
                    if (com.gary.ysplive.core.data.QualityState.isDegraded(spec.slug)) {
                        setStatus("正在播放 " + spec.name,
                                "标称 4K/8K，当前实际 " + actual + "（高码率通道暂不可用）");
                    }
                });
            } catch (Throwable ignored) {
                // 画质探测失败不影响播放
            }
        }, "quality-" + spec.slug);
        t.setDaemon(true);
        t.start();
    }

    /** 托盘菜单「退出」：真正结束进程。 */
    private void quitFromTray() {
        exiting = true;
        Log.i(TAG, "用户从托盘退出");
        shutdown();
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
        exiting = true;
        config.save();
        if (tray != null) {
            tray.remove();
            tray = null;
        }
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
        if (logoPool != null) {
            logoPool.shutdownNow();
        }
        LogFile.flush();
        SingleInstance.release();
        Platform.exit();
    }

    /** 仅供 PerfBench 调用：切换筛选并立即同步布局，用于测量真实开销。 */
    void benchApplyFilter(String filter) {
        config.filter = filter;
        applyFilter();
        // 强制同步布局，把开销算进本次测量（否则只是入队，测不出真实成本）
        if (virtualGrid != null && virtualGrid.node().getScene() != null) {
            virtualGrid.node().applyCss();
            virtualGrid.node().layout();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
