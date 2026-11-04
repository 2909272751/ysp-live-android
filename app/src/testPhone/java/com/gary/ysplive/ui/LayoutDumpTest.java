package com.gary.ysplive.ui;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.gary.ysplive.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

/**
 * 把主界面真实布局出来的视图树几何 dump 出来，用于排查「点击没反应」。
 * <p>排查要点：是否有尺寸非零、可见、且覆盖整屏的可点击视图挡在频道网格/筛选栏上面。
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 33)
public class LayoutDumpTest {

    private static final int W = 1080;
    private static final int H = 1920;

    @Test
    public void dumpMainHierarchy() {
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);

        System.out.println("===== MAIN VIEW TREE =====");
        print(decor, 0);

        GridView grid = activity.findViewById(R.id.channel_grid);
        LinearLayout filterRow = activity.findViewById(R.id.filter_row);
        System.out.println("grid: vis=" + grid.getVisibility() + " size=" + grid.getWidth()
                + "x" + grid.getHeight() + " children=" + grid.getChildCount()
                + " adapterCount=" + grid.getAdapter().getCount());
        System.out.println("filterRow: vis=" + filterRow.getVisibility()
                + " size=" + filterRow.getWidth() + "x" + filterRow.getHeight()
                + " children=" + filterRow.getChildCount());
        for (int i = 0; i < filterRow.getChildCount(); i++) {
            View c = filterRow.getChildAt(i);
            System.out.println("  chip[" + i + "] text=" + ((android.widget.TextView) c).getText()
                    + " size=" + c.getWidth() + "x" + c.getHeight()
                    + " clickable=" + c.isClickable() + " vis=" + c.getVisibility());
        }
    }

    /**
     * 关键回归测试：每个 chip 必须真的有足够大的可点击区域。
     *
     * <p>排查 v9.5.0「点 tag 没反应」时 dump 出真实几何发现：5 个 chip 全被压成
     * <b>34px 宽</b>（1080px 屏上），文字根本放不下，点击热区极小且互相重叠。
     * 根因是 HorizontalScrollView 把 WRAP_CONTENT 子视图压到了近 0。
     *
     * <p>这条测试断言「宽度必须 ≥ 56dp、高度必须 ≥ 36dp」，把这类
     * 「静默变形导致点不动」的问题钉死。
     */
    @Test
    public void filterChipsHaveUsableTouchTargets() {
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);

        LinearLayout filterRow = activity.findViewById(R.id.filter_row);
        int minW = Ui.dp(activity, 56);
        int minH = Ui.dp(activity, 36);
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < filterRow.getChildCount(); i++) {
            View c = filterRow.getChildAt(i);
            String text = ((android.widget.TextView) c).getText().toString();
            if (c.getWidth() < minW || c.getHeight() < minH) {
                bad.append(text).append("=").append(c.getWidth()).append('x')
                        .append(c.getHeight()).append("(需≥").append(minW).append('x')
                        .append(minH).append(") ");
            }
        }
        if (bad.length() > 0) {
            throw new AssertionError("筛选项可点击区域过小，会导致手机上点不动：" + bad);
        }
    }

    /**
     * 频道条目必须也有足够大的触摸目标，并且不能被任何覆盖层挡住。
     */
    @Test
    public void channelGridCellsAreLargeEnoughToTap() {
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);

        GridView grid = activity.findViewById(R.id.channel_grid);
        if (grid.getChildCount() == 0) {
            throw new AssertionError("GridView 没有渲染出任何条目，无法点击");
        }
        View cell = grid.getChildAt(0);
        Rect r = new Rect();
        cell.getHitRect(r);
        if (r.width() < 100 || r.height() < 80) {
            throw new AssertionError("频道条目点击区域过小：" + r.width() + "x" + r.height());
        }
        // 条目内不能有 CLICKABLE 子视图吃掉事件
        StringBuilder bad = new StringBuilder();
        scanClickable(cell, cell, bad);
        if (bad.length() > 0) {
            throw new AssertionError("频道条目内有可点击子视图会截获触摸：" + bad);
        }
    }

    private static void scanClickable(View root, View v, StringBuilder bad) {
        if (v != root && v.isClickable()) {
            bad.append(v.getClass().getSimpleName()).append(";");
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                scanClickable(root, g.getChildAt(i), bad);
            }
        }
    }

    private static void print(View v, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        int id = v.getId();
        String idName = "?";
        if (id != View.NO_ID) {
            try {
                idName = v.getResources().getResourceEntryName(id);
            } catch (RuntimeException e) {
                idName = "0x" + Integer.toHexString(id);
            }
        }
        sb.append(v.getClass().getSimpleName())
                .append(" #").append(idName)
                .append(" ").append(v.getLeft()).append(',').append(v.getTop())
                .append(" ").append(v.getWidth()).append('x').append(v.getHeight())
                .append(" vis=").append(v.getVisibility())
                .append(v.isClickable() ? " CLICKABLE" : "")
                .append(v.isFocusable() ? " focusable" : "")
                .append(v.isFocusableInTouchMode() ? " fitm" : "");
        System.out.println(sb);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                print(g.getChildAt(i), depth + 1);
            }
        }
    }
}
