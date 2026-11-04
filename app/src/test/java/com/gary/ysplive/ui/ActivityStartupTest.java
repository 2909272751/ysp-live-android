package com.gary.ysplive.ui;

import android.os.Bundle;
import android.view.View;

import com.gary.ysplive.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

/**
 * 这个测试只做一件事：把 Activity 真实创建出来，
 * 并且在 onCreate/onResume 阶段是否抛异常上给出证据。
 *
 * <p>如果这里抛异常，而 MainActivity 里 startActivity 又只有 try/catch，
 * 那么点击频道时就会「看起来什么都没发生」——这是比触摸事件更可能的真凶。
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 33)
public class ActivityStartupTest {

    @Test
    public void playerActivityCanBeCreated() {
        org.robolectric.android.controller.ActivityController<PlayerActivity> c =
                Robolectric.buildActivity(PlayerActivity.class,
                        PlayerActivity.intentFor(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                                "cctv1", "全部"));
        c.setup();   // onCreate + onStart + onResume
        PlayerActivity a = c.get();
        if (a == null) {
            throw new AssertionError("PlayerActivity 创建失败");
        }
        c.pause().stop().destroy();
    }

    @Test
    public void settingsActivityCanBeCreated() {
        org.robolectric.android.controller.ActivityController<SettingsActivity> c =
                Robolectric.buildActivity(SettingsActivity.class);
        c.setup();
        if (c.get() == null) {
            throw new AssertionError("SettingsActivity 创建失败");
        }
        c.pause().stop().destroy();
    }

    @Test
    public void logsActivityCanBeCreated() {
        org.robolectric.android.controller.ActivityController<LogsActivity> c =
                Robolectric.buildActivity(LogsActivity.class);
        c.setup();
        if (c.get() == null) {
            throw new AssertionError("LogsActivity 创建失败");
        }
        c.pause().stop().destroy();
    }

    @Test
    public void mainActivityRendersFilterChips() {
        org.robolectric.android.controller.ActivityController<MainActivity> c =
                Robolectric.buildActivity(MainActivity.class);
        c.setup();
        MainActivity a = c.get();
        View filterRow = a.findViewById(R.id.filter_row);
        if (filterRow == null) {
            throw new AssertionError("filter_row 为空");
        }
        if (((android.view.ViewGroup) filterRow).getChildCount() != 5) {
            throw new AssertionError("chip 数量不对："
                    + ((android.view.ViewGroup) filterRow).getChildCount());
        }
        c.pause().stop().destroy();
    }
}
