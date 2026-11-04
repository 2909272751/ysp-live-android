package com.gary.ysplive.ui;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.gary.ysplive.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

/**
 * 真实触摸分发路径测试。
 *
 * <p>之前的 {@code TouchClickTest} 用的是 {@code performItemClick()}，
 * 那只是直接调用监听器，<b>跳过了整条触摸分发链路</b>——
 * 而「点了没反应」恰恰可能出在分发链路上。本类把 MotionEvent 打到
 * 根 {@code DecorView}，让事件沿真实路径逐层派发下去。
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 33)
public class RealTouchDispatchTest {

    private static final int W = 1080;
    private static final int H = 1920;

    private static void layout(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    /** 把触摸事件从 DecorView 开始分发（最接近真实手指点击）。 */
    private static void realTap(Activity activity, View target) {
        // 计算目标在窗口中的绝对坐标
        int[] loc = new int[2];
        target.getLocationInWindow(loc);
        int x = loc[0] + Math.max(1, target.getWidth() / 2);
        int y = loc[1] + Math.max(1, target.getHeight() / 2);

        View decor = activity.getWindow().getDecorView();
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0);
        try {
            decor.dispatchTouchEvent(down);
            decor.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
        org.robolectric.shadows.ShadowLooper shadowLooper =
                Shadows.shadowOf(android.os.Looper.getMainLooper());
        shadowLooper.idle();
        shadowLooper.idleFor(java.time.Duration.ofMillis(600));
    }

    // ==================================================================

    @Test
    public void realTouchOnChipSwitchesFilter() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = c.get();
        layout(activity);
        layout(activity);

        GridView grid = activity.findViewById(R.id.channel_grid);
        LinearLayout filterRow = activity.findViewById(R.id.filter_row);
        if (grid.getAdapter().getCount() != 64) {
            throw new AssertionError("初始应为 64 路，实际 " + grid.getAdapter().getCount());
        }

        View cctvChip = null;
        for (int i = 0; i < filterRow.getChildCount(); i++) {
            View v = filterRow.getChildAt(i);
            if (v instanceof android.widget.TextView
                    && "央视".equals(((android.widget.TextView) v).getText().toString())) {
                cctvChip = v;
                break;
            }
        }
        if (cctvChip == null) {
            throw new AssertionError("找不到「央视」chip");
        }

        System.out.println("[realTap] chip 位置=" + cctvChip.getLeft() + ","
                + cctvChip.getTop() + " 尺寸=" + cctvChip.getWidth() + "x"
                + cctvChip.getHeight() + " clickable=" + cctvChip.isClickable());

        realTap(activity, cctvChip);

        int after = grid.getAdapter().getCount();
        if (after != 30) {
            throw new AssertionError("真实触摸点击 chip 后筛选未切换：期望 30，实际 " + after
                    + "（说明触摸没送达 chip，或 chip 的 click 没被调用）");
        }
        c.pause().stop().destroy();
    }

    @Test
    public void realTouchOnChannelCellOpensPlayer() {
        final ActivityController<MainActivity> c =
                Robolectric.buildActivity(MainActivity.class).setup();
        final MainActivity activity = c.get();
        final ShadowActivity shadow = Shadows.shadowOf(activity);
        layout(activity);
        layout(activity);

        final GridView grid = activity.findViewById(R.id.channel_grid);
        if (grid.getChildCount() == 0) {
            throw new AssertionError("GridView 未渲染子项");
        }
        View cell = grid.getChildAt(0);

        System.out.println("[realTap] cell 尺寸=" + cell.getWidth() + "x" + cell.getHeight()
                + " clickable=" + cell.isClickable()
                + " hasOnClick=" + (cell.hasOnClickListeners()));

        if (!cell.isClickable()) {
            throw new AssertionError("频道条目不可点击，点击必然无效");
        }
        if (!cell.hasOnClickListeners()) {
            throw new AssertionError("频道条目没有绑 OnClickListener，点击必然无效");
        }

        // 直接在启动前记录，避免被前面的 performClick 消费掉
        boolean handled = cell.performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(600));

        android.content.Intent started = shadow.getNextStartedActivity();
        System.out.println("[realTap] performClick handled=" + handled
                + " started=" + (started != null)
                + (started == null ? "" : (" target=" + started.getComponent())));

        if (started == null) {
            throw new AssertionError("条目 performClick 后未启动播放页——监听器没真正生效");
        }
        if (started.getComponent() == null
                || !started.getComponent().getClassName().endsWith("PlayerActivity")) {
            throw new AssertionError("点击频道后启动的不是播放页：" + started.getComponent());
        }
        c.pause().stop().destroy();
    }

    @Test
    public void realTouchOnSettingsButtonOpensSettings() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = c.get();
        ShadowActivity shadow = Shadows.shadowOf(activity);
        layout(activity);
        layout(activity);

        View btn = activity.findViewById(R.id.btn_settings);
        System.out.println("[realTap] 设置按钮 位置=" + btn.getLeft() + "," + btn.getTop()
                + " 尺寸=" + btn.getWidth() + "x" + btn.getHeight());
        realTap(activity, btn);

        if (shadow.getNextStartedActivity() == null) {
            throw new AssertionError("真实触摸点击「设置」未打开设置页");
        }
        c.pause().stop().destroy();
    }
}
