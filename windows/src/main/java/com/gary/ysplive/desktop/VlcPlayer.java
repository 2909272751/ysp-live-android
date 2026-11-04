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
                "--avcodec-hw=dxva2");

        final EmbeddedMediaPlayer player = factory.mediaPlayers().newEmbeddedMediaPlayer();
        player.audio().setVolume(Math.max(0, Math.min(130, config.playerVolume)));

        final JFrame frame = new JFrame("央视频直播 · " + initial.name);
        frame.setUndecorated(false);
        frame.setBackground(Color.BLACK);

        final Canvas canvas = new Canvas();
        canvas.setBackground(Color.BLACK);

        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas));

        final Window window = new Window(frame, player, factory);

        // ---------------- 界面组装 ----------------
        //
        // 布局原则（仿 PotPlayer / VLC / mpv）：
        //   · 视频始终铺满整个窗口，不因控制条/侧栏的显隐而改变尺寸
        //   · 控制条与侧栏是**浮在视频上的覆盖层**，不是布局的一部分
        //
        // 之前的实现把控制条放在 BorderLayout.SOUTH、侧栏放在 WEST，
        // 一隐藏就会触发整个窗口 relayout 并让视频区跳一下；而且鼠标移到
        // 控制条/侧栏上时收不到 mouseMoved（监听只装在视频层），
        // 于是"3 秒自动隐藏"在用户正要点击时反而把控件藏了——
        // 这就是"鼠标放上去不像成熟播放器有播放键"的直接原因。
        // 用字段保存覆盖层引用，供 doLayout 使用。
        // 不能直接捕获后面才声明的局部变量（Java 的前向引用限制）。
        final JComponent[] sideHolder = new JComponent[1];
        final JComponent[] barHolder = new JComponent[1];

        final JPanel root = new JPanel(null) {
            @Override
            public void doLayout() {
                int w = getWidth();
                int h = getHeight();
                // 索引 0 是视频层（最先加入），铺满整个窗口
                if (getComponentCount() > 0) {
                    getComponent(0).setBounds(0, 0, w, h);
                }

                JComponent sideComp = sideHolder[0];
                JComponent barComp = barHolder[0];

                // 控制条浮在底部，横跨整个宽度
                int barH = 0;
                if (barComp != null) {
                    barH = Math.max(46, barComp.getPreferredSize().height);
                    barComp.setBounds(0, h - barH, w, barH);
                }

                // 侧栏浮在左侧，底部让出控制条的高度
                if (sideComp != null) {
                    boolean isOpen = !(sideComp instanceof SidePanel)
                            || ((SidePanel) sideComp).isOpen();
                    sideComp.setBounds(0, 0, isOpen ? 280 : 0, Math.max(0, h - barH));
                }
            }
        };
        root.setBackground(Color.BLACK);

        // 视频区（画布 + OSD 叠加）
        final Osd osd = new Osd();

        final JPanel videoLayer;
        videoLayer = new JPanel(null) {
            @Override
            public void doLayout() {
                canvas.setBounds(0, 0, getWidth(), getHeight());
                // OSD 放在视频区域的垂直居中位置，
                // 并避开底部控制条（否则会压住控制条上的文字）
                if (osd.getParent() == this) {
                    Dimension d = osd.getPreferredSize();
                    int safeH = Math.max(1, getHeight() - 56);
                    osd.setBounds((getWidth() - d.width) / 2,
                            Math.max(0, (safeH - d.height) / 2), d.width, d.height);
                }
            }
        };
        videoLayer.setBackground(Color.BLACK);
        videoLayer.add(canvas);

        videoLayer.add(osd);
        osd.setVisible(false);

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
        final SidePanel side = new SidePanel(channels, initial, spec -> {
            if (onSwitch != null) {
                onSwitch.onSwitch(spec);
            }
            frame.setTitle("央视频直播 · " + spec.name);
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
        });
        sideRef[0] = side;
        // 从初始流地址推导前缀（形如 http://127.0.0.1:8767）
        try {
            int cut = mrl.lastIndexOf('/');
            if (cut > 0) {
                switchPrefixRef.set(mrl.substring(0, cut));
            }
        } catch (RuntimeException ignored) {
            // 推导失败则换台只更新界面（降级但不崩）
        }

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

        // 注册到 holder，供 root.doLayout 定位
        sideHolder[0] = side;
        barHolder[0] = bar;

        // 覆盖层顺序：视频在最底，侧栏其次，控制条最上（后加的在上层）
        root.add(videoLayer);
        root.add(side);
        root.add(bar);

        frame.setContentPane(root);

        // ---------------- 事件 ----------------

        player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mp) {
                retryCount.set(0);
                recovering.set(false);
                Log.i(TAG, "开始播放: " + initial.name);
                SwingUtilities.invokeLater(() -> bar.setBuffering(-1));
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

        // ---------------- 控制条 / 侧栏的显隐（仿成熟播放器） ----------------
        //
        // 成熟播放器的行为（PotPlayer / VLC / mpv）：
        //   · 鼠标一动就显示控制条
        //   · 鼠标停在控制条/侧栏上时**不隐藏**（否则用户正要点就消失）
        //   · 鼠标静止 N 秒且不在任何控件上 -> 隐藏
        //   · 隐藏只是"不可见"，不参与布局，视频尺寸完全不变
        //
        // 旧实现的两个错误：
        //   1) 只在 videoLayer 上装 mouseMoved，鼠标移到控制条/侧栏上就收不到事件，
        //      于是 3 秒后照样隐藏；
        //   2) 控制条在 BorderLayout.SOUTH，隐藏会触发 relayout 让视频跳一下。
        final Timer[] hideRef = new Timer[1];
        final Timer hideTimer = new Timer(2800, e -> {
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

        // 全局鼠标监听：装在 root 上，任何子组件上的移动都能收到。
        // AWT 的事件会从子组件冒泡到父容器，所以 root 能捕获整个窗口的移动。
        // 全局鼠标监听：装在 root 上，任何子组件上的移动都能收到。
        // AWT 事件会从子组件冒泡到父容器，所以 root 能覆盖整个窗口。
        root.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (!bar.isVisible()) {
                    bar.setVisible(true);
                    root.revalidate();
                    root.repaint();
                }
                setCursorVisible(frame, true);
                hideTimer.restart();
            }
        });
        // 控件上也要监听，否则鼠标停在控制条上时计时器仍在跑
        bar.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hideTimer.restart();
            }
        });
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
                    window.release();
                    if (onExit != null) {
                        onExit.onExit();
                    }
                }
            });
            if (config.fullScreenPlayer) {
                frame.setUndecorated(true);
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            } else {
                frame.setSize(1280, 760);
                frame.setLocationRelativeTo(null);
            }
            frame.setVisible(true);
            canvas.requestFocusInWindow();
            // 首帧强制布局一次，确保浮层位置正确（null 布局不会自动排）
            root.validate();
            root.repaint();
            // 启动时给用户 3 秒看到控制条，然后自动隐藏
            hideTimer.start();
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

    private static void toggleFullScreen(JFrame frame) {
        SwingUtilities.invokeLater(() -> {
            boolean wasUndecorated = frame.isUndecorated();
            frame.dispose();
            frame.setUndecorated(!wasUndecorated);
            if (!wasUndecorated) {
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            } else {
                frame.setExtendedState(JFrame.NORMAL);
                frame.setSize(1280, 760);
                frame.setLocationRelativeTo(null);
            }
            frame.setVisible(true);
        });
    }

    // ------------------------------------------------------------------ OSD

    /** 画面上短暂显示的提示（换台/音量）。 */
    static final class Osd extends JPanel {
        private final JLabel label = new JLabel("", JLabel.CENTER);
        private final Timer hide;

        Osd() {
            super(new BorderLayout());
            setOpaque(true);
            setBackground(new Color(10, 14, 20, 220));
            setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
            label.setForeground(Color.WHITE);
            label.setFont(label.getFont().deriveFont(Font.BOLD, 20f));
            add(label, BorderLayout.CENTER);
            hide = new Timer(1400, e -> setVisible(false));
            hide.setRepeats(false);
        }

        void show(String text) {
            label.setText(text);
            setVisible(true);
            setPreferredSize(null);
            revalidate();
            hide.restart();
        }
    }

    // ------------------------------------------------------------------ 侧边换台栏

    /** 左侧滑出的频道列表（仿 PotPlayer 播放列表）。 */
    static final class SidePanel extends JPanel {
        private final java.util.List<ChannelSpec> channels;
        private final Consumer<ChannelSpec> onPick;
        private final JPanel list;
        private final java.util.Map<String, JPanel> rows = new java.util.HashMap<>();
        private String activeSlug;
        private boolean open;

        SidePanel(List<ChannelSpec> channels, ChannelSpec initial,
                  Consumer<ChannelSpec> onPick) {
            super(new BorderLayout());
            this.channels = channels;
            this.onPick = onPick;
            this.activeSlug = initial.slug;

            setBackground(new Color(14, 19, 27));
            setPreferredSize(new Dimension(280, 0));
            setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(255, 255, 255, 26)));

            JLabel title = new JLabel("频道列表  (" + channels.size() + ")");
            title.setForeground(new Color(240, 244, 250));
            title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
            title.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
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
            // 默认**展开**：用户反馈"侧边栏一播放就没有"，说明默认隐藏不合理。
            // 成熟播放器（PotPlayer 播放列表、VLC 播放列表）默认都是可见的，
            // 用户可以按 Tab 或点控制条的 ☰ 收起。
            setVisible(true);
            open = true;
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

            row.add(numLabel, BorderLayout.WEST);
            row.add(nameLabel, BorderLayout.CENTER);

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
                public void mouseClicked(MouseEvent e) {
                    onPick.accept(spec);
                }
            });
            return row;
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

            refreshPlayButton();
            refreshVolume();

            // 定时刷新按钮图标，覆盖"用快捷键切换播放状态"的场景
            Timer sync = new Timer(500, e -> {
                refreshPlayButton();
                if (isShowing()) {
                    refreshVolume();
                }
            });
            sync.setRepeats(true);
            sync.start();
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
