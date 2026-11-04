package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.spi.Log;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

/**
 * 内嵌播放器（libVLC）—— 仿成熟播放器（PotPlayer / VLC / mpv）的交互。
 *
 * <h2>交互设计</h2>
 * <ul>
 *   <li><b>侧边换台栏</b>：左侧可滑出的频道列表，点击直接换台。
 *       按 {@code Tab} 或把鼠标移到左边缘呼出，{@code Esc} 收起。</li>
 *   <li><b>底部控制条</b>：播放/暂停、音量大/小/静音、全屏、换台栏，鼠标静止 3 秒自动隐藏。</li>
 *   <li><b>快捷键</b>：空格暂停、↑↓ / PgUp PgDn 换台、←→ 快退快进、
 *       +/- 音量、M 静音、F 或双击全屏、Esc 退出全屏/返回选台。</li>
 *   <li><b>OSD</b>：换台、调音量时在画面中央短暂显示提示。</li>
 * </ul>
 *
 * <p>为什么用 Swing 而不是 JavaFX：libVLC 需要一块原生窗口表面（AWT Canvas）
 * 才能硬件渲染视频。JavaFX 的 SwingNode 里放 Canvas 会多一层合成，实测掉帧。
 * 直接用 Swing 承载 Canvas 是最短路径，视频是真正的 GPU 直出。
 */
final class VlcPlayer {

    private static final String TAG = "vlc";

    /** 换台回调：用户在选择器里点了另一个频道。 */
    interface ChannelSwitch {
        void onSwitch(ChannelSpec spec);
    }

    /** 返回主界面回调（关闭播放窗口时）。 */
    interface OnExit {
        void onExit();
    }

    /** 播放窗口。 */
    static final class Window {
        final JFrame frame;
        final EmbeddedMediaPlayer player;
        final MediaPlayerFactory factory;
        private volatile boolean released;
        /** 自动隐藏控制条的定时器（截图/自动化测试时可停掉）。 */
        Timer hideTimer;

        Window(JFrame frame, EmbeddedMediaPlayer player, MediaPlayerFactory factory) {
            this.frame = frame;
            this.player = player;
            this.factory = factory;
        }

        /** 全局鼠标监听器，释放时必须注销，否则会泄漏并拖慢事件分发。 */
        java.awt.event.AWTEventListener globalMouseListener;
        /** 当前播放的频道 slug（用于保存播放进度）。 */
        volatile String currentSlug;
        /** 配置引用，供 release() 时保存窗口几何。 */
        DesktopConfig config;

        /** 供截图与自动化测试使用：停掉自动隐藏并强制显示控制条。 */
        void pinControls() {
            if (hideTimer != null) {
                hideTimer.stop();
            }
            java.awt.Container root = frame.getContentPane();
            for (java.awt.Component c : root.getComponents()) {
                String n = c.getClass().getSimpleName();
                if (n.equals("ControlBar") || n.equals("SidePanel")) {
                    c.setVisible(true);
                }
            }
            root.doLayout();
            root.validate();
            root.repaint();
        }

        boolean isShowing() {
            return !released && frame.isDisplayable() && frame.isVisible();
        }

        void release() {
            if (released) {
                return;
            }
            released = true;
            // 必须在 dispose 之前保存：dispose 之后 isShowing() 变成 false，
            // 窗口几何就读不到了（曾因此导致位置记忆失效）。
            savePlayerState(this, config);
            try {
                player.controls().stop();
            } catch (Throwable ignored) {
                // 已停止
            }
            try {
                player.release();
            } catch (Throwable ignored) {
                // 已释放
            }
            try {
                factory.release();
            } catch (Throwable ignored) {
                // 已释放
            }
            // 注销全局监听，避免窗口关闭后仍持有引用
            if (globalMouseListener != null) {
                try {
                    java.awt.Toolkit.getDefaultToolkit()
                            .removeAWTEventListener(globalMouseListener);
                } catch (Throwable ignored) {
                    // 忽略
                }
                globalMouseListener = null;
            }
            if (hideTimer != null) {
                hideTimer.stop();
            }
            SwingUtilities.invokeLater(frame::dispose);
        }
    }

    private VlcPlayer() {
    }

    static boolean isAvailable() {
        File dir = libVlcDir();
        if (dir == null) {
            return false;
        }
        return new File(dir, "libvlc.dll").isFile();
    }

    static File libVlcDir() {
        String override = System.getProperty("ysp.vlc.dir");
        if (override != null && !override.trim().isEmpty()) {
            File f = new File(override);
            if (f.isDirectory()) {
                return f;
            }
        }
        String[] candidates = {"vlc", "../vlc", "../../vlc"};
        File base = appBaseDir();
        for (String c : candidates) {
            File f = new File(base, c);
            if (new File(f, "libvlc.dll").isFile()) {
                return f;
            }
        }
        File dev = new File("C:\\1\\ysp-android\\_vlc\\minimal");
        if (new File(dev, "libvlc.dll").isFile()) {
            return dev;
        }
        return null;
    }

    private static File appBaseDir() {
        try {
            String path = VlcPlayer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            File f = new File(path);
            if (f.isFile()) {
                return f.getParentFile();
            }
            return f;
        } catch (Throwable t) {
            return new File(".");
        }
    }

    // ------------------------------------------------------------------ 打开

