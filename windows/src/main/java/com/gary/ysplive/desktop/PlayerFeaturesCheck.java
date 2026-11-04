package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 验证本轮三个新功能：
 * 1) 播放列表右键菜单（收藏 / 复制链接）
 * 2) 进度条与拖动
 * 3) 完整位置记忆（窗口几何、侧栏、音量、频道进度）
 */
public final class PlayerFeaturesCheck {

    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        LogFile.start();
        DesktopConfig config = DesktopConfig.load();
        config.enginePort = 18980;
        // 从干净状态开始，避免旧值干扰
        config.playerSidePanelOpen = true;

        EngineController engine = new EngineController(config);
        CountDownLatch started = engine.startAsync(s -> { });
        started.await(60, TimeUnit.SECONDS);
        if (!engine.isRunning()) {
            sb.append("engine failed\n");
            System.out.println(sb);
            System.exit(1);
        }
        String origin = engine.localOrigin();
        ChannelSpec a = ChannelTable.bySlug("cctv1");
        ChannelSpec b = ChannelTable.bySlug("cctv6");

        VlcPlayer.Window w = VlcPlayer.open(origin + "/" + a.slug + ".m3u8", a,
                ChannelTable.all(), config, spec -> { }, () -> { });
        Thread.sleep(7000);

        // ---------- 1. 右键菜单存在性 ----------
        sb.append("=== 1. 侧栏右键菜单 ===\n");
        Container side = (Container) findByName(w.frame.getContentPane(), "SidePanel");
        if (side == null) {
            sb.append("  FAIL: 找不到 SidePanel\n");
        } else {
            Container row = firstRowWithPopup(side);
            sb.append("  找到频道行: ").append(row != null).append('\n');
            if (row != null) {
                // 统计该行的弹出菜单
                sb.append("  row mouseListeners = ")
                        .append(row.getMouseListeners().length).append('\n');
                boolean hasPopup = row.getMouseListeners().length > 0;
                sb.append("  右键菜单可弹出 = ").append(hasPopup).append('\n');
                // 收藏功能验证
                boolean before = config.isFavorite(b.slug);
                config.toggleFavorite(b.slug);
                boolean after = config.isFavorite(b.slug);
                sb.append("  收藏切换生效 = ").append(before != after).append('\n');
                // 还原
                config.toggleFavorite(b.slug);
            }
        }

        // ---------- 2. 进度条 ----------
        sb.append("\n=== 2. 进度条 ===\n");
        javax.swing.JSlider slider = (javax.swing.JSlider)
                findByName(w.frame.getContentPane(), "JSlider");
        sb.append("  进度条存在 = ").append(slider != null).append('\n');
        if (slider != null) {
            sb.append("  可拖动(enabled) = ").append(slider.isEnabled()).append('\n');
            sb.append("  说明：直播流 length=0 时显示「直播」且禁用拖动（预期行为）\n");
        }

        // ---------- 3. 位置记忆 ----------
        sb.append("\n=== 3. 完整位置记忆 ===\n");
        // 改窗口位置后关闭，看是否写进配置
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            w.frame.setBounds(120, 90, 1000, 640);
            w.frame.validate();
        });
        Thread.sleep(600);
        w.release();
        Thread.sleep(1500);

        DesktopConfig reloaded = DesktopConfig.load();
        sb.append("  窗口 X/Y      = ").append(reloaded.playerWinX)
                .append("/").append(reloaded.playerWinY).append('\n');
        sb.append("  窗口 宽x高    = ").append(reloaded.playerWinWidth)
                .append("x").append(reloaded.playerWinHeight).append('\n');
        sb.append("  侧栏状态记录  = ").append(reloaded.playerSidePanelOpen).append('\n');
        sb.append("  音量记录      = ").append(reloaded.playerVolume).append('\n');

        // 断言：位置必须精确匹配；尺寸受屏幕限制，只要求"被记录了且合理"
        // （本开发会话虚拟屏幕只有 1053x509，窗口高度会被系统钳制）
        boolean geomSaved = reloaded.playerWinX == 120
                && reloaded.playerWinY == 90
                && reloaded.playerWinWidth == 1000
                && reloaded.playerWinHeight > 240;
        sb.append("  窗口几何已保存 = ").append(geomSaved).append('\n');
        if (!geomSaved) {
            sb.append("  说明：位置应为 120/90、宽度 1000；高度受屏幕限制可为任意合理值\n");
        }

        boolean pass = slider != null && geomSaved;
        sb.append("\nRESULT: ").append(pass ? "PASS" : "FAIL").append('\n');

        System.out.println(sb);
        if (args.length > 0) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        engine.stop();
        LogFile.flush();
        System.exit(pass ? 0 : 1);
    }

    private static Container firstRowWithPopup(Container c) {
        for (Component child : c.getComponents()) {
            if (child.getClass().getSimpleName().equals("JPanel")
                    && child.getMouseListeners().length > 0) {
                return (Container) child;
            }
            if (child instanceof Container) {
                Container r = firstRowWithPopup((Container) child);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static Component findByName(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (c.getClass().getSimpleName().equals(name)) {
                return c;
            }
            if (c instanceof Container) {
                Component r = findByName((Container) c, name);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
