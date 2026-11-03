package com.gary.ysplive.play;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.ui.AppPrefs;

/**
 * 开机自启：只有在设置里打开了「开机自启」才拉起引擎前台服务。
 * <p>注意：Android 8.0 起后台启动前台服务受限，这里只做尽力而为，失败仅记录日志。
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) {
            return;
        }
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        AppPrefs prefs = AppPrefs.get(context);
        if (!prefs.bootAutoStart()) {
            Log.i(TAG, "收到开机广播，但未开启自启，跳过");
            return;
        }
        Log.i(TAG, "收到开机广播，正在启动引擎服务");
        // 提前初始化单例，避免 Application 尚未完全就绪时 Actvitiy 侧拿到不同实例。
        EngineBus.ensureStarted(context);
    }
}
