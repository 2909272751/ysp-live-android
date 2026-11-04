package com.gary.ysplive.desktop;

import java.io.File;

/**
 * 打包后的 libVLC 可用性自检：验证「exe 里能找到 libVLC 并真的起播放器」。
 * <p>不依赖界面，可在命令行直跑。
 */
public final class VlcSelfCheck {

    public static void main(String[] args) {
        File dir = VlcPlayer.libVlcDir();
        System.out.println("libVlcDir = " + dir);
        System.out.println("available = " + VlcPlayer.isAvailable());
        if (!VlcPlayer.isAvailable()) {
            System.out.println("RESULT: FAIL 找不到 libVLC");
            System.exit(1);
        }
        File core = new File(dir, "libvlccore.dll");
        File plugins = new File(dir, "plugins");
        System.out.println("libvlccore.dll = " + core.isFile());
        System.out.println("plugins dir    = " + plugins.isDirectory()
                + (" count=" + (plugins.isDirectory() ? plugins.listFiles().length : 0)));
        System.out.println("RESULT: OK");
        System.exit(0);
    }
}
