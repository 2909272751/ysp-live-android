package com.gary.ysplive.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页内联开关回归测试。
 *
 * <p>对应改动：布尔型设置从「点一下弹窗确认」改成「内联开关」。
 * 这类改动最容易出的问题是「开关只是好看，其实没写进配置」——
 * 所以这里不检查外观，而是断言<b>切换开关后配置真的变了</b>。
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 33, qualifiers = "zh-rCN-w411dp-h891dp-port-xxhdpi")
public class SettingsSwitchTest {

    private static SettingsActivity launch() {
        ActivityController<SettingsActivity> c =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        return c.get();
    }

    @Test
    public void settingsScreenHasInlineSwitches() {
        SettingsActivity a = launch();
        List<Switch> switches = new ArrayList<>();
        collectSwitches(a.findViewById(android.R.id.content), switches);
        assertNotNull(switches);
        assertTrue("设置页没有任何内联开关，布尔项没换成开关", switches.size() >= 3);
    }

    @Test
    public void togglingSwitchWritesPreference() {
        SettingsActivity a = launch();
        AppPrefs prefs = AppPrefs.get(ApplicationProvider.getApplicationContext());

        Switch target = findSwitchByRowTitle(a.findViewById(android.R.id.content), "播放页手势");
        assertNotNull("找不到「播放页手势」这一行的开关", target);

        boolean before = prefs.playerGestures();
        // 模拟用户点击开关（setChecked 会触发 OnCheckedChangeListener）
        target.setChecked(!before);
        boolean after = prefs.playerGestures();

        assertTrue("切换开关后配置没有变化——开关形同虚设（before=" + before + " after=" + after + "）",
                before != after);

        // 还原，避免影响其它测试
        target.setChecked(before);
    }

    // ---------------------------------------------------------------- 工具

    private static void collectSwitches(View v, List<Switch> out) {
        if (v instanceof Switch) {
            out.add((Switch) v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectSwitches(g.getChildAt(i), out);
            }
        }
    }

    /** 找到「标题为 title 的行」里的开关。做法：遍历所有开关，回到它所在的行，再在行内找标题。 */
    private static Switch findSwitchByRowTitle(View root, String title) {
        List<Switch> switches = new ArrayList<>();
        collectSwitches(root, switches);
        for (Switch sw : switches) {
            ViewGroup row = (ViewGroup) sw.getParent();   // row_root
            if (row == null) {
                continue;
            }
            if (title.contentEquals(rowTitle(row))) {
                return sw;
            }
        }
        return null;
    }

    /** 在一行内部递归找第一个「非空且不是开关文字」的 TextView 作为标题。 */
    private static CharSequence rowTitle(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            CharSequence t = firstNonEmptyText(row.getChildAt(i));
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private static CharSequence firstNonEmptyText(View v) {
        if (v instanceof Switch) {
            return null;   // 开关自己不带文字，跳过
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            return t == null || t.length() == 0 ? null : t;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                CharSequence t = firstNonEmptyText(g.getChildAt(i));
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static Switch firstSwitch(ViewGroup g) {
        List<Switch> list = new ArrayList<>();
        collectSwitches(g, list);
        return list.isEmpty() ? null : list.get(0);
    }
}
