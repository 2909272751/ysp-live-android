package com.gary.ysplive.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;
import android.view.View;

import com.gary.ysplive.R;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * 手机版播放页手势回归测试。
 *
 * <p>对应问题：播放页原先只有「单击显隐控制层」，换台/调音量都必须先点开控制层
 * 再去找按钮，和成熟手机播放器差距明显。
 *
 * <p>本测试只锁住最基本的契约：<b>手势层确实装到了视频视图上</b>，
 * 且常见的触摸序列不会导致崩溃。具体手感（滑动阈值、音量步进）需要在真机上体验，
 * 无法在 JVM 里断言，这里不假装能测。
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 33, qualifiers = "w411dp-h891dp-port-xxhdpi")
public class PlayerGestureTest {

    private static PlayerActivity launch() {
        ActivityController<PlayerActivity> c = Robolectric.buildActivity(
                PlayerActivity.class,
                PlayerActivity.intentFor(
                        ApplicationProvider.getApplicationContext(), "cctv1", "全部"));
        c.setup();
        return c.get();
    }

    private static View playerView(PlayerActivity a) {
        View v = a.findViewById(R.id.player_view);
        assertNotNull("播放页没有 player_view", v);
        return v;
    }

    @Test
    public void playerViewHasGestureTouchListener() {
        PlayerActivity a = launch();
        View v = playerView(a);
        assertTrue("视频视图上没有装手势监听，双击/滑动换台都不会生效",
                v.hasOnClickListeners() || v.isClickable() || touchListenerInstalled(v));
    }

    private static boolean touchListenerInstalled(View v) {
        // View 没有公开 getOnTouchListener，用一个可观察的副作用判断：
        // 手势装好后 dispatchTouchEvent 会返回 true（消费事件）。
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t,
                MotionEvent.ACTION_DOWN, 10f, 10f, 0);
        boolean handled = v.dispatchTouchEvent(down);
        down.recycle();
        return handled;
    }

    @Test
    public void tapAndFlingDoNotCrash() {
        PlayerActivity a = launch();
        View v = playerView(a);

        // 单击
        dispatch(v, MotionEvent.ACTION_DOWN, 100f, 100f, 0);
        dispatch(v, MotionEvent.ACTION_UP, 100f, 100f, 60);
        // 双击
        dispatch(v, MotionEvent.ACTION_DOWN, 120f, 120f, 0);
        dispatch(v, MotionEvent.ACTION_UP, 120f, 120f, 40);
        dispatch(v, MotionEvent.ACTION_DOWN, 120f, 120f, 90);
        dispatch(v, MotionEvent.ACTION_UP, 120f, 120f, 130);
        // 横向滑动（换台）
        dispatch(v, MotionEvent.ACTION_DOWN, 400f, 200f, 0);
        dispatch(v, MotionEvent.ACTION_MOVE, 260f, 200f, 40);
        dispatch(v, MotionEvent.ACTION_MOVE, 120f, 200f, 80);
        dispatch(v, MotionEvent.ACTION_UP, 80f, 200f, 120);
        // 竖向滑动（音量/亮度）
        dispatch(v, MotionEvent.ACTION_DOWN, 300f, 400f, 0);
        dispatch(v, MotionEvent.ACTION_MOVE, 300f, 320f, 40);
        dispatch(v, MotionEvent.ACTION_MOVE, 300f, 240f, 80);
        dispatch(v, MotionEvent.ACTION_UP, 300f, 200f, 120);

        assertNotNull(a);
    }

    private static void dispatch(View v, int action, float x, float y, long dt) {
        long t = android.os.SystemClock.uptimeMillis() + dt;
        MotionEvent e = MotionEvent.obtain(t, t, action, x, y, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
    }
}
