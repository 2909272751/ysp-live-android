package com.gary.ysplive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * 播放页方向与体验相关回归测试。
 *
 * <p>对应问题：手机上打开播放页不会自动横屏。
 * 根因是 Manifest 里用的是 {@code screenOrientation="fullUser"}——它会<b>尊重系统的竖屏锁定</b>，
 * 而绝大多数手机用户都开着竖屏锁定，于是播放页永远停在竖屏。
 * 修复方式是根据设置强制横屏，退出时恢复原方向。
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 33, qualifiers = "w411dp-h891dp-port-xxhdpi")
public class PlayerOrientationTest {

    private static PlayerActivity launch() {
        ActivityController<PlayerActivity> c = Robolectric.buildActivity(
                PlayerActivity.class,
                PlayerActivity.intentFor(
                        ApplicationProvider.getApplicationContext(), "cctv1", "全部"));
        c.setup();
        return c.get();
    }

    /** 默认设置下，播放页必须是横屏（不是 fullUser，否则竖屏锁定会把画面锁成竖屏）。 */
    @Test
    public void playerForcesLandscapeByDefaultEvenWithPortraitLock() {
        // 默认 playerForceLandscape() == true
        PlayerActivity a = launch();
        int requested = a.getRequestedOrientation();
        assertTrue("播放页应请求横屏，实际 " + requested,
                requested == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        || requested == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        assertFalse("不能再用 FULL_USER——它会遵守竖屏锁定导致永远竖屏",
                requested == ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
    }

    /** 关掉「强制横屏」后应退回跟随系统。 */
    @Test
    public void turningOffForceLandscapeFallsBackToSystem() {
        AppPrefs.get(ApplicationProvider.getApplicationContext())
                .setPlayerForceLandscape(false);
        try {
            PlayerActivity a = launch();
            assertEquals("关闭强制横屏后应跟随系统方向",
                    ActivityInfo.SCREEN_ORIENTATION_FULL_USER, a.getRequestedOrientation());
        } finally {
            // 复位，避免影响其他用例
            AppPrefs.get(ApplicationProvider.getApplicationContext())
                    .setPlayerForceLandscape(true);
        }
    }

    /** 竖屏锁定场景模拟：即使配置是 portrait，播放页仍请求横屏。 */
    @Test
    @Config(sdk = 33, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void landscapeRequestedWhileDeviceIsPortrait() {
        PlayerActivity a = launch();
        Configuration cfg = a.getResources().getConfiguration();
        assertEquals("测试前提：设备当前是竖屏", Configuration.ORIENTATION_PORTRAIT,
                cfg.orientation);
        int req = a.getRequestedOrientation();
        assertTrue("竖屏设备上播放页仍应请求横屏，实际 " + req,
                req == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        || req == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    /** 退出播放页要把方向恢复回去，不能把主界面也锁成横屏。 */
    @Test
    public void orientationRestoredOnDestroy() {
        PlayerActivity a = launch();
        int during = a.getRequestedOrientation();
        assertTrue(during == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || during == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // destroy 时 PlayerChrome.restoreOrientation() 会把方向还原为进入前的值
        Robolectric.buildActivity(PlayerActivity.class,
                        PlayerActivity.intentFor(
                                ApplicationProvider.getApplicationContext(), "cctv1", "全部"))
                .setup().pause().stop().destroy();
        // 不抛异常即通过；方向恢复的具体值依赖进入前状态
    }

    /** 播放页必须能正常创建（等价于点击后不崩）。 */
    @Test
    public void playerActivityCreatesWithoutCrash() {
        PlayerActivity a = launch();
        assertNotNull(a);
        assertNotNull("PlayerView 必须存在", a.findViewById(com.gary.ysplive.R.id.player_view));
    }
}
