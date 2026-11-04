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

    Tray(Runnable onOpen, Runnable onExit) {
        this.onOpen = onOpen;
        this.onExit = onExit;
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

            MenuItem open = new MenuItem("打开选台界面");
            open.addActionListener(e -> fire(onOpen));
            menu.add(open);

            menu.addSeparator();

            MenuItem exit = new MenuItem("退出");
            exit.addActionListener(e -> fire(onExit));
            menu.add(exit);

            icon = new TrayIcon(img, "央视频直播", menu);
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