    /**
     * 打开播放窗口。
     *
     * @param mrl        初始流地址
     * @param initial    初始频道
     * @param channels   全部频道（供侧边栏换台）
     * @param config     配置（记住音量等）
     * @param onSwitch   换台回调
     * @param onExit     关闭回主界面回调
     */
    static Window open(String mrl, ChannelSpec initial, List<ChannelSpec> channels,
                       DesktopConfig config, ChannelSwitch onSwitch, OnExit onExit) throws Exception {
        File vlcDir = libVlcDir();
        if (vlcDir == null) {
            throw new IllegalStateException("找不到内置 libVLC 目录");
        }
        System.setProperty("jna.library.path", vlcDir.getAbsolutePath());

        MediaPlayerFactory factory = new MediaPlayerFactory(
                "--no-video-title-show",
                "--quiet",
                "--no-osd",
                "--no-snapshot-preview",
                "--avcodec-hw=dxva2",
                // 显式给出音频输出模块优先级。默认 libVLC 会自己挑，
                // 但在某些精简版 Windows / 多声卡机器上会挑错甚至挑不到，
                // 表现为「有画面没声音」。这三项覆盖了 Windows 上可用的全部
                // 音频后端（mmdevice=WASAPI 首选，directsound、waveout 兜底）。
                "--aout=mmdevice,directsound,waveout");

        final EmbeddedMediaPlayer player = factory.mediaPlayers().newEmbeddedMediaPlayer();
        // 注意：这里的 setVolume 只是"先记下"，真正生效要在 playing 之后，
        // 见 applyAudioSettings()。
        player.audio().setVolume(Math.max(0, Math.min(130, config.playerVolume)));

        final JFrame frame = new JFrame("央视频直播 v" + App.VERSION + " · " + initial.name);
        frame.setUndecorated(false);
        frame.setBackground(Color.BLACK);

        final Canvas canvas = new Canvas();
        canvas.setBackground(Color.BLACK);

        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas));

        final Window window = new Window(frame, player, factory);
        window.currentSlug = initial.slug;
        window.config = config;

        // ---------------- 界面组装 ----------------
        //
        // ★ 关键约束：libVLC 的视频表面是 java.awt.Canvas —— **重量级组件**。
        // AWT 里重量级组件永远绘制在轻量级 Swing 组件**之上**，与 z-order 无关。
        // 所以「控制条/侧栏浮在视频上层」在 AWT 下不可能实现：
        // 之前用 null 布局把侧栏和控制条叠在 Canvas 上，结果它们全被视频盖住，
        // 用户看到的就是「只有画面，没有任何控件和换台入口」。
        //
        // 唯一正确的做法：让它们**互不重叠**，用 BorderLayout 停靠。
        // 这也是 VLC / PotPlayer 播放列表的实际做法（停靠面板，不是覆盖层）。
        final JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(Color.BLACK);
        root.setOpaque(true);

        // 视频区（画布）
        final Osd osd = new Osd();

        // 视频区：用 BorderLayout 让 Canvas 占满中心，
        // AWT 自己管理尺寸，比 null 布局更可靠。
        final JPanel videoLayer = new JPanel(new BorderLayout());
        videoLayer.setBackground(Color.BLACK);
        videoLayer.add(canvas, BorderLayout.CENTER);

        // 注意：OSD 是独立的 JWindow（重量级），不能放在 videoLayer 里，
        // 否则会被 Canvas 盖住（同为轻量级/重量级混用的老问题）。
        osd.attachTo(frame);

        // ---------------- 断流自动恢复的状态 ----------------
        // 提前声明：换台逻辑（侧边栏 lambda）与事件回调都要用到。
        //
        // 为什么要做重连：之前的实现只在 OSD 上写「正在重试…」但没有真的重试，
        // 网络抖动一次就永久黑屏——这是商用播放器不能接受的行为。
        // 这里按 1s/2s/4s/8s 退避重连，成功则复位计数，连续失败 5 次才提示用户。
        final java.util.concurrent.atomic.AtomicInteger retryCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicReference<String> currentMrl =
                new java.util.concurrent.atomic.AtomicReference<>(mrl);
        final java.util.concurrent.atomic.AtomicBoolean recovering =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        // 侧边换台栏
        // 用数组持有自身引用，避免 lambda 里引用"尚未初始化"的局部变量
        final SidePanel[] sideRef = new SidePanel[1];
        // 换台需要知道播放器与流地址前缀，这里用数组在稍后赋值，
        // 因为 SidePanel 构造时 player 已存在但 currentMrl 在后面才声明。
        final java.util.concurrent.atomic.AtomicReference<String> switchPrefixRef =
                new java.util.concurrent.atomic.AtomicReference<>("");
        // 先推导流地址前缀（形如 http://127.0.0.1:8767），
        // 侧栏的「复制链接」与换台逻辑都要用到，必须在使用前就绪。
        try {
            int cut = mrl.lastIndexOf('/');
            if (cut > 0) {
                switchPrefixRef.set(mrl.substring(0, cut));
            }
        } catch (RuntimeException ignored) {
            // 推导失败则换台只更新界面（降级但不崩）
        }

        final SidePanel side = new SidePanel(channels, initial, spec -> {
            if (onSwitch != null) {
                onSwitch.onSwitch(spec);
            }
            frame.setTitle("央视频直播 v" + App.VERSION + " · " + spec.name);
            window.currentSlug = spec.slug;
            if (sideRef[0] != null) {
                sideRef[0].setActive(spec.slug);
            }
            // 真正切流：之前的实现只改了标题和选中态，视频根本没换。
            String prefix = switchPrefixRef.get();
            if (!prefix.isEmpty()) {
                String newMrl = prefix + "/" + spec.slug + ".m3u8";
                Log.i(TAG, "换台 -> " + spec.name + " : " + newMrl);
                currentMrl.set(newMrl);
                // 换台时重置重连计数，避免沿用上个频道的失败次数
                retryCount.set(0);
                player.media().play(newMrl);
            }
            osd.show("换台  " + spec.name);
        }, config, spec -> {
            // 供「复制播放链接」使用：优先拼出可直接播放的本地地址
            String p = switchPrefixRef.get();
            String url = p.isEmpty() ? (spec.slug + ".m3u8") : (p + "/" + spec.slug + ".m3u8");
            return url;
        });
        sideRef[0] = side;

        // 底部控制条
        final ControlBar bar = new ControlBar(player, config,
                () -> {
                    // 换台栏开关
                    side.toggle();
                    root.revalidate();
                    root.repaint();
                },
                () -> toggleFullScreen(frame),
                () -> {
                    // 返回选台
                    window.release();
                    if (onExit != null) {
                        onExit.onExit();
                    }
                });

        // 停靠布局：侧栏在左、控制条在下、视频占中间，三者互不重叠。
        // 这是 AWT 下唯一能保证控件可见的排布方式（Canvas 是重量级，
        // 任何叠在它上面的轻量组件都会被覆盖）。
        root.add(side, BorderLayout.WEST);
        root.add(videoLayer, BorderLayout.CENTER);
        root.add(bar, BorderLayout.SOUTH);

        frame.setContentPane(root);

        // ---------------- 事件 ----------------

        player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mp) {
                retryCount.set(0);
                recovering.set(false);
                Log.i(TAG, "开始播放: " + initial.name);
                SwingUtilities.invokeLater(() -> bar.setBuffering(-1));
                // ★ 音频初始化必须放在"真正开始播放"之后。
                // libVLC 在 play() 之前调用 setVolume() 时，音频输出（aout）
                // 还没建立，音量/静音设置会被丢弃，表现为"有画面没声音"。
                // 这里在 playing 事件里再强制设置一次，并显式取消静音。
                applyAudioSettings(player, config, bar);
            }

            @Override
            public void error(MediaPlayer mp) {
                Log.w(TAG, "播放出错");
                scheduleReconnect();
            }

            @Override
            public void finished(MediaPlayer mp) {
                // 直播流不应"结束"，出现说明上游断开，同样重连
                Log.w(TAG, "播放意外结束，准备重连");
                scheduleReconnect();
            }

            /** 退避重连。用单独线程 sleep，避免占用 VLC 回调线程。 */
            private void scheduleReconnect() {
                if (!recovering.compareAndSet(false, true)) {
                    return;   // 已有重连在排队，避免叠加
                }
                final int attempt = retryCount.incrementAndGet();
                SwingUtilities.invokeLater(() -> {
                    osd.show(attempt <= 4 ? "连接中断，正在重连 (" + attempt + "/5)…"
                            : "重连失败，请按 Esc 返回后重试");
                    bar.setBuffering(-1);
                });
                Thread t = new Thread(() -> {
                    try {
                        // 1s, 2s, 4s, 8s, 8s
                        long delayMs = Math.min(8000L, 1000L << Math.min(3, attempt - 1));
                        Thread.sleep(delayMs);
                        if (attempt > 5) {
                            Log.w(TAG, "重连次数超限，停止");
                            recovering.set(false);
                            return;
                        }
                        Log.i(TAG, "第 " + attempt + " 次重连: " + currentMrl.get());
                        player.media().play(currentMrl.get());
                    } catch (Throwable e) {
                        Log.w(TAG, "重连异常: " + e);
                    } finally {
                        // 留出时间让 playing/error 回调更新状态
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        if (player.status().isPlaying()) {
                            retryCount.set(0);
                        }
                        recovering.set(false);
                    }
                }, "vlc-reconnect");
                t.setDaemon(true);
                t.start();
            }

            @Override
            public void buffering(MediaPlayer mp, float newCache) {
                // 直播缓冲很常见，只在首次较大缓冲时提示，避免闪烁
                if (newCache > 0 && newCache < 100) {
                    SwingUtilities.invokeLater(() -> bar.setBuffering(newCache));
                } else if (newCache >= 100) {
                    SwingUtilities.invokeLater(() -> bar.setBuffering(-1));
                }
            }
        });

        // 键盘快捷键：装在 root 上，避免焦点问题
        installKeyBindings(root, player, config, bar, side, videoLayer, window, frame, onExit, osd);

        // ---------------- 控制条 / 侧栏的显隐 ----------------
        //
        // 重要教训（用户实测反馈"打开播放看不到暂停、换台列表"）：
        //   libVLC 的视频表面是 java.awt.Canvas —— 一个**重量级（heavyweight）**
        //   组件，带自己的 native peer。鼠标在它上面移动时，事件直接由 native
        //   peer 接收，**不会冒泡到 Swing 父容器**。
        //   所以把 mouseMoved 装在 root / videoLayer 上根本收不到，
        //   控制条一旦自动隐藏就再也出不来。
        //
        // 现在的策略：
        //   1) 默认**不自动隐藏**（config.playerAutoHideControls = false）。
        //      画面底部常驻控制条，用户任何时候都能看到暂停/停止/换台入口。
        //   2) 用户可在设置里开启"自动隐藏"，开启后用**全局 AWT 事件监听**
        //      （Toolkit.addAWTEventListener）捕获鼠标移动 —— 这个通道
        //      不受重量级组件影响，能可靠地把控制条唤回来。
        //   3) 另外在 Canvas 上直接装一份监听，双保险。
        final boolean autoHide = config.playerAutoHideControls;

        final Timer[] hideRef = new Timer[1];
        final Timer hideTimer = new Timer(2800, e -> {
            if (!autoHide) {
                return;
            }
            // 鼠标在控制条或侧栏范围内 -> 不隐藏
            java.awt.Point mp = java.awt.MouseInfo.getPointerInfo() == null
                    ? null : java.awt.MouseInfo.getPointerInfo().getLocation();
            if (mp != null) {
                java.awt.Point inBar = new java.awt.Point(mp);
                javax.swing.SwingUtilities.convertPointFromScreen(inBar, bar);
                if (bar.isShowing() && bar.contains(inBar)) {
                    hideRef[0].restart();
                    return;
                }
                if (side.isOpen()) {
                    java.awt.Point inSide = new java.awt.Point(mp);
                    javax.swing.SwingUtilities.convertPointFromScreen(inSide, side);
                    if (side.isShowing() && side.contains(inSide)) {
                        hideRef[0].restart();
                        return;
                    }
                }
            }
            bar.setVisible(false);
            setCursorVisible(frame, true);
            hideRef[0].stop();
        });
        hideRef[0] = hideTimer;
        window.hideTimer = hideTimer;
        hideTimer.setRepeats(false);

        // 把控制条唤回来的统一动作（多处复用）
        final Runnable showControls = () -> {
            if (!bar.isVisible()) {
                bar.setVisible(true);
                root.revalidate();
                root.repaint();
            }
            setCursorVisible(frame, true);
            hideTimer.restart();
        };

        // 通道 1：Canvas 自己（重量级组件的 native peer 直接接收事件）
        canvas.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                showControls.run();
            }
        });
        // 通道 2：root 与控件（覆盖侧栏、控制条等轻量区域）
        root.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                showControls.run();
            }
        });
        bar.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                showControls.run();
            }
        });
        side.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                showControls.run();
            }
        });

        // 通道 3（关键）：全局 AWT 事件监听。
        // 上面两条通道在鼠标压到重量级 Canvas 上时都可能收不到，
        // 这个通道监听整个应用的事件队列，不受轻重组件影响，是兜底手段。
        final java.awt.event.AWTEventListener globalMouse =
                event -> {
                    if (!(event instanceof MouseEvent)) {
                        return;
                    }
                    MouseEvent me = (MouseEvent) event;
                    if (me.getID() != MouseEvent.MOUSE_MOVED
                            && me.getID() != MouseEvent.MOUSE_DRAGGED) {
                        return;
                    }
                    // 只关心发生在播放窗口内的移动
                    java.awt.Component src = me.getComponent();
                    if (src == null || !javax.swing.SwingUtilities
                            .isDescendingFrom(src, frame)) {
                        return;
                    }
                    javax.swing.SwingUtilities.invokeLater(showControls);
                };
        try {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
                    globalMouse, java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK);
            window.globalMouseListener = globalMouse;
        } catch (Throwable e) {
            Log.w(TAG, "注册全局鼠标监听失败（不影响其它唤出方式）: " + e);
        }
        // 视频区域的点击：单击播放/暂停，双击全屏
        videoLayer.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    toggleFullScreen(frame);
                    return;
                }
                player.controls().setPause(player.status().isPlaying());
                osd.show(player.status().isPlaying() ? "▶  播放" : "⏸  暂停");
            }
        });

        SwingUtilities.invokeLater(() -> {
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    savePlayerState(window, config);
                    window.release();
                    if (onExit != null) {
                        onExit.onExit();
                    }
                }

                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    // 关闭前先记住位置/进度，避免 dispose 之后读不到
                    savePlayerState(window, config);
                }
            });
            if (config.fullScreenPlayer) {
                frame.setUndecorated(true);
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            } else {
                // 完整位置记忆：恢复上次播放器窗口的位置与大小；
                // 首次运行或数据不合法时退回居中 1280x760。
                int w = config.playerWinWidth > 320 ? config.playerWinWidth : 1280;
                int h = config.playerWinHeight > 240 ? config.playerWinHeight : 760;
                frame.setSize(w, h);
                if (config.playerWinX >= 0 && config.playerWinY >= 0
                        && isOnScreen(config.playerWinX, config.playerWinY, w, h)) {
                    frame.setLocation(config.playerWinX, config.playerWinY);
                } else {
                    frame.setLocationRelativeTo(null);
                }
            }
            frame.setVisible(true);
            canvas.requestFocusInWindow();
            // 首帧强制布局一次，确保浮层位置正确（null 布局不会自动排）
            root.validate();
            root.repaint();
            // 只有用户显式开启「自动隐藏」时才启动计时器。
            // 默认常驻显示，保证任何时候都能找到暂停/停止/换台入口。
            if (autoHide) {
                hideTimer.start();
            } else {
                bar.setVisible(true);
                root.validate();
                root.repaint();
            }
        });

        Thread.sleep(350);
        player.media().play(mrl);
        Log.i(TAG, "播放 " + initial.name + " -> " + mrl);
        osd.show(initial.name);
        return window;
    }

    // ------------------------------------------------------------------ 快捷键

    private static void installKeyBindings(JComponent root, EmbeddedMediaPlayer player,
                                           DesktopConfig config, ControlBar bar, SidePanel side,
                                           JPanel videoLayer, Window window, JFrame frame,
                                           OnExit onExit, Osd osd) {
        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();

        bind(im, am, KeyEvent.VK_SPACE, "togglePause", () -> {
            player.controls().setPause(player.status().isPlaying());
            osd.show(player.status().isPlaying() ? "▶  播放" : "⏸  暂停");
        });

        bind(im, am, KeyEvent.VK_M, "mute", () -> {
            player.audio().setMute(!player.audio().isMute());
            osd.show(player.audio().isMute() ? "🔇  静音" : "🔊  取消静音");
        });

        bind(im, am, KeyEvent.VK_F, "fullscreen", () -> toggleFullScreen(frame));

        bind(im, am, KeyEvent.VK_TAB, "sidePanel", () -> side.toggle());

        bind(im, am, KeyEvent.VK_UP, "volUp", () -> {
            int v = Math.min(130, player.audio().volume() + 5);
            player.audio().setVolume(v);
            config.playerVolume = v;
            osd.show("音量  " + v + "%");
        });
        bind(im, am, KeyEvent.VK_DOWN, "volDown", () -> {
            int v = Math.max(0, player.audio().volume() - 5);
            player.audio().setVolume(v);
            config.playerVolume = v;
            osd.show("音量  " + v + "%");
        });

        bind(im, am, KeyEvent.VK_EQUALS, "volUp2", () -> {
            int v = Math.min(130, player.audio().volume() + 5);
            player.audio().setVolume(v);
            osd.show("音量  " + v + "%");
        });
        bind(im, am, KeyEvent.VK_MINUS, "volDown2", () -> {
            int v = Math.max(0, player.audio().volume() - 5);
            player.audio().setVolume(v);
            osd.show("音量  " + v + "%");
        });

        bind(im, am, KeyEvent.VK_RIGHT, "ff", () -> {
            player.controls().skipTime(10_000);
            osd.show("»  快进 10 秒");
        });
        bind(im, am, KeyEvent.VK_LEFT, "rew", () -> {
            player.controls().skipTime(-10_000);
            osd.show("«  快退 10 秒");
        });

        bind(im, am, KeyEvent.VK_PAGE_DOWN, "nextCh", () -> side.next());
        bind(im, am, KeyEvent.VK_PAGE_UP, "prevCh", () -> side.prev());

        bind(im, am, KeyEvent.VK_ESCAPE, "escape", () -> {
            if (frame.isUndecorated()) {
                toggleFullScreen(frame);
            } else if (side.isOpen()) {
                side.toggle();
            } else {
                window.release();
                if (onExit != null) {
                    onExit.onExit();
                }
            }
        });
    }

    private static void bind(InputMap im, ActionMap am, int key, String name, Runnable action) {
        im.put(KeyStroke.getKeyStroke(key, 0), name);
        am.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    action.run();
                } catch (Throwable t) {
                    Log.w(TAG, "快捷键 " + name + " 失败: " + t);
                }
            }
        });
    }

    /**
     * 全屏时自动隐藏鼠标指针（仿成熟播放器：鼠标静止后指针消失，一动就出现）。
     * 用透明光标实现，不需要改组件层级。
     */
    /**
     * 保存播放器状态（完整位置记忆）：
     * 窗口位置与大小、当前频道、音量、以及该频道的播放进度。
     */
    private static void savePlayerState(Window window, DesktopConfig config) {
        if (window == null || config == null || window.frame == null) {
            return;
        }
        try {
            JFrame f = window.frame;
            // 注意：这里不能用 f.isShowing() 判断——
            // 程序化 release() 时先保存再 dispose，此时窗口可能已不可见。
            // 只要窗口还被显示过（isDisplayable）并且不是全屏，就去读 bounds。
            if (!f.isUndecorated() && f.isDisplayable()) {
                int state = f.getExtendedState();
                if ((state & JFrame.MAXIMIZED_BOTH) == 0) {
                    java.awt.Rectangle b = f.getBounds();
                    if (b.width > 320 && b.height > 240) {
                        config.playerWinX = b.x;
                        config.playerWinY = b.y;
                        config.playerWinWidth = b.width;
                        config.playerWinHeight = b.height;
                    }
                }
            }
            // 音量
            try {
                int v = window.player.audio().volume();
                if (v > 0) {
                    config.playerVolume = v;
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            // 当前频道的播放进度（仅对可 seek 的流有意义）
            try {
                long len = window.player.status().length();
                long time = window.player.status().time();
                String slug = window.currentSlug;
                if (slug != null && !slug.isEmpty() && len > 0 && time > 0) {
                    config.playPositions.put(slug, time);
                    // 只保留最近 200 条，防止配置文件无限增长
                    while (config.playPositions.size() > 200) {
                        java.util.Iterator<String> it =
                                config.playPositions.keySet().iterator();
                        if (it.hasNext()) {
                            it.next();
                            it.remove();
                        } else {
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            config.save();
            Log.i(TAG, "已保存播放器状态");
        } catch (Throwable t) {
            Log.w(TAG, "保存播放器状态失败: " + t);
        }
    }

    /** 判断给定位置是否落在某个屏幕内（防止拔掉外接屏后窗口跑到看不见的地方）。 */
    private static boolean isOnScreen(int x, int y, int w, int h) {
        try {
            java.awt.Rectangle target = new java.awt.Rectangle(x, y, w, h);
            for (java.awt.GraphicsDevice gd : java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getScreenDevices()) {
                java.awt.Rectangle bounds = gd.getDefaultConfiguration().getBounds();
                if (bounds.intersects(target)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 探测失败就当不在屏内，走居中
        }
        return false;
    }

    /**
     * 应用音频设置（音量 + 取消静音）。
     *
     * <p>必须在 {@code playing} 事件之后调用：libVLC 在媒体真正开始播放前
     * 音频输出（aout）尚未建立，此时 {@code setVolume()} 会被丢弃，
     * 用户看到的就是「有画面、音量显示正常、但没有声音」。
     * 这是 libVLC 集成的经典坑，不是本项目的独创问题。
     */
    private static void applyAudioSettings(EmbeddedMediaPlayer player,
                                           DesktopConfig config, ControlBar bar) {
        final int want = config == null ? 100 : Math.max(0, Math.min(130, config.playerVolume));
        // 在后台线程里重试，不能阻塞 VLC 回调线程。
        Thread t = new Thread(() -> {
            for (int i = 0; i < 40; i++) {
                try {
                    player.audio().setMute(false);
                    player.audio().setVolume(want);
                    Thread.sleep(400);
                    int now = player.audio().volume();
                    int tracks = player.audio().trackCount();
                    if (i == 0 || now >= 0) {
                        Log.i(TAG, "音频初始化尝试 " + (i + 1)
                                + ": 目标=" + want + " 实际=" + now
                                + " 静音=" + player.audio().isMute()
                                + " 音轨数=" + tracks);
                    }
                    // 音量已生效且存在音轨 -> 完成
                    if (now > 0 && tracks > 0) {
                        if (bar != null) {
                            SwingUtilities.invokeLater(bar::refreshVolume);
                        }
                        return;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    Log.w(TAG, "音频初始化异常: " + e);
                    return;
                }
            }
            Log.w(TAG, "音频初始化未成功（音轨数或音量仍为 0/-1），"
                    + "可能是流本身无音频或缺少 AAC 解码器");
        }, "audio-init");
        t.setDaemon(true);
        t.start();
    }

    private static void setCursorVisible(JFrame frame, boolean visible) {
        try {
            if (visible) {
                frame.setCursor(java.awt.Cursor.getDefaultCursor());
            } else {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                        16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                frame.setCursor(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
                        img, new java.awt.Point(0, 0), "invisible"));
            }
        } catch (Throwable ignored) {
            // 光标设置失败不影响播放
        }
    }

    /**
     * 记录进全屏前的普通窗口尺寸，退出全屏时恢复。
     */
    private static volatile java.awt.Rectangle lastNormalBounds;

    /**
     * 切换全屏。
     *
     * <p><b>注意：这里绝对不能 dispose()。</b>
     * AWT 的 {@code dispose()} 会触发 {@code windowClosed}，而 windowClosed 里
     * 会 release() 整个播放器（停止播放、释放 libVLC）。之前用
     * dispose + setUndecorated 实现全屏，结果按 F 或双击会把播放器直接杀掉，
     * 窗口重新显示出来也是死的。
     * 现在只切窗口状态，不销毁窗口。
     */
    private static void toggleFullScreen(JFrame frame) {
        SwingUtilities.invokeLater(() -> {
            try {
                boolean wasFull = (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0;
                if (!wasFull) {
                    java.awt.Rectangle b = frame.getBounds();
                    if (b.width > 320 && b.height > 240) {
                        lastNormalBounds = b;
                    }
                    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                } else {
                    frame.setExtendedState(JFrame.NORMAL);
                    if (lastNormalBounds != null) {
                        frame.setBounds(lastNormalBounds);
                    } else {
                        frame.setSize(1280, 760);
                        frame.setLocationRelativeTo(null);
                    }
                }
                frame.validate();
                java.awt.Container cp = frame.getContentPane();
                cp.doLayout();
                cp.validate();
                cp.repaint();
            } catch (Throwable t) {
                Log.w(TAG, "全屏切换失败: " + t);
            }
        });
    }

    // ------------------------------------------------------------------ OSD

    /** 画面上短暂显示的提示（换台/音量）。 */
    static final class Osd extends JPanel {
        private final JLabel label = new JLabel("", JLabel.CENTER);
        private final Timer hide;
        /** OSD 用独立 JWindow 承载：重量级窗口才能显示在 Canvas 之上。 */
        private JWindow window;
        private JFrame parent;

        Osd() {
            super(new BorderLayout());
            setOpaque(true);
            setBackground(new Color(10, 14, 20, 220));
            setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
            label.setForeground(Color.WHITE);
            label.setFont(label.getFont().deriveFont(Font.BOLD, 20f));
            add(label, BorderLayout.CENTER);
            hide = new Timer(1400, e -> {
                if (window != null) {
                    window.setVisible(false);
                }
            });
            hide.setRepeats(false);
        }

        /**
         * 绑定到主窗口。
         * <p>OSD 必须是**独立的重量级窗口**（JWindow），
         * 否则会被 libVLC 的 Canvas 盖住 —— 与侧栏/控制条同一个根因。
         */
        void attachTo(JFrame frame) {
            this.parent = frame;
            window = new JWindow(frame);
            window.setBackground(new Color(0, 0, 0, 0));
            window.getContentPane().add(this);
            window.setFocusableWindowState(false);
            window.setAlwaysOnTop(false);
            window.setVisible(false);
        }

        void show(String text) {
            label.setText(text);
            if (window == null || parent == null) {
                return;
            }
            try {
                window.pack();
                java.awt.Dimension d = window.getSize();
                java.awt.Point p = parent.getLocationOnScreen();
                int cx = p.x + (parent.getWidth() - d.width) / 2;
                int cy = p.y + (parent.getHeight() - d.height) / 2 - 40;
                window.setLocation(Math.max(0, cx), Math.max(0, cy));
                window.setVisible(true);
                window.toFront();
            } catch (Throwable t) {
                Log.w(TAG, "OSD 显示失败: " + t);
            }
            hide.restart();
        }

        void dispose() {
            if (window != null) {
                window.dispose();
                window = null;
            }
        }
    }

    // ------------------------------------------------------------------ 侧边换台栏

    /** 左侧滑出的频道列表（仿 PotPlayer 播放列表）。 */
    static final class SidePanel extends JPanel {
        private final java.util.List<ChannelSpec> channels;
        private final Consumer<ChannelSpec> onPick;
        private final JPanel list;
        private final java.util.Map<String, JPanel> rows = new java.util.HashMap<>();
        private final DesktopConfig config;
        /** 由 slug 生成可分享的播放地址（形如 http://127.0.0.1:8767/cctv1.m3u8）。 */
        private final java.util.function.Function<ChannelSpec, String> urlBuilder;
        private String activeSlug;
        private boolean open;

        SidePanel(List<ChannelSpec> channels, ChannelSpec initial,
                  Consumer<ChannelSpec> onPick, DesktopConfig config,
                  java.util.function.Function<ChannelSpec, String> urlBuilder) {
            super(new BorderLayout());
            this.channels = channels;
            this.onPick = onPick;
            this.config = config;
            this.urlBuilder = urlBuilder;
            this.activeSlug = initial.slug;

            setBackground(new Color(14, 19, 27));
            setPreferredSize(new Dimension(280, 0));
            setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(255, 255, 255, 26)));

            JLabel title = new JLabel("频道列表  (" + channels.size() + ")");
            title.setForeground(new Color(240, 244, 250));
            title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
            title.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
            title.setToolTipText("右键频道可收藏 / 复制链接");
            add(title, BorderLayout.NORTH);

            list = new JPanel();
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            list.setBackground(new Color(14, 19, 27));

            for (int i = 0; i < channels.size(); i++) {
                ChannelSpec spec = channels.get(i);
                JPanel row = buildRow(i + 1, spec);
                rows.put(spec.slug, row);
                list.add(row);
            }

            JScrollPane sp = new JScrollPane(list);
            sp.setBorder(null);
            sp.getVerticalScrollBar().setUnitIncrement(18);
            sp.setBackground(new Color(14, 19, 27));
            add(sp, BorderLayout.CENTER);

            setActive(initial.slug);
            // 记住用户上次是否展开（完整位置记忆的一部分）
            open = config == null || config.playerSidePanelOpen;
            setVisible(open);
        }

        private JPanel buildRow(int num, ChannelSpec spec) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setBackground(new Color(14, 19, 27));
            row.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel numLabel = new JLabel(String.valueOf(num));
            numLabel.setForeground(new Color(126, 139, 156));
            numLabel.setFont(numLabel.getFont().deriveFont(11f));
            numLabel.setPreferredSize(new Dimension(26, 16));

            JLabel nameLabel = new JLabel(spec.name);
            nameLabel.setForeground(new Color(214, 222, 233));
            nameLabel.setFont(nameLabel.getFont().deriveFont(13f));

            // 收藏标记（★），收藏状态变化时刷新
            JLabel favLabel = new JLabel("");
            favLabel.setForeground(new Color(255, 194, 77));
            favLabel.setFont(favLabel.getFont().deriveFont(Font.BOLD, 12f));
            favLabel.setPreferredSize(new Dimension(16, 16));
            refreshFav(favLabel, spec);

            row.add(numLabel, BorderLayout.WEST);
            row.add(nameLabel, BorderLayout.CENTER);
            row.add(favLabel, BorderLayout.EAST);

            // 右键菜单：收藏/取消收藏、复制链接、播放
            final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
            final javax.swing.JMenuItem favItem = new javax.swing.JMenuItem();
            final javax.swing.JMenuItem copyItem =
                    new javax.swing.JMenuItem("复制播放链接");
            final javax.swing.JMenuItem playItem =
                    new javax.swing.JMenuItem("播放此频道");
            menu.add(favItem);
            menu.add(playItem);
            menu.addSeparator();
            menu.add(copyItem);

            // 每次弹出前刷新菜单文案（收藏状态可能已变）
            menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                    boolean fav = config != null && config.isFavorite(spec.slug);
                    favItem.setText(fav ? "取消收藏" : "收藏此频道");
                }

                @Override
                public void popupMenuWillBecomeInvisible(
                        javax.swing.event.PopupMenuEvent e) {
                    // 无需处理
                }

                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                    // 无需处理
                }
            });

            favItem.addActionListener(e -> {
                if (config == null) {
                    return;
                }
                boolean added = config.toggleFavorite(spec.slug);
                refreshFav(favLabel, spec);
                Log.i(TAG, (added ? "已收藏 " : "已取消收藏 ") + spec.name);
            });

            playItem.addActionListener(e -> onPick.accept(spec));

            copyItem.addActionListener(e -> {
                String url = urlBuilder == null ? spec.slug : urlBuilder.apply(spec);
                try {
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                            new java.awt.datatransfer.StringSelection(url), null);
                    // 给用户一个明确反馈，否则不知道复制成功没
                    javax.swing.JOptionPane.showMessageDialog(row,
                            "已复制到剪贴板：\n" + url,
                            "复制播放链接", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                } catch (Throwable t) {
                    Log.w(TAG, "复制链接失败: " + t);
                }
            });

            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    if (!spec.slug.equals(activeSlug)) {
                        row.setBackground(new Color(28, 37, 52));
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (!spec.slug.equals(activeSlug)) {
                        row.setBackground(new Color(14, 19, 27));
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    maybePopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybePopup(e);
                }

                /** 右键弹出菜单；左键单击换台。 */
                private void maybePopup(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        menu.show(e.getComponent(), e.getX(), e.getY());
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    // 只有左键才是换台，右键走菜单
                    if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                        onPick.accept(spec);
                    }
                }
            });
            return row;
        }

        /** 刷新某行的收藏星标。 */
        private void refreshFav(JLabel favLabel, ChannelSpec spec) {
            boolean fav = config != null && config.isFavorite(spec.slug);
            favLabel.setText(fav ? "★" : "");
            favLabel.setToolTipText(fav ? "已收藏" : null);
        }

        void setActive(String slug) {
            this.activeSlug = slug;
            for (java.util.Map.Entry<String, JPanel> e : rows.entrySet()) {
                JPanel row = e.getValue();
                boolean active = e.getKey().equals(slug);
                row.setBackground(active ? new Color(194, 28, 43)
                        : new Color(14, 19, 27));
                for (Component c : row.getComponents()) {
                    if (c instanceof JLabel) {
                        ((JLabel) c).setForeground(active ? Color.WHITE
                                : (c == row.getComponent(0)
                                ? new Color(126, 139, 156) : new Color(214, 222, 233)));
                    }
                }
            }
            // 滚动到当前项
            JPanel active = rows.get(slug);
            if (active != null) {
                active.scrollRectToVisible(active.getBounds());
            }
        }

        boolean isOpen() {
            return open;
        }

        /** 切换侧栏显示。实际位置与尺寸由外层 root 的 doLayout 负责。 */
        void toggle() {
            open = !open;
            setVisible(open);
            // 记住侧栏状态（完整位置记忆的一部分）
            if (config != null) {
                config.playerSidePanelOpen = open;
            }
            revalidate();
            repaint();
        }

        void next() {
            int i = indexOf(activeSlug);
            if (i >= 0 && i + 1 < channels.size()) {
                onPick.accept(channels.get(i + 1));
            }
        }

        void prev() {
            int i = indexOf(activeSlug);
            if (i > 0) {
                onPick.accept(channels.get(i - 1));
            }
        }

        private int indexOf(String slug) {
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).slug.equals(slug)) {
                    return i;
                }
            }
            return -1;
        }
    }

    // ------------------------------------------------------------------ 底部控制条

    /**
     * 底部控制条：播放/暂停、音量、换台栏、全屏、返回。
     *
     * <p>设计要点（对齐成熟播放器）：
     * <ul>
     *   <li>播放键图标随状态变化（▶ / ⏸），让用户一眼看出当前状态</li>
     *   <li>音量有数值显示，调音量时不用猜</li>
     *   <li>半透明深色底，压在视频上也不挡画面</li>
     *   <li>按钮有 hover 反馈，明确"这里可以点"</li>
     * </ul>
     */
    static final class ControlBar extends JPanel {
        private final JLabel buffering = new JLabel("");
        private final JLabel channelName = new JLabel("");
        private final JLabel volumeLabel = new JLabel();
        private final javax.swing.JButton playBtn;
        private final EmbeddedMediaPlayer player;
        /** 记住最后有效音量（libVLC 起播前会返回 0）。 */
        private int lastVolume = 100;

        ControlBar(EmbeddedMediaPlayer player, DesktopConfig config,
                   Runnable onToggleSide, Runnable onFullScreen, Runnable onExit) {
            super(new BorderLayout(10, 0));
            this.player = player;
            // 半透明深色底：压在视频上不刺眼，也不会挡太多画面
            setOpaque(true);
            setBackground(new Color(12, 16, 23, 235));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(255, 255, 255, 30)),
                    BorderFactory.createEmptyBorder(8, 14, 8, 14)));

            JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
            left.setOpaque(false);

            // 播放/暂停：用矢量图标自己画。
            // 实测 ▶ / ⏸ / 🔇 这些字符在 Windows 默认字体下渲染不稳定
            // （暂停变成两条细竖线、静音变成方块），自己画才能保证清晰。
            playBtn = new javax.swing.JButton() {
                @Override
                protected void paintComponent(java.awt.Graphics g) {
                    super.paintComponent(g);
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(240, 244, 250));
                    int w = getWidth();
                    int h = getHeight();
                    boolean playing;
                    try {
                        playing = player.status().isPlaying();
                    } catch (Throwable t) {
                        playing = false;
                    }
                    if (playing) {
                        // 暂停：两个圆角竖条
                        int barW = 5;
                        int gap = 5;
                        int barH = 15;
                        int totalW = barW * 2 + gap;
                        int x = (w - totalW) / 2;
                        int y = (h - barH) / 2;
                        g2.fillRoundRect(x, y, barW, barH, 2, 2);
                        g2.fillRoundRect(x + barW + gap, y, barW, barH, 2, 2);
                    } else {
                        // 播放：实心三角形
                        int size = 15;
                        int x = (w - size) / 2 + 1;
                        int y = (h - size) / 2;
                        java.awt.geom.Path2D tri = new java.awt.geom.Path2D.Double();
                        tri.moveTo(x, y);
                        tri.lineTo(x, y + size);
                        tri.lineTo(x + size - 2, y + size / 2.0);
                        tri.closePath();
                        g2.fill(tri);
                    }
                    g2.dispose();
                }
            };
            styleFlatButton(playBtn, "播放 / 暂停 (空格)");
            playBtn.setPreferredSize(new Dimension(40, 32));
            playBtn.addActionListener(e -> {
                try {
                    player.controls().setPause(player.status().isPlaying());
                    refreshPlayButton();
                    playBtn.repaint();
                } catch (Throwable ignored) {
                    // 忽略
                }
            });
            left.add(playBtn);

            // 停止：实心方块
            javax.swing.JButton stopBtn = new javax.swing.JButton() {
                @Override
                protected void paintComponent(java.awt.Graphics g) {
                    super.paintComponent(g);
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(240, 244, 250));
                    int size = 12;
                    g2.fillRoundRect((getWidth() - size) / 2, (getHeight() - size) / 2,
                            size, size, 2, 2);
                    g2.dispose();
                }
            };
            styleFlatButton(stopBtn, "停止");
            stopBtn.setPreferredSize(new Dimension(36, 32));
            stopBtn.addActionListener(e -> {
                try {
                    player.controls().stop();
                    refreshPlayButton();
                    playBtn.repaint();
                } catch (Throwable ignored) {
                    // 忽略
                }
            });
            left.add(stopBtn);

            // 静音：喇叭 + 波纹 / 斜线
            javax.swing.JButton muteBtn = new javax.swing.JButton() {
                @Override
                protected void paintComponent(java.awt.Graphics g) {
                    super.paintComponent(g);
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(230, 236, 244));
                    int cx = getWidth() / 2;
                    int cy = getHeight() / 2;
                    boolean mute;
                    try {
                        mute = player.audio().isMute();
                    } catch (Throwable t) {
                        mute = false;
                    }
                    java.awt.geom.Path2D spk = new java.awt.geom.Path2D.Double();
                    spk.moveTo(cx - 8, cy - 3);
                    spk.lineTo(cx - 4, cy - 3);
                    spk.lineTo(cx + 1, cy - 8);
                    spk.lineTo(cx + 1, cy + 8);
                    spk.lineTo(cx - 4, cy + 3);
                    spk.lineTo(cx - 8, cy + 3);
                    spk.closePath();
                    g2.fill(spk);
                    if (mute) {
                        g2.setStroke(new java.awt.BasicStroke(2f));
                        g2.setColor(new Color(230, 70, 80));
                        g2.drawLine(cx - 9, cy - 9, cx + 9, cy + 9);
                    } else {
                        g2.setStroke(new java.awt.BasicStroke(1.6f));
                        g2.drawArc(cx + 1, cy - 6, 8, 12, -60, 120);
                    }
                    g2.dispose();
                }
            };
            styleFlatButton(muteBtn, "静音 (M)");
            muteBtn.setPreferredSize(new Dimension(36, 32));
            muteBtn.addActionListener(e -> {
                try {
                    player.audio().setMute(!player.audio().isMute());
                    refreshVolume();
                    muteBtn.repaint();
                } catch (Throwable ignored) {
                    // 忽略
                }
            });
            left.add(muteBtn);

            left.add(flatButton("−", "音量 -", () -> {
                player.audio().setVolume(Math.max(0, player.audio().volume() - 5));
                refreshVolume();
            }));
            volumeLabel.setForeground(new Color(200, 212, 226));
            volumeLabel.setFont(volumeLabel.getFont().deriveFont(12f));
            volumeLabel.setPreferredSize(new Dimension(42, 18));
            left.add(volumeLabel);
            left.add(flatButton("＋", "音量 +", () -> {
                player.audio().setVolume(Math.min(130, player.audio().volume() + 5));
                refreshVolume();
            }));

            channelName.setForeground(new Color(255, 255, 255));
            channelName.setFont(channelName.getFont().deriveFont(Font.BOLD, 14f));
            channelName.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
            left.add(channelName);

            buffering.setForeground(new Color(255, 200, 80));
            buffering.setFont(buffering.getFont().deriveFont(12f));
            buffering.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
            left.add(buffering);

            JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
            right.setOpaque(false);
            right.add(flatButton("☰", "频道列表 (Tab)", onToggleSide));
            right.add(flatButton("⛶", "全屏 (F / 双击)", onFullScreen));
            right.add(flatButton("✕", "返回选台 (Esc)", onExit));

            add(left, BorderLayout.WEST);
            add(right, BorderLayout.EAST);

            // ---------------- 进度条（可拖动） ----------------
            //
            // 直播流通常 length()=0（无已知时长），此时进度条显示「直播」并禁用拖动。
            // 时移回看 / 已缓冲的 HLS 窗口里 length()>0 且 isSeekable()=true，
            // 这时允许拖动跳到指定位置。
            add(buildSeekRow(), BorderLayout.NORTH);

            refreshPlayButton();
            refreshVolume();

            // 定时刷新按钮图标与进度，覆盖"用快捷键切换播放状态"的场景。
            // 拖动过程中不更新滑块，否则会和用户的手打架。
            Timer sync = new Timer(400, e -> {
                refreshPlayButton();
                if (isShowing()) {
                    refreshVolume();
                    refreshSeek();
                }
            });
            sync.setRepeats(true);
            sync.start();
        }

        // ---------------- 进度条实现 ----------------

        private final javax.swing.JSlider seekBar = new javax.swing.JSlider(0, 1000, 0);
        private final JLabel timeLabel = new JLabel("00:00");
        private final JLabel totalLabel = new JLabel("直播");
        private volatile boolean userDragging;
        /** 上一次已知的可 seek 时长（毫秒），0 表示不可 seek。 */
        private volatile long knownLengthMs;

        private JPanel buildSeekRow() {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

            styleTimeLabel(timeLabel);
            styleTimeLabel(totalLabel);

            seekBar.setOpaque(false);
            seekBar.setFocusable(false);
            seekBar.setBorder(BorderFactory.createEmptyBorder());
            seekBar.setEnabled(false);
            seekBar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            seekBar.setToolTipText("拖动可跳转（直播流通常不可拖动）");

            // 拖动开始时先冻结自动刷新，松手才真正 seek
            seekBar.addChangeListener(e -> {
                if (seekBar.getValueIsAdjusting()) {
                    userDragging = true;
                    // 拖动时实时更新时间文字，给用户预览
                    long len = knownLengthMs;
                    if (len > 0) {
                        long target = (long) (len * (seekBar.getValue() / 1000.0));
                        timeLabel.setText(formatMs(target));
                    }
                }
            });
            seekBar.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    userDragging = false;
                    long len = knownLengthMs;
                    if (len > 0) {
                        long target = (long) (len * (seekBar.getValue() / 1000.0));
                        try {
                            player.controls().setTime(target);
                            Log.i(TAG, "进度跳转到 " + formatMs(target));
                        } catch (Throwable t) {
                            Log.w(TAG, "进度跳转失败: " + t);
                        }
                    }
                }
            });

            row.add(timeLabel, BorderLayout.WEST);
            row.add(seekBar, BorderLayout.CENTER);
            row.add(totalLabel, BorderLayout.EAST);
            return row;
        }

        private static void styleTimeLabel(JLabel l) {
            l.setForeground(new Color(170, 182, 198));
            l.setFont(l.getFont().deriveFont(11f));
            l.setPreferredSize(new Dimension(46, 14));
        }

        /** 刷新进度显示。 */
        void refreshSeek() {
            try {
                long length = player.status().length();
                long time = player.status().time();
                boolean seekable = player.status().isSeekable();

                // 直播流 length 常为 0；此时明确显示「直播」而不是误导性的空进度条
                if (length <= 0) {
                    knownLengthMs = 0;
                    seekBar.setEnabled(false);
                    seekBar.setValue(0);
                    timeLabel.setText(formatMs(Math.max(0, time)));
                    totalLabel.setText("直播");
                    return;
                }

                knownLengthMs = length;
                // 可 seek 才允许拖动
                if (seekBar.isEnabled() != seekable) {
                    seekBar.setEnabled(seekable);
                    seekBar.setToolTipText(seekable
                            ? "拖动可跳转" : "当前流不支持跳转");
                }
                totalLabel.setText(formatMs(length));
                if (!userDragging) {
                    timeLabel.setText(formatMs(Math.max(0, time)));
                    int v = (int) Math.round(1000.0 * time / Math.max(1, length));
                    seekBar.setValue(Math.max(0, Math.min(1000, v)));
                }
            } catch (Throwable ignored) {
                // 状态读取失败保持原样
            }
        }

        /** 毫秒 -> mm:ss 或 h:mm:ss。 */
        static String formatMs(long ms) {
            if (ms < 0) {
                ms = 0;
            }
            long totalSec = ms / 1000;
            long h = totalSec / 3600;
            long m = (totalSec % 3600) / 60;
            long s = totalSec % 60;
            if (h > 0) {
                return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s);
            }
            return String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
        }

        void refreshPlayButton() {
            try {
                boolean playing = player.status().isPlaying();
                // 图标由 paintComponent 按当前状态绘制，不依赖文本；
                // 这里只同步 tooltip 并触发重绘。
                String tip = playing ? "暂停 (空格)" : "播放 (空格)";
                if (!tip.equals(playBtn.getToolTipText())) {
                    playBtn.setToolTipText(tip);
                }
                playBtn.repaint();
            } catch (Throwable ignored) {
                // 状态读取失败时保持原样
            }
        }

        void refreshVolume() {
            try {
                int v = player.audio().volume();
                boolean mute = player.audio().isMute();
                // libVLC 在开始播放前 volume() 可能返回 0，
                // 这时用记住的最后有效音量兜底，
                // 避免界面显示 0% 误导用户。
                if (v <= 0 && !mute) {
                    v = lastVolume;
                }
                if (v > 0) {
                    lastVolume = v;
                }
                volumeLabel.setText(mute ? "静音" : (v + "%"));
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        void setChannel(String name) {
            channelName.setText(name);
        }

        void setBuffering(float percent) {
            buffering.setText(percent < 0 ? "" : ("缓冲 " + (int) percent + "%"));
        }

        /** 控制条按钮的常色/悬停色（与半透明控制条底协调）。 */
        private static final Color BTN_BG = new Color(12, 16, 23, 0);
        private static final Color BTN_HOVER = new Color(58, 70, 90, 200);

        /** 给无文字（矢量图标）的按钮套用与控制条一致的样式。 */
        private static void styleFlatButton(javax.swing.JButton b, String tip) {
            b.setToolTipText(tip);
            b.setFocusPainted(false);
            b.setBorderPainted(false);
            b.setContentAreaFilled(false);
            b.setOpaque(false);   // 关键：不透明按钮在 paintComponent 前置绘制会遮住自绘图标
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            b.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    b.setOpaque(true);
                    b.setBackground(BTN_HOVER);
                    b.repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    b.setOpaque(false);
                    b.setBackground(BTN_BG);
                    b.repaint();
                }
            });
        }

        private static javax.swing.JButton flatButton(String text, String tip, Runnable action) {
            javax.swing.JButton b = new javax.swing.JButton(text);
            b.setToolTipText(tip);
            b.setForeground(new Color(232, 237, 244));
            b.setBackground(new Color(14, 19, 27));
            b.setFocusPainted(false);
            b.setBorderPainted(false);
            b.setContentAreaFilled(false);
            b.setOpaque(true);
            b.setFont(b.getFont().deriveFont(Font.PLAIN, 15f));
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.setBorder(BorderFactory.createEmptyBorder(6, 11, 6, 11));
            b.addActionListener(e -> {
                try {
                    action.run();
                } catch (Throwable ignored) {
                    // 忽略
                }
            });
            b.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    b.setBackground(new Color(35, 44, 59));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    b.setBackground(new Color(14, 19, 27));
                }
            });
            return b;
        }
    }
}
