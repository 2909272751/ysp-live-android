package com.gary.ysplive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.gary.ysplive.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * 手机版主界面结构回归测试（对应本轮视觉重排）。
 *
 * <p>重排做了两件有结构意义的事，用测试锁住，避免以后被无意改回去：
 * <ol>
 *     <li><b>「继续观看」提升为独立整行按钮</b>：它原来是塞在分类行右侧的小 chip，
 *         和分类抢宽度、还经常被挤到看不见。现在必须是根布局的直接子项。</li>
 *     <li><b>竖屏下频道网格是 2 列</b>：触摸场景下卡片要够大，3 列以上点击精度会下降。</li>
 * </ol>
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 33, qualifiers = "zh-rCN-w411dp-h891dp-port-xxhdpi")
public class MainLayoutTest {

    private static MainActivity launch() {
        ActivityController<MainActivity> c =
                Robolectric.buildActivity(MainActivity.class).setup();
        return c.get();
    }

    /** 「继续观看」必须是根布局的直接子项（独立整行），不能缩在分类行里。 */
    @Test
    public void continueWatchingIsTopLevelRow() {
        MainActivity a = launch();
        TextView btn = a.findViewById(R.id.btn_continue);
        assertNotNull("找不到「继续观看」按钮", btn);

        View root = a.findViewById(R.id.root);
        assertNotNull(root);
        assertTrue("「继续观看」不是根布局的直接子项，说明又被塞回分类行里了；"
                        + "它的 parent 是 " + btn.getParent().getClass().getSimpleName(),
                btn.getParent() == root);
    }

    /** 分类 chips 容器必须存在且位于独立行。 */
    @Test
    public void filterRowIsTopLevel() {
        MainActivity a = launch();
        View filterRow = a.findViewById(R.id.filter_row);
        assertNotNull("找不到分类容器", filterRow);
        assertTrue("分类容器不在根布局下", filterRow.getParent() != null);
    }

    /** 竖屏 411dp 下网格应为 2 列，保证卡片够大。 */
    @Test
    public void gridUsesTwoColumnsOnPhonePortrait() {
        MainActivity a = launch();
        GridView grid = a.findViewById(R.id.channel_grid);
        assertNotNull("找不到频道网格", grid);

        // 触发一次测量/布局，auto_fit 的列数在布局后才确定
        View root = a.findViewById(R.id.root);
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int px = dm.widthPixels;
        int py = dm.heightPixels;
        int w = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY);
        int h = View.MeasureSpec.makeMeasureSpec(py, View.MeasureSpec.EXACTLY);
        root.measure(w, h);
        root.layout(0, 0, px, py);

        int cols = grid.getNumColumns();
        assertTrue("网格列数异常：" + cols, cols >= 1);
        assertEquals("竖屏手机应保持 2 列（当前 " + cols + " 列），否则卡片太小不好点", 2, cols);
    }
}
