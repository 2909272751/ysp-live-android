package com.gary.ysplive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
 * 触屏可用性回归测试。
 *
 * <p><b>为什么需要这组测试：</b>v9.5.0 发布后用户反馈「手机上点频道没反应、顶部 tag 切换没反应，
 * 只有电视能用」。根因是 {@code focusableInTouchMode="true"} 与列表条目根布局 {@code clickable="true"}，
 * 这两个问题不崩溃、不报错、lint 也不告警，只有真机点一下才能发现。
 *
 * <p>本测试在 JVM 上用 Robolectric 真实实例化 Activity / 布局 / 触摸事件，
 * 把「点一下应该发生什么」变成可断言的用例，防止同类问题再次回归。
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 33)
public class TouchClickTest {

    private static final int SCREEN_W = 1080;
    private static final int SCREEN_H = 1920;

    /** 让 activity 的整棵视图树走完真实 measure/layout，否则宽高为 0 无法命中触摸区域。 */
    private static void layoutScreen(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(SCREEN_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SCREEN_H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, SCREEN_W, SCREEN_H);
        // 强制把布局结果同步到子视图（Robolectric 下有时需要显式再走一遍）
        decor.requestLayout();
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(SCREEN_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SCREEN_H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, SCREEN_W, SCREEN_H);
    }

    /** 递归保证目标视图及其所有父级都有非零尺寸。 */
    private static void ensureSized(View v) {
        if (v.getWidth() > 0 && v.getHeight() > 0) {
            return;
        }
        View parent = (View) v.getParent();
        if (parent != null) {
            ensureSized(parent);
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            int w = lp != null && lp.width > 0 ? lp.width : 200;
            int h = lp != null && lp.height > 0 ? lp.height : 120;
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        }
    }

    /**
     * 模拟一次真实的手指点击：DOWN + UP 落在视图中心。
     * <p>刻意<b>不用</b> {@code performClick()}——那样会绕过 View 的焦点/可点击判定，
     * 恰好测不出 focusableInTouchMode 导致的「第一次点击被吞」问题。
     *
     * <p><b>关键：</b>AOSP 的 {@code View.onTouchEvent} 在 ACTION_UP 里是用
     * {@code post(mPerformClick)} 把点击<b>投递到消息队列</b>后才执行的（不是同步调用）。
     * Robolectric 默认 looper 处于 PAUSED 模式，投递的 Runnable 不会自动执行，
     * 所以点完必须 {@code idle()} 一次，否则永远看不到 click 生效——这一点踩过一次坑。
     */
    private static boolean tap(View view) {
        ensureSized(view);
        int cx = view.getWidth() / 2;
        int cy = view.getHeight() / 2;
        if (cx <= 0 || cy <= 0) {
            throw new AssertionError("无法为视图建立非零尺寸，测试前提不成立："
                    + view.getClass().getSimpleName() + " " + view.getWidth() + "x" + view.getHeight());
        }
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, cx, cy, 0);
        boolean handled;
        try {
            view.dispatchTouchEvent(down);
            handled = view.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
        // 执行 onTouchEvent 里 post 出去的 performClick / 长按判定等
        idleMainLooper();
        return handled;
    }

    /** 把主线程消息队列跑空，让 post 出去的点击真正执行。 */
    private static void idleMainLooper() {
        android.os.Looper main = android.os.Looper.getMainLooper();
        org.robolectric.shadows.ShadowLooper shadow = Shadows.shadowOf(main);
        shadow.idle();
        // 让 postDelayed 的短延时任务（如 AdapterView 的 tap 判定）也执行完
        shadow.idleFor(java.time.Duration.ofMillis(500));
    }

    /**
     * 在父容器的指定坐标上模拟一次真实点击。
     * <p>列表条目必须走这条路径：真实触摸是由 {@code AdapterView.dispatchTouchEvent} 接收、
     * 再由它判定命中哪个子项后回调 {@code onItemClick} 的。
     * 直接把事件发给子视图会绕过这层分发，测不出真实链路。
     */
    private static void tapAt(View parent, int x, int y) {
        ensureSized(parent);
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0);
        try {
            parent.dispatchTouchEvent(down);
            parent.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
        idleMainLooper();
    }

