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

        Window(JFrame frame, EmbeddedMediaPlayer player, MediaPlayerFactory factory) {
            this.frame = frame;
            this.player = player;
            this.factory = factory;
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

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.BLACK);

        // 视频区（画布 + OSD 叠加）
        final Osd osd = new Osd();

        JPanel videoLayer = new JPanel(null) {
            @Override
            public void doLayout() {
                canvas.setBounds(0, 0, getWidth(), getHeight());
                // OSD 居中
                if (osd.getParent() == this) {
                    Dimension d = osd.getPreferredSize();
                    osd.setBounds((getWidth() - d.width) / 2,
                            (getHeight() - d.height) / 2 - 40, d.width, d.height);
                }
            }
        };
        videoLayer.setBackground(Color.BLACK);
        videoLayer.add(canvas);

        videoLayer.add(osd);
        osd.setVisible(false);

        // 侧边换台栏
        // 用数组持有自身引用，避免 lambda 里引用"尚未初始化"的局部变量
        final SidePanel[] sideRef = new SidePanel[1];
        final SidePanel side = new SidePanel(channels, initial, spec -> {
            if (onSwitch != null) {
                onSwitch.onSwitch(spec);
            }
            frame.setTitle("央视频直播 · " + spec.name);
            if (sideRef[0] != null) {
                sideRef[0].setActive(spec.slug);
            }
            osd.show("换台  " + spec.name);
        });
        sideRef[0] = side;

        // 底部控制条
        final ControlBar bar = new ControlBar(player, config,
                () -> {
                    // 换台栏开关
                    side.toggle(videoLayer.getWidth());
                },
                () -> toggleFullScreen(frame),
                () -> {
                    // 返回选台
                    window.release();
                    if (onExit != null) {
                        onExit.onExit();
                    }
                });

        root.add(videoLayer, BorderLayout.CENTER);
        root.add(bar, BorderLayout.SOUTH);
        root.add(side, BorderLayout.WEST);

        frame.setContentPane(root);

        // ---------------- 事件 ----------------

        player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mp) {
                Log.i(TAG, "开始播放: " + initial.name);
            }

            @Override
            public void error(MediaPlayer mp) {
                Log.w(TAG, "播放出错");
                SwingUtilities.invokeLater(() -> osd.show("播放出错，正在重试…"));
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

        // 鼠标移动 -> 显示控制条，静止 3 秒 -> 隐藏（仿 PotPlayer）
        final Timer[] hideRef = new Timer[1];
        final Timer hideTimer = new Timer(3000, e -> {
            if (player.status().isPlaying()) {
                bar.setVisible(false);
                if (hideRef[0] != null) {
                    hideRef[0].stop();
                }
            }
        });
        hideRef[0] = hideTimer;
        hideTimer.setRepeats(false);

        videoLayer.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (!bar.isVisible()) {
                    bar.setVisible(true);
                    root.revalidate();
                }
                hideTimer.restart();
            }
        });
        videoLayer.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    toggleFullScreen(frame);
                    return;
                }
                // 单击切换播放/暂停（成熟播放器通用行为）
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
            // 启动时的隐藏计时
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

        bind(im, am, KeyEvent.VK_TAB, "sidePanel", () -> side.toggle(videoLayer.getWidth()));

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
                side.toggle(videoLayer.getWidth());
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
            // 默认隐藏（宽度 0），按 Tab 或到左边缘时滑出
            setVisible(false);
            open = false;
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

        void toggle(int videoWidth) {
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

    /** 底部控制条：播放/暂停、音量、换台栏、全屏、返回。 */
    static final class ControlBar extends JPanel {
        private final JLabel buffering = new JLabel("");
        private final JLabel channelName = new JLabel("");

        ControlBar(EmbeddedMediaPlayer player, DesktopConfig config,
                   Runnable onToggleSide, Runnable onFullScreen, Runnable onExit) {
            super(new BorderLayout(10, 0));
            setBackground(new Color(14, 19, 27));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(255, 255, 255, 26)),
                    BorderFactory.createEmptyBorder(8, 14, 8, 14)));

            JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
            left.setOpaque(false);

            left.add(flatButton("⏯", "播放 / 暂停 (空格)", () -> {
                player.controls().setPause(player.status().isPlaying());
            }));
            left.add(flatButton("🔇", "静音 (M)", () ->
                    player.audio().setMute(!player.audio().isMute())));
            left.add(flatButton("－", "音量 -", () ->
                    player.audio().setVolume(Math.max(0, player.audio().volume() - 5))));
            left.add(flatButton("＋", "音量 +", () ->
                    player.audio().setVolume(Math.min(130, player.audio().volume() + 5))));

            channelName.setForeground(new Color(255, 255, 255));
            channelName.setFont(channelName.getFont().deriveFont(Font.BOLD, 14f));
            channelName.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
            left.add(channelName);

            buffering.setForeground(new Color(255, 200, 80));
            buffering.setFont(buffering.getFont().deriveFont(12f));
            buffering.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
            left.add(buffering);

            JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
            right.setOpaque(false);
            right.add(flatButton("☰", "频道列表 (Tab)", onToggleSide));
            right.add(flatButton("⛶", "全屏 (F / 双击)", onFullScreen));
            right.add(flatButton("✕", "返回选台 (Esc)", onExit));

            add(left, BorderLayout.WEST);
            add(right, BorderLayout.EAST);
        }

        void setChannel(String name) {
            channelName.setText(name);
        }

        void setBuffering(float percent) {
            buffering.setText(percent < 0 ? "" : ("缓冲 " + (int) percent + "%"));
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
