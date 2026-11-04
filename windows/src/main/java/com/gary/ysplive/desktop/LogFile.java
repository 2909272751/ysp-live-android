package com.gary.ysplive.desktop;

import com.gary.ysplive.core.spi.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 文件日志。
 *
 * <p><b>为什么需要它：</b>安装版 exe 是 GUI 子系统（{@code PE subsystem=2}），
 * 没有控制台窗口——这是刻意的（否则会一直挂个黑框）。
 * 但这样一来 {@code System.out} 就没人看了，出问题无从排查。
 * 所以把所有日志（包括 {@link Log} 总线里的）同时落到文件：
 * <ul>
 *   <li>{@code %APPDATA%\YspLive\logs\ysp-live.log} —— 当前日志</li>
 *   <li>超过 2 MB 自动轮转成 {@code .1}，只保留一份历史，避免无限增长</li>
 * </ul>
 *
 * <p>另外把 JVM 未捕获异常也写进去——GUI 程序崩溃时用户看不到任何提示，
 * 有这条记录才能定位。
 */
final class LogFile {

    private static final String TAG = "logfile";
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private static final BlockingQueue<String> QUEUE = new LinkedBlockingQueue<>(4096);
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT);

    private static volatile Path logPath;
    private static volatile Thread writer;
    private static volatile boolean started;

    private LogFile() {
    }

    /** 单例启动；重复调用无副作用。 */
    static synchronized void start() {
        if (started) {
            return;
        }
        started = true;

        Path dir = DesktopConfig.dataDir().resolve("logs");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("无法创建日志目录: " + e);
            return;
        }
        logPath = dir.resolve("ysp-live.log");
        rotateIfNeeded();

        // 桥接 core 的日志总线
        Log.addListener(entry -> append(entry.format()));

        // 捕获未处理异常
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            append("[FATAL] 线程 " + t.getName() + " 未捕获异常: " + e);
            for (StackTraceElement el : e.getStackTrace()) {
                append("    at " + el);
            }
            Throwable cause = e.getCause();
            int guard = 0;
            while (cause != null && guard++ < 5) {
                append("  caused by: " + cause);
                for (StackTraceElement el : cause.getStackTrace()) {
                    append("    at " + el);
                }
                cause = cause.getCause();
            }
        });

        writer = new Thread(LogFile::drainLoop, "ysp-logfile");
        writer.setDaemon(true);
        writer.start();

        append("===== YspLive 启动 =====");
        append("版本: 9.5.3");
        append("Java: " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        append("系统: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        append("配置目录: " + DesktopConfig.dataDir());
        append("日志文件: " + logPath);
    }

    /** 当前日志文件路径（可能为 null，表示未启用）。 */
    static Path path() {
        return logPath;
    }

    /** 写一条日志；线程安全、不阻塞调用方。 */
    static void append(String line) {
        if (!started || line == null) {
            return;
        }
        String stamped = "[" + TIME.format(new Date()) + "] " + line;
        // 队列满时丢弃，绝不阻塞业务线程
        QUEUE.offer(stamped);
    }

    private static void drainLoop() {
        while (true) {
            try {
                String first = QUEUE.take();
                StringBuilder batch = new StringBuilder(4096);
                batch.append(first).append('\n');
                // 批量合并，减少 IO 次数
                String more;
                while ((more = QUEUE.poll(50, TimeUnit.MILLISECONDS)) != null) {
                    batch.append(more).append('\n');
                }
                writeBatch(batch.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ignored) {
                // 日志本身绝不能把程序弄崩
            }
        }
    }

    private static void writeBatch(String text) {
        Path p = logPath;
        if (p == null) {
            return;
        }
        try {
            rotateIfNeeded();
            Files.write(p, text.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 写不进去就算了，别影响主流程
        }
    }

    /** 超过上限就把当前日志改名成 .1（覆盖旧的 .1）。 */
    private static void rotateIfNeeded() {
        Path p = logPath;
        if (p == null) {
            return;
        }
        try {
            File f = p.toFile();
            if (!f.isFile() || f.length() <= MAX_BYTES) {
                return;
            }
            Path old = p.resolveSibling("ysp-live.log.1");
            Files.move(p, old, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 轮转失败无妨
        }
    }

    /** 立刻把队列刷盘（退出时调用）。 */
    static void flush() {
        if (!started) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = QUEUE.poll()) != null) {
            sb.append(line).append('\n');
        }
        if (sb.length() > 0) {
            writeBatch(sb.toString());
        }
        append("===== YspLive 退出 =====");
        String last;
        StringBuilder tail = new StringBuilder();
        while ((last = QUEUE.poll()) != null) {
            tail.append(last).append('\n');
        }
        if (tail.length() > 0) {
            writeBatch(tail.toString());
        }
    }
}
