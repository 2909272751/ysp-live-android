package com.gary.ysplive.desktop;

import com.gary.ysplive.core.spi.Log;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

/**
 * 内嵌播放器（libVLC）。
 *
 * <p><b>为什么不依赖系统浏览器了：</b>早期版本用「系统 Edge/Chrome + hls.js」渲染，
 * 虽然能播，但意味着用户机器上必须装浏览器、且关主界面后播放窗口会独立残留。
 * 现在改为随包内置精简版 libVLC（62 MB），真正做到「自带播放器」。
 *
 * <p>播放能力来自 libVLC 自身：HLS demux + H.264/HEVC 硬解 + WASAPI 音频，
 * 不需要任何外部依赖。
 */
final class VlcPlayer {

    private static final String TAG = "vlc";

    /** 播放窗口句柄。 */
    static final class Window {
        final JFrame frame;
        final EmbeddedMediaPlayer player;
        final MediaPlayerFactory factory;
        private final String channelName;
        private volatile boolean released;

        Window(JFrame frame, EmbeddedMediaPlayer player, MediaPlayerFactory factory,
               String channelName) {
            this.frame = frame;
            this.player = player;
            this.factory = factory;
            this.channelName = channelName;
        }

        boolean isShowing() {
            return !released && frame.isDisplayable() && frame.isVisible();
        }

        void toggleFullScreen() {
            SwingUtilities.invokeLater(() -> {
                if (frame.isUndecorated()) {
                    frame.dispose();
                    frame.setUndecorated(false);
                    frame.setVisible(true);
                } else {
                    frame.dispose();
                    frame.setUndecorated(true);
                    frame.setVisible(true);
                    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                }
            });
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
            Log.i(TAG, "播放窗口已关闭: " + channelName);
        }
    }

    private VlcPlayer() {
    }

    /** libVLC 目录是否存在且可用。 */
    static boolean isAvailable() {
        File dir = libVlcDir();
        if (dir == null) {
            return false;
        }
        return new File(dir, "libvlc.dll").isFile();
    }

    /** 定位随包内置的 libVLC 目录。 */
    static File libVlcDir() {
        // 1) 显式配置
        String override = System.getProperty("ysp.vlc.dir");
        if (override != null && !override.trim().isEmpty()) {
            File f = new File(override);
            if (f.isDirectory()) {
                return f;
            }
        }
        // 2) 与 jar/exe 同级或上级的 vlc 目录
        String[] candidates = {"vlc", "../vlc", "../../vlc"};
        File base = appBaseDir();
        for (String c : candidates) {
            File f = new File(base, c);
            if (new File(f, "libvlc.dll").isFile()) {
                return f;
            }
        }
        // 3) 开发期：项目内 _vlc/minimal
        File dev = new File("C:\\1\\ysp-android\\_vlc\\minimal");
        if (new File(dev, "libvlc.dll").isFile()) {
            return dev;
        }
        return null;
    }

    /** 程序所在目录（jar 的上一级 或 exe 所在目录）。 */
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

    /**
     * 打开播放窗口并开始播放。
     *
     * @param mrl         流地址（本地引擎的 m3u8）
     * @param channelName 窗口标题用的频道名
     * @param fullScreen  是否直接全屏
     */
    static Window open(String mrl, String channelName, boolean fullScreen) throws Exception {
        File vlcDir = libVlcDir();
        if (vlcDir == null) {
            throw new IllegalStateException("找不到内置 libVLC 目录");
        }
        // jna 需要在加载 dll 之前拿到搜索路径
        System.setProperty("jna.library.path", vlcDir.getAbsolutePath());

        MediaPlayerFactory factory = new MediaPlayerFactory(
                "--no-video-title-show",
                "--quiet",
                "--no-osd",
                "--avcodec-hw=dxva2");

        final EmbeddedMediaPlayer player = factory.mediaPlayers().newEmbeddedMediaPlayer();

        final JFrame frame = new JFrame("央视频直播 · " + channelName);
        final Canvas canvas = new Canvas();
        canvas.setBackground(Color.BLACK);
        frame.getContentPane().add(canvas, BorderLayout.CENTER);
        frame.setBackground(Color.BLACK);

        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas));

        final Window window = new Window(frame, player, factory, channelName);

        player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mp) {
                Log.i(TAG, "开始播放: " + channelName);
            }

            @Override
            public void error(MediaPlayer mp) {
                Log.w(TAG, "播放出错: " + channelName);
            }

            @Override
            public void finished(MediaPlayer mp) {
                Log.i(TAG, "播放结束: " + channelName);
            }

            @Override
            public void buffering(MediaPlayer mp, float newCache) {
                // 直播流偶尔缓冲，静默处理即可
            }
        });

        // 键盘：空格暂停、F 全屏、Esc 关闭
        canvas.setFocusable(true);
        canvas.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_SPACE:
                        player.controls().setPause(player.status().isPlaying());
                        break;
                    case KeyEvent.VK_F:
                        window.toggleFullScreen();
                        break;
                    case KeyEvent.VK_ESCAPE:
                        if (frame.isUndecorated()) {
                            window.toggleFullScreen();
                        } else {
                            window.release();
                        }
                        break;
                    default:
                        break;
                }
            }
        });
        // 双击切全屏
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    window.toggleFullScreen();
                }
            }
        });

        SwingUtilities.invokeLater(() -> {
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    window.release();
                }
            });
            if (fullScreen) {
                frame.setUndecorated(true);
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            } else {
                frame.setSize(1280, 720);
                frame.setLocationRelativeTo(null);
            }
            frame.setVisible(true);
            canvas.requestFocusInWindow();
        });

        // 给窗口一点时间建立 surface
        Thread.sleep(400);
        player.media().play(mrl);
        Log.i(TAG, "播放 " + channelName + " -> " + mrl);
        return window;
    }
}
