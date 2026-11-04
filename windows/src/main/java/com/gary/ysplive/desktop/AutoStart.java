package com.gary.ysplive.desktop;

import com.gary.ysplive.core.spi.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 开机自启（Windows 注册表 Run 键）。
 *
 * <p>写 {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}，
 * 只影响当前用户、不需要管理员权限。用户可在
 * 「任务管理器 → 启动」里自行关闭，卸载时由安装程序清理。
 *
 * <p><b>为什么不用 {@code reg add} 命令行（踩过的坑）：</b>
 * Java 的 {@code ProcessBuilder} 会按 CreateProcess 规则重新加引号，
 * 而 reg.exe 自己还要再解析一次引号，`/d` 里带引号的路径会被二次转义，
 * 结果报「无效语法」(exit=1)。实测含空格的路径必挂。
 *
 * <p><b>为什么不用 {@code java.util.prefs}：</b>
 * Preferences 只是把值存到 {@code HKCU\Software\JavaSoft\Prefs}，
 * 那是普通键、不是 Run 键，Windows 根本不会在登录时执行它——
 * 存进去等于没设置。
 *
 * <p>最终方案：生成临时 {@code .reg} 文件再 {@code reg import}。
 * 文件内容是精确文本，不经过任何命令行引号解析，路径里有什么字符都不会出错。
 */
final class AutoStart {

    private static final String TAG = "autostart";
    private static final String RUN_KEY =
            "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "YspLive";

    private AutoStart() {
    }

    /** 当前是否已设置开机自启（直接读注册表，不信本地缓存）。 */
    static boolean isEnabled() {
        try {
            String out = runCapture("reg", "query", RUN_KEY, "/v", VALUE_NAME);
            return out != null && out.contains(VALUE_NAME) && out.contains("REG_SZ");
        } catch (Throwable t) {
            Log.w(TAG, "读取自启状态失败: " + t);
            return false;
        }
    }

    /** 设置或取消开机自启。 */
    static boolean setEnabled(boolean enabled) {
        try {
            if (!enabled) {
                runCapture("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f");
                boolean off = !isEnabled();
                Log.i(TAG, off ? "已取消开机自启" : "取消开机自启失败");
                return off;
            }

            String cmd = buildCommand();
            if (cmd == null) {
                Log.w(TAG, "无法解析可执行文件路径，跳过自启设置");
                return false;
            }

            // .reg 文件格式：值里的反斜杠要写成双反斜杠，引号要转义成 \"
            String escaped = cmd.replace("\\", "\\\\").replace("\"", "\\\"");
            String regContent = "Windows Registry Editor Version 5.00\r\n\r\n"
                    + "[" + RUN_KEY + "]\r\n"
                    + "\"" + VALUE_NAME + "\"=\"" + escaped + "\"\r\n";

            Path tmp = Files.createTempFile("ysp-autostart-", ".reg");
            try {
                // reg import 需要 BOM 或 UTF-16LE 才能正确处理中文路径；
                // 用 UTF-16LE + BOM 最稳（这是 regedit 导出时的默认编码）
                byte[] bom = {(byte) 0xFF, (byte) 0xFE};
                byte[] body = regContent.getBytes(StandardCharsets.UTF_16LE);
                byte[] all = new byte[bom.length + body.length];
                System.arraycopy(bom, 0, all, 0, bom.length);
                System.arraycopy(body, 0, all, bom.length, body.length);
                Files.write(tmp, all);

                String out = runCapture("reg", "import", tmp.toAbsolutePath().toString());
                boolean ok = isEnabled();
                Log.i(TAG, (ok ? "已设置" : "设置失败") + " 开机自启: " + cmd
                        + (ok ? "" : "  reg输出=" + out));
                return ok;
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件删不掉无妨
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "设置开机自启失败: " + t);
            return false;
        }
    }

    /**
     * 构造启动命令行。
     * <p>安装版 code source 是 {@code <app>\app\YspLive-Desktop.jar}，
     * 真正的启动器是上一级的 {@code YspLive.exe}，需要还原；
     * 开发版直接跑 jar 时用 javaw -jar。
     */
    private static String buildCommand() {
        try {
            File self = new File(AutoStart.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (self.isFile() && self.getName().endsWith(".jar")) {
                File appDir = self.getParentFile();
                File base = appDir == null ? null : appDir.getParentFile();
                if (base != null) {
                    File exe = new File(base, "YspLive.exe");
                    if (exe.isFile()) {
                        return '"' + exe.getAbsolutePath() + '"';
                    }
                }
                File javaw = new File(System.getProperty("java.home"), "bin/javaw.exe");
                if (javaw.isFile()) {
                    return '"' + javaw.getAbsolutePath() + "\" -jar \"" + self.getAbsolutePath() + '"';
                }
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "解析可执行路径失败: " + t);
            return null;
        }
    }

    /** 执行命令并返回合并输出。 */
    private static String runCapture(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "(timeout)";
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "执行 " + cmd[0] + " 失败: " + t);
            return null;
        }
    }
}
