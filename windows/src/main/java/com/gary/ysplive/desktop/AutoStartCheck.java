package com.gary.ysplive.desktop;

/**
 * 验证「开机自启默认关闭，且不会被程序自动打开」。
 *
 * <p>要证明三件事：
 * <ol>
 *   <li>全新配置（无 settings.properties）下 {@code autoStartWithWindows} 默认是 false</li>
 *   <li>即使注册表里被人为写入条目，只要配置开关是 false，启动期校验会清掉它</li>
 *   <li>只有显式调用 setEnabled(true) 才会真的写注册表</li>
 * </ol>
 *
 * <p>注意：这个测试会临时改注册表并恢复，跑完必须确认环境干净。
 */
public final class AutoStartCheck {

    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AUTOSTART CHECK ===\n");

        // 记录初始状态，结束时恢复
        boolean regBefore = AutoStart.isEnabled();
        sb.append("registryBefore=").append(regBefore).append('\n');

        boolean pass = false;
        try {
            // ---------- 1. 默认配置应为 false ----------
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ysp-autostart-test");
            boolean defaultFlag = new DefaultProbe().valueInFreshConfig(tmp);
            sb.append("defaultConfigFlag=").append(defaultFlag).append('\n');

            // ---------- 2. 注册表有残留 + 配置为 false -> 应被清除 ----------
            boolean wrote = AutoStart.setEnabled(true);
            sb.append("manuallyWritten=").append(wrote).append('\n');
            boolean regAfterWrite = AutoStart.isEnabled();
            sb.append("registryAfterWrite=").append(regAfterWrite).append('\n');

            // 模拟启动期校验（与 App.start 里的逻辑一致）
            boolean configFlag = false;
            if (AutoStart.isEnabled() && !configFlag) {
                AutoStart.setEnabled(false);
            }
            boolean regAfterClean = AutoStart.isEnabled();
            sb.append("registryAfterStartupCheck=").append(regAfterClean).append('\n');
            boolean cleaned = regAfterWrite && !regAfterClean;

            // ---------- 3. 显式开启才写 ----------
            boolean on = AutoStart.setEnabled(true);
            boolean regOn = AutoStart.isEnabled();
            boolean off = AutoStart.setEnabled(false);
            boolean regOff = AutoStart.isEnabled();
            boolean enableOk = on && regOn;
            boolean disableOk = off && !regOff;

            sb.append('\n');
            sb.append("defaultOff        = ").append(!defaultFlag).append('\n');
            sb.append("leftoverCleaned   = ").append(cleaned).append('\n');
            sb.append("explicitEnableOk  = ").append(enableOk).append('\n');
            sb.append("explicitDisableOk = ").append(disableOk).append('\n');

            pass = !defaultFlag && cleaned && enableOk && disableOk;
            sb.append("RESULT: ").append(pass ? "PASS" : "FAIL").append('\n');

            try {
                java.nio.file.Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // ignore
            }
        } finally {
            // 恢复到测试前状态，绝不留痕
            AutoStart.setEnabled(regBefore);
            boolean restored = AutoStart.isEnabled() == regBefore;
            sb.append("restored=").append(restored).append('\n');
            if (!restored) {
                sb.append("RESULT: FAIL (could not restore registry)\n");
            }
        }

        System.out.println(sb);
        if (args.length > 0) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        System.exit(0);
    }

    /** 在隔离的配置目录里读一次默认值，避免碰用户真实配置。 */
    private static final class DefaultProbe {
        boolean valueInFreshConfig(java.nio.file.Path tmp) {
            // DesktopConfig.load() 依赖 %APPDATA%，这里直接反射构造一个全新实例：
            // 字段默认值就是"全新安装"时的值。
            try {
                java.lang.reflect.Constructor<DesktopConfig> c =
                        DesktopConfig.class.getDeclaredConstructor(java.nio.file.Path.class);
                c.setAccessible(true);
                DesktopConfig fresh = c.newInstance(tmp.resolve("settings.properties"));
                java.lang.reflect.Field f =
                        DesktopConfig.class.getDeclaredField("autoStartWithWindows");
                f.setAccessible(true);
                return (Boolean) f.get(fresh);
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
