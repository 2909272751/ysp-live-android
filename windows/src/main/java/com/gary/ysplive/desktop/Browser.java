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
}
