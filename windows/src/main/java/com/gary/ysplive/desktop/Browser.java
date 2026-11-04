package com.gary.ysplive.desktop;

import com.gary.ysplive.core.spi.Log;

import java.awt.Desktop;
import java.net.URI;

/** 用系统默认浏览器打开 URL（用于订阅地址等）。 */
final class Browser {

    private Browser() {
    }

    static void open(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop d = Desktop.getDesktop();
                if (d.isSupported(Desktop.Action.BROWSE)) {
                    d.browse(new URI(url));
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w("browser", "打开浏览器失败: " + t);
        }
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (Throwable t) {
            Log.w("browser", "备用打开方式也失败: " + t);
        }
    }

    /** 用系统默认程序打开一个文件（例如日志、说明文档）。 */
    static void openFile(java.io.File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop d = Desktop.getDesktop();
                if (d.isSupported(Desktop.Action.OPEN)) {
                    d.open(file);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w("browser", "打开文件失败: " + t);
        }
        try {
            Runtime.getRuntime().exec(new String[]{"notepad.exe", file.getAbsolutePath()});
            return;
        } catch (Throwable ignored) {
            // 落到 cmd start
        }
        try {
            new ProcessBuilder("cmd", "/c", "start", "", file.getAbsolutePath()).start();
        } catch (Throwable t) {
            Log.w("browser", "备用打开方式也失败: " + t);
        }
    }

    /** 在资源管理器中定位文件。 */
    static void revealInExplorer(java.io.File file) {
        if (file == null) {
            return;
        }
        try {
            new ProcessBuilder("explorer.exe", "/select,", file.getAbsolutePath()).start();
        } catch (Throwable t) {
            Log.w("browser", "定位文件失败: " + t);
        }
    }
}
