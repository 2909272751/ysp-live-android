package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.JSlider;

/**
 * 音量滑块回归测试。
 *
 * <p>对应改动：控制条上的音量从「−/+ 两个按钮，每次 ±5%」改成连续滑块。
 *
 * <p>这类改动最容易出的问题是「滑块能拖，但音量没变」——
 * 所以这里不检查外观，而是断言<b>改滑块的值之后，libVLC 的实际音量跟着变</b>。
 *
 * <p>滑块按取值范围区分：音量 0..130，进度条 0..1000。
 */
public final class VolumeSliderCheck {

    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        LogFile.start();
        DesktopConfig config = DesktopConfig.load();
        config.enginePort = 18995;
        config.playerVolume = 100;

        EngineController engine = new EngineController(config);
        CountDownLatch started = engine.startAsync(s -> { });
        started.await(60, TimeUnit.SECONDS);
        if (!engine.isRunning()) {
            System.out.println("engine failed");
            System.exit(1);
        }
        String origin = engine.localOrigin();
        ChannelSpec a = ChannelTable.bySlug("cctv1");
        VlcPlayer.Window w = VlcPlayer.open(origin + "/" + a.slug + ".m3u8", a,
                ChannelTable.all(), config, spec -> { }, () -> { });

        // 等音频输出建立（libVLC 的 aout 需要时间，见 applyAudioSettings 的注释）
        Thread.sleep(9000);

        JSlider vol = findVolumeSlider(w.frame.getContentPane());
        sb.append("=== 音量滑块 ===\n");
        sb.append("  找到滑块 = ").append(vol != null).append('\n');
        if (vol == null) {
            sb.append("\nRESULT: FAIL（控制条上没有音量滑块）\n");
            System.out.println(sb);
            w.release();
            System.exit(1);
        }
        sb.append("  取值范围 = ").append(vol.getMinimum())
                .append("..").append(vol.getMaximum()).append('\n');
        sb.append("  初始值   = ").append(vol.getValue()).append('\n');

        int before = w.player.audio().volume();
        sb.append("  VLC 当前音量 = ").append(before).append('\n');

        // 拖到 60
        final JSlider target = vol;
        javax.swing.SwingUtilities.invokeAndWait(() -> target.setValue(60));
        Thread.sleep(1500);
        int after = w.player.audio().volume();
        sb.append("\n=== 设为 60 之后 ===\n");
        sb.append("  滑块值 = ").append(vol.getValue()).append('\n');
        sb.append("  VLC 音量 = ").append(after).append('\n');
        boolean applied = Math.abs(after - 60) <= 2;
        sb.append("  音量已生效 = ").append(applied).append('\n');

        // 再拖到 20，验证双向
        javax.swing.SwingUtilities.invokeAndWait(() -> target.setValue(20));
        Thread.sleep(1500);
        int after2 = w.player.audio().volume();
        sb.append("\n=== 设为 20 之后 ===\n");
        sb.append("  VLC 音量 = ").append(after2).append('\n');
        boolean applied2 = Math.abs(after2 - 20) <= 2;
        sb.append("  音量已生效 = ").append(applied2).append('\n');

        boolean pass = applied && applied2;
        sb.append("\nRESULT: ").append(pass ? "PASS" : "FAIL").append('\n');
        System.out.println(sb);
        if (args.length > 0) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        w.release();
        engine.stop();
        LogFile.flush();
        System.exit(pass ? 0 : 1);
    }

    private static JSlider findVolumeSlider(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JSlider) {
                JSlider s = (JSlider) c;
                // 音量滑块上限 130，进度条上限 1000
                if (s.getMaximum() == 130) {
                    return s;
                }
            }
            if (c instanceof Container) {
                JSlider hit = findVolumeSlider((Container) c);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }
}