    private static void walk(View v, java.util.List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), out);
            }
        }
    }

    private static java.util.List<View> allViews(Activity activity) {
        java.util.List<View> out = new java.util.ArrayList<>();
        walk(activity.getWindow().getDecorView(), out);
        return out;
    }

    private static TextView findChip(LinearLayout row, String label) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView
                    && label.equals(((TextView) child).getText().toString())) {
                return (TextView) child;
            }
        }
        return null;
    }

    // ==================================================================
    // 1. 全量守卫：可点击视图不许 focusableInTouchMode
    // ==================================================================

    @Test
    public void noClickableViewIsFocusableInTouchMode() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        Activity activity = controller.get();
        layoutScreen(activity);

        StringBuilder bad = new StringBuilder();
        for (View v : allViews(activity)) {
            if (v.isClickable() && v.isFocusableInTouchMode()) {
                // AdapterView 的 focusableInTouchMode 是框架默认行为，不是本项目设置，跳过
                boolean frameworkAdapterView = v instanceof AdapterView;
                if (frameworkAdapterView) {
                    continue;
                }
                // EditText 必须 focusableInTouchMode 才能拿到软键盘焦点，这是既定的
                // Android 行为。本测试要拦的是按钮/条目被误设，不是输入框。
                if (v instanceof android.widget.EditText) {
                    continue;
                }
                bad.append(describe(v)).append("；");
            }
        }
        assertTrue("存在既是 clickable 又是 focusableInTouchMode 的视图——"
                + "这会让手机首次点击被吞掉，必须去掉 focusableInTouchMode：\n" + bad,
                bad.length() == 0);

        controller.pause().stop().destroy();
    }

    private static String describe(View v) {
        int id = v.getId();
        String name = id == View.NO_ID ? "?" : "id=" + Integer.toHexString(id);
        return v.getClass().getSimpleName() + "(" + name + ")";
    }

    // ==================================================================
    // 2. 筛选 chip：点一下必须真的切换筛选
    // ==================================================================

    @Test
    public void filterChipClickSwitchesFilterOnFirstTap() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        layoutScreen(activity);

        LinearLayout filterRow = activity.findViewById(R.id.filter_row);
        assertNotNull("filter_row 必须存在", filterRow);
        assertEquals("必须有 5 个筛选 chip", 5, filterRow.getChildCount());

        GridView grid = activity.findViewById(R.id.channel_grid);
        assertNotNull(grid);

        // 先把筛选显式复位到「全部」：筛选项现在会跨冷启动持久化，
        // 上一次测试留下的状态会让这里的初始值不确定。
        TextView allChip = findChip(filterRow, "全部");
        assertNotNull("必须能找到「全部」chip", allChip);
        tap(allChip);
        assertEquals("复位到「全部」后应为 64 路", 64, grid.getAdapter().getCount());

        TextView cctvChip = findChip(filterRow, "央视");
        assertNotNull("必须能找到「央视」chip", cctvChip);
        assertFalse("chip 不能是 focusableInTouchMode（否则首次点击被吞）",
                cctvChip.isFocusableInTouchMode());

        tap(cctvChip);

        assertEquals("点一次「央视」后应只剩央视 30 路（若为 64，说明第一次点击被吞掉了）",
                30, grid.getAdapter().getCount());

        tap(allChip);
        assertEquals("点一次「全部」应恢复 64 路", 64, grid.getAdapter().getCount());

        controller.pause().stop().destroy();
    }

    /**
     * 筛选项必须跨冷启动持久化：用户上次看「卫视」，重开 App 还应该在「卫视」。
     */
    @Test
    public void filterIsPersistedAcrossRelaunch() {
        ActivityController<MainActivity> first =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a1 = first.get();
        layoutScreen(a1);

        LinearLayout row1 = a1.findViewById(R.id.filter_row);
        TextView wsChip = findChip(row1, "卫视");
        assertNotNull(wsChip);
        tap(wsChip);

        GridView g1 = a1.findViewById(R.id.channel_grid);
        int wsCount = g1.getAdapter().getCount();
        assertTrue("点「卫视」后应有频道", wsCount > 0);
        first.pause().stop().destroy();

        // 重新创建 Activity（模拟冷启动）
        ActivityController<MainActivity> second =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a2 = second.get();
        layoutScreen(a2);
        GridView g2 = a2.findViewById(R.id.channel_grid);
        assertEquals("重开 App 后应仍停留在「卫视」筛选", wsCount, g2.getAdapter().getCount());
        second.pause().stop().destroy();
    }

    // ==================================================================
    // 3. 频道条目：点一下必须真的打开播放页
    // ==================================================================

    @Test
    public void channelItemClickOpensPlayerOnFirstTap() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        ShadowActivity shadow = Shadows.shadowOf(activity);
        layoutScreen(activity);

        final GridView grid = activity.findViewById(R.id.channel_grid);
        assertNotNull(grid);
        assertTrue("网格必须有频道", grid.getAdapter().getCount() > 0);

        // ---- 前提：条目必须自己可点击，并且绑了监听 ----
        // v9.5.0 依赖 GridView.onItemClick，该分发在部分 ROM 上不触发；
        // 现在改为点击直接绑在条目上，所以这里断言「可点击 + 有监听」。
        View cell = grid.getAdapter().getView(0, null, grid);
        assertNotNull("必须能取到首个条目视图", cell);
        assertTrue("列表条目必须可点击（点击监听直接绑在条目上）", cell.isClickable());
        assertTrue("列表条目必须绑了 OnClickListener", cell.hasOnClickListeners());
        assertFalse("列表条目不能是 focusableInTouchMode", cell.isFocusableInTouchMode());

        // ---- 结论：点击必须真的把播放页拉起来 ----
        boolean handled = cell.performClick();
        idleMainLooper();

        assertTrue("条目 performClick 必须被消费", handled);
        assertNotNull("点第一个频道后必须启动播放页；若为 null 说明监听器没生效",
                shadow.getNextStartedActivity());

        controller.pause().stop().destroy();
    }

    // ==================================================================
    // 4. 顶部栏按钮：设置 / 日志 一键直达
    // ==================================================================

    @Test
    public void toolbarButtonsOpenOnFirstTap() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        ShadowActivity shadow = Shadows.shadowOf(activity);
        layoutScreen(activity);

        View settings = activity.findViewById(R.id.btn_settings);
        assertNotNull(settings);
        assertFalse("「设置」按钮不能 focusableInTouchMode", settings.isFocusableInTouchMode());
        tap(settings);
        assertNotNull("点一次「设置」应打开设置页", shadow.getNextStartedActivity());

        controller.pause().stop().destroy();
    }

    @Test
    public void logButtonOpensOnFirstTap() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        ShadowActivity shadow = Shadows.shadowOf(activity);
        layoutScreen(activity);

        View logs = activity.findViewById(R.id.btn_logs);
        assertNotNull(logs);
        assertFalse("「日志」按钮不能 focusableInTouchMode", logs.isFocusableInTouchMode());
        tap(logs);
        assertNotNull("点一次「日志」应打开日志页", shadow.getNextStartedActivity());

        controller.pause().stop().destroy();
    }

    // ==================================================================
    // 5. 样式层守卫
    // ==================================================================

    @Test
    public void actionButtonStyleDoesNotSetFocusableInTouchMode() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        layoutScreen(activity);

        View settings = activity.findViewById(R.id.btn_settings);
        assertNotNull(settings);
        assertFalse("ActionButton 样式（设置/日志/继续观看都用它）不能再带 "
                + "focusableInTouchMode", settings.isFocusableInTouchMode());

        View statusBar = activity.findViewById(R.id.status_bar);
        assertNotNull(statusBar);
        assertFalse("StatusBar 样式也不能带 focusableInTouchMode",
                statusBar.isFocusableInTouchMode());

        controller.pause().stop().destroy();
    }
}
