package com.gary.ysplive.desktop;

import com.gary.ysplive.core.spi.Log;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionListener;

import javax.imageio.ImageIO;

/**
 * 系统托盘图标（商用桌面软件的基本要求）。
 *
 * <p>作用：
 * <ul>
 *   <li>关闭主窗口后程序仍在托盘常驻，正在播放的视频不会因为误关窗口而中断</li>
 *   <li>双击托盘图标快速呼出选台界面</li>
 *   <li>右键菜单：打开主界面 / 显示当前频道 / 退出</li>
 * </ul>
 *
 * <p>用 AWT 的 {@link SystemTray} 而不是 JavaFX 方案：
 * JavaFX 自身没有托盘 API，而 AWT 托盘在 Windows 上是最稳的实现。
 * 注意 AWT 事件在 EDT 线程，需要 runLater 切回 JavaFX 线程。
 */
final class Tray {

    private static final String TAG = "tray";

    private TrayIcon icon;
    private final Runnable onOpen;
    private final Runnable onExit;
    /** 停止播放并回到选台（托盘菜单用）。 */
    private final Runnable onStopPlayback;

    Tray(Runnable onOpen, Runnable onExit, Runnable onStopPlayback) {
        this.onOpen = onOpen;
        this.onExit = onExit;
        this.onStopPlayback = onStopPlayback;
    }

    Tray(Runnable onOpen, Runnable onExit) {
        this(onOpen, onExit, onOpen);
    }

    /**
     * 挑一个能显示中文的字体。
     * <p>AWT 菜单项默认字体在部分 Windows 上不含 CJK 字形，
     * 会导致菜单文字变成方框。这里按优先级探测，并用
     * {@link java.awt.Font#canDisplayUpTo} 实际验证能否画出中文。
     */
    private static java.awt.Font pickCjkFont() {
        String probe = "打开选台界面退出程序停止播放";
        String[] candidates = {
                "Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑",
                "SimSun", "宋体", "SimHei", "黑体",
                "Dialog", java.awt.Font.DIALOG,
        };
        for (String name : candidates) {
            try {
                java.awt.Font f = new java.awt.Font(name, java.awt.Font.PLAIN, 12);
                if (f.canDisplayUpTo(probe) < 0) {
                    Log.i(TAG, "托盘菜单字体 = " + name);
                    return f;
                }
            } catch (Throwable ignored) {
                // 尝试下一个
            }
        }
        Log.w(TAG, "没有找到可显示中文的字体，托盘菜单可能显示为方框");
        return null;
    }

    private static void applyFont(MenuItem item, java.awt.Font font) {
        if (item != null && font != null) {
            try {
                item.setFont(font);
            } catch (Throwable ignored) {
                // 部分平台不支持
            }
        }
    }

    /** 是否支持系统托盘（极少数精简版 Windows 不支持）。 */
    static boolean isSupported() {
        try {
            return SystemTray.isSupported();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 安装托盘图标。
     *
     * @return 是否成功
     */
    boolean install() {
        if (!isSupported()) {
            Log.w(TAG, "系统不支持托盘");
            return false;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();

            Image img = loadIcon();
            PopupMenu menu = new PopupMenu();

            // 关键：AWT 的 PopupMenu/MenuItem 默认字体在部分 Windows 上
            // 渲染不了中文，菜单项会显示成一排方框（用户实测反馈"右键有奇怪的东西"）。
            // 必须显式挑一个能显示中文的字体，并用 canDisplayUpTo 验证。
            java.awt.Font cjk = pickCjkFont();

            MenuItem open = new MenuItem("打开选台界面");
            applyFont(open, cjk);
            open.addActionListener(e -> fire(onOpen));
            menu.add(open);

            MenuItem stopPlay = new MenuItem("停止播放并回到选台");
            applyFont(stopPlay, cjk);
            stopPlay.addActionListener(e -> fire(onStopPlayback));
            menu.add(stopPlay);

            menu.addSeparator();

            MenuItem exit = new MenuItem("退出程序");
            applyFont(exit, cjk);
            exit.addActionListener(e -> fire(onExit));
            menu.add(exit);

            icon = new TrayIcon(img, "央视频直播", menu);
            // 注意：TrayIcon 没有 setFont —— 气泡通知的字体由系统决定，
            // 只有 PopupMenu 里的 MenuItem 才能单独设字体。
            icon.setImageAutoSize(true);
            // 双击托盘图标 = 打开主界面
            ActionListener dbl = e -> {
                if (e.getActionCommand() != null
                        && e.getActionCommand().contains("DoubleClick")) {
                    fire(onOpen);
                }
            };
            icon.addActionListener(dbl);

            tray.add(icon);
            Log.i(TAG, "托盘图标已安装");
            return true;
        } catch (AWTException e) {
            Log.w(TAG, "托盘初始化失败: " + e);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "托盘初始化异常: " + t);
            return false;
        }
    }

    /** 更新托盘提示文字（显示当前频道）。 */
    void setTooltip(String text) {
        if (icon != null) {
            try {
                icon.setToolTip(text == null || text.isEmpty()
                        ? "央视频直播" : "央视频直播 · " + text);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 弹一条气泡通知（例如在后台切换了频道）。 */
    void notify(String title, String body) {
        if (icon != null) {
            try {
                icon.displayMessage(title, body, TrayIcon.MessageType.NONE);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    void remove() {
        if (icon != null) {
            try {
                SystemTray.getSystemTray().remove(icon);
            } catch (Throwable ignored) {
                // 忽略
            }
            icon = null;
        }
    }

    /** AWT 事件线程 -> JavaFX 线程。 */
    private static void fire(Runnable r) {
        if (r == null) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                Log.w(TAG, "托盘回调失败: " + t);
            }
        });
    }

    private static Image loadIcon() {
        // 优先用内置的多尺寸 ico 转出的 png；没有就画一个纯色块兜底
        try {
            java.net.URL u = Tray.class.getResource("/ui/tray.png");
            if (u != null) {
                Image img = ImageIO.read(u);
                if (img != null) {
                    return img;
                }
            }
        } catch (Throwable ignored) {
            // 走兜底
        }
        int s = 32;
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(s, s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = bi.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0xC2, 0x1C, 0x2B));
        g.fillRoundRect(0, 0, s, s, 8, 8);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("Dialog", java.awt.Font.BOLD, 18));
        g.drawString("央", 4, 24);
        g.dispose();
        return bi;
    }
}
