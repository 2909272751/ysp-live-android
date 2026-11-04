package com.gary.ysplive.desktop;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 播放窗口：用一个内嵌的 Chromium（Edge / Chrome 的 {@code --app} 模式）承载
 * 自带 hls.js 的 HTML 播放页。
 *
 * <p><b>为什么不用纯 Java 播放器：</b>实测结论（都跑过，不是推测）：
 * <ul>
 *   <li>JavaFX {@code MediaPlayer}：不支持 HLS，12 秒内不出帧；</li>
 *   <li>JavaFX {@code WebView}：{@code MediaSource=false}、{@code SourceBuffer=false}，
 *       没有 MSE，hls.js 无法工作（报 levelLoadError）；</li>
 *   <li>Windows 自带 WebView2 Runtime（Chromium 156）与 Edge：
 *       {@code MSE=true}，hls.js 正常，实测能播出 CCTV-6 画面。</li>
 * </ul>
 * 所以走「本地 HTTP 服务托管播放页 + 系统 Chromium --app 打开」这条路：
 * 无额外依赖、无需安装 Native SDK、MSE/hls.js 全部可用，而且播放窗口与主界面解耦，
 * 全屏/画中画/硬件解码都由 Chromium 自己处理，比自己拼解码器稳得多。
 *
 * <p>播放页由 {@link LocalWebServer} 从内置资源提供，避免 {@code file://} 的
 * CORS/Origin 限制（实测 {@code file://} 下 hls.js 会 manifestLoadError）。
 */
final class PlayerLauncher {

    /** 播放进程句柄。 */
    static final class Session {
        final Process process;
        final String url;

        Session(Process process, String url) {
            this.process = process;
            this.url = url;
        }

        boolean isAlive() {
            return process != null && process.isAlive();
        }

        void close() {
            if (process == null) {
                return;
            }
            try {
                process.destroy();
                if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private static final String[][] CHROMIUM_CANDIDATES = {
            {"msedge.exe", "Microsoft Edge"},
            {"chrome.exe", "Google Chrome"},
    };

    private static final String[] CHROMIUM_DIRS = {
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    };

    private PlayerLauncher() {
    }

    /** 找一个可用的 Chromium 内核浏览器；找不到返回 null。 */
    static String findChromium() {
        for (String path : CHROMIUM_DIRS) {
            File f = new File(path);
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        // 退回到 PATH 查找
        for (String[] cand : CHROMIUM_CANDIDATES) {
            String hit = which(cand[0]);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static String which(String exe) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir == null || dir.trim().isEmpty()) {
                continue;
            }
            File f = new File(dir, exe);
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * 打开播放窗口。
     *
     * @param chromium 浏览器可执行文件路径
     * @param pageUrl  播放页 URL（由 {@link LocalWebServer} 提供）
     * @param titleBar 窗口标题
     */
    static Session open(String chromium, String pageUrl, String windowTitle,
                        boolean fullScreen, Path userDataDir) throws IOException {
        if (chromium == null) {
            throw new IOException("未找到可用的浏览器内核（Edge / Chrome）");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(chromium);
        cmd.add("--app=" + pageUrl);
        cmd.add("--autoplay-policy=no-user-gesture-required");
        cmd.add("--disable-features=CalculateNativeWinOcclusion");
        // 独立 user-data-dir，避免和用户日常浏览器会话冲突，也保证窗口尺寸可记忆
        if (userDataDir != null) {
            try {
                Files.createDirectories(userDataDir);
                cmd.add("--user-data-dir=" + userDataDir.toAbsolutePath());
            } catch (IOException ignored) {
                // 用默认目录也能跑
            }
        }
        if (fullScreen) {
            cmd.add("--start-fullscreen");
        } else {
            cmd.add("--window-size=1280,720");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        return new Session(p, pageUrl);
    }

    /** 把打包进 jar 的播放页资源释放到临时目录（供 LocalWebServer 读取）。 */
    static Path extractResource(String resource, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        Path out = targetDir.resolve(name);
        try (InputStream in = PlayerLauncher.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("资源不存在: " + resource);
            }
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    /** 读取打包资源为字符串。 */
    static String readResourceText(String resource) throws IOException {
        try (InputStream in = PlayerLauncher.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("资源不存在: " + resource);
            }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
