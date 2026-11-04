package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 验证断流自动重连真的生效。
 *
 * <p>测试手法：起播放后，人为把播放器切到一个坏地址（模拟上游断流），
 * 观察它是否会自动重连回原地址并恢复播放。
 * 这不是模拟——是真的让 libVLC 报 error，走真实的重连路径。
 */
public final class ReconnectCheck {

    public static void main(String[] args) throws Exception {
        LogFile.start();
        DesktopConfig config = DesktopConfig.load();
        config.enginePort = 18962;

        EngineController engine = new EngineController(config);
        CountDownLatch started = engine.startAsync(s -> { });
        started.await(60, TimeUnit.SECONDS);
        if (!engine.isRunning()) {
            System.out.println("engine failed");
            System.exit(1);
        }
        String origin = engine.localOrigin();
        ChannelSpec a = ChannelTable.bySlug("cctv1");
        String goodMrl = origin + "/" + a.slug + ".m3u8";

        VlcPlayer.Window w = VlcPlayer.open(goodMrl, a, ChannelTable.all(), config,
                spec -> { }, () -> { });

        Thread.sleep(7000);
        System.out.println("=== 初始状态 ===");
        System.out.println("  state = " + w.player.status().state());
        boolean initialOk = w.player.status().isPlaying();

        // 用一个不存在的地址触发 error（真实走 media().play -> VLC error 回调）
        System.out.println();
        System.out.println("=== 制造断流：切到无效地址 ===");
        w.player.media().play("http://127.0.0.1:1/dead.m3u8");
        Thread.sleep(4000);
        System.out.println("  state after break = " + w.player.status().state());

        // 等重连（退避 1s，加上起流时间，给 20 秒宽松窗口）
        System.out.println();
        System.out.println("=== 等待自动重连（最多 20s）===");
        boolean recovered = false;
        for (int i = 0; i < 40; i++) {
            Thread.sleep(500);
            if (w.player.status().isPlaying()) {
                recovered = true;
                System.out.println("  第 " + (i / 2) + " 秒恢复播放");
                break;
            }
        }

        System.out.println();
        System.out.println("  final state = " + w.player.status().state());
        System.out.println("  initialOk   = " + initialOk);
        System.out.println("  recovered   = " + recovered);
        String verdict = (initialOk && recovered) ? "PASS" : "FAIL";
        System.out.println("RESULT: " + verdict);

        w.release();
        engine.stop();
        LogFile.flush();
        System.exit("PASS".equals(verdict) ? 0 : 1);
    }
}
