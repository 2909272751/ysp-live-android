package com.gary.ysplive.desktop;

import com.gary.ysplive.core.spi.Log;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 单实例保护。
 *
 * <p><b>为什么需要：</b>桌面程序允许用户反复双击图标。若不拦截，会同时跑起
 * 多个实例，它们争抢同一个引擎端口（8767）、互相覆盖同一份配置文件，
 * 表现为「第二个窗口起来了但播不了」或「设置改了就丢」。
 *
 * <p>实现用 {@link FileLock} 而不是固定端口探测：文件锁在进程退出（包括崩溃）
 * 时由操作系统自动释放，不会像端口绑定那样留下「上次没退干净」的假占用。
 */
final class SingleInstance {

    private static final String TAG = "single-instance";
    private static RandomAccessFile raf;
    private static FileLock lock;

    private SingleInstance() {
    }

    /**
     * 尝试获取单实例锁。
     *
     * @return true 表示本进程是唯一实例；false 表示已有实例在运行，本进程应退出
     */
    static boolean acquire() {
        try {
            Path dir = DesktopConfig.dataDir();
            Files.createDirectories(dir);
            Path lockFile = dir.resolve("ysp-live.lock");

            raf = new RandomAccessFile(lockFile.toFile(), "rw");
            FileChannel ch = raf.getChannel();
            try {
                lock = ch.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException e) {
                // 同一 JVM 内重复获取（例如热重启），视为已有实例
                Log.w(TAG, "本进程内已持有锁");
                return false;
            }
            if (lock == null) {
                String owner = "";
                try {
                    raf.seek(0);
                    byte[] b = new byte[(int) Math.min(32, raf.length())];
                    int n = raf.read(b);
                    if (n > 0) {
                        owner = new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
                    }
                } catch (Throwable ignored) {
                    // 读不到 pid 不影响判定
                }
                Log.w(TAG, "另一个实例正在运行（持有 pid=" + owner + "），本进程退出");
                return false;
            }
            // 写入 pid 便于排查
            raf.setLength(0);
            raf.write(String.valueOf(ProcessHandle.current().pid()).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            raf.getFD().sync();
            Log.i(TAG, "单实例锁已获取 pid=" + ProcessHandle.current().pid());
            return true;
        } catch (Throwable e) {
            // 拿不到锁文件不该阻止程序运行（例如目录只读）
            Log.w(TAG, "单实例检查跳过: " + e);
            return true;
        }
    }

    /** 释放锁（正常退出时）。进程崩溃时由 OS 自动释放。 */
    static void release() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            if (raf != null) {
                raf.close();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        lock = null;
        raf = null;
    }
}
