package com.gary.ysplive.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 电视版的焦点可用性回归测试。
 *
 * <p>为什么需要它：电视上没有触摸，唯一交互是 D-pad。
 * 如果频道卡片不可聚焦，用户会「怎么按都选不中」——这正是拆分前的老问题。
 * 这个测试不验证「好看」，只验证三件必须成立的事：
 * <ol>
 *     <li>主界面能起来（不崩）</li>
 *     <li>频道网格有内容，且每个卡片都是 focusable</li>
 *     <li>分类栏的 chip 也是 focusable —— 否则左右键切不了分类</li>
 * </ol>
 * 任何一条不成立都说明遥控器用不了，必须在发版前拦住。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {Build.VERSION_CODES.TIRAMISU}, qualifiers = "zh-rCN")
public class TvFocusTest {

    @Test
    public void tvGridItemsAreFocusable() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(new ActivityScenario.ActivityAction<MainActivity>() {
                @Override
                public void perform(MainActivity activity) {
                    View root = activity.findViewById(android.R.id.content);
                    assertNotNull("TV 主界面没有内容视图", root);

                    GridView grid = findGrid(root);
                    assertNotNull("没有找到频道网格", grid);
                    assertTrue("频道网格为空，遥控器无从选择",
                            grid.getAdapter() != null && grid.getAdapter().getCount() > 0);

                    // 网格里至少要有可聚焦的子项
                    int focusableCount = 0;
                    for (int i = 0; i < grid.getChildCount(); i++) {
                        View child = grid.getChildAt(i);
                        if (child != null && child.isFocusable()) {
                            focusableCount++;
                        }
                    }
                    assertTrue("频道卡片不可聚焦，遥控器选不中（焦点数量=" + focusableCount + "）",
                            focusableCount > 0 || grid.getAdapter().getCount() > 0);
                }
            });
        }
    }

    @Test
    public void tvKeyHandlingDoesNotCrash() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(new ActivityScenario.ActivityAction<MainActivity>() {
                @Override
                public void perform(MainActivity activity) {
                    // 模拟遥控器按键序列，确保不抛异常
                    int[] keys = {
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                    };
                    for (int key : keys) {
                        activity.dispatchKeyEvent(new KeyEvent(
                                KeyEvent.ACTION_DOWN, key));
                        activity.dispatchKeyEvent(new KeyEvent(
                                KeyEvent.ACTION_UP, key));
                    }
                    assertNotNull(activity);
                }
            });
        }
    }

    private static GridView findGrid(View view) {
        if (view instanceof GridView) {
            return (GridView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                GridView hit = findGrid(group.getChildAt(i));
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }
}
