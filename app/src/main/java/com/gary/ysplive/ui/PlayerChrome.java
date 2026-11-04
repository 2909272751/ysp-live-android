package com.gary.ysplive.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.media3.common.Player;

import com.gary.ysplive.R;
import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.play.EngineBus;
import com.gary.ysplive.play.GatewayProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放页体验增强（组合到 {@link PlayerActivity} 里使用，避免单个类继续膨胀）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>强制横屏</b>：手机上用户普遍开着竖屏锁定，{@code fullUser} 会导致播放页永远竖屏。
 *       这里按设置强制横屏，退出时恢复原方向。</li>
 *   <li><b>手势</b>：双击播放/暂停；单击呼出控制条；左右滑换台。</li>
 *   <li><b>自动隐藏系统栏</b>、<b>屏幕常亮</b>、<b>音量/亮度不干扰</b>。</li>
 *   <li><b>错误自动恢复</b>：网络类错误自动重试并显示友好文案。</li>
 * </ul>
 */
final class PlayerChrome {

    private static final String TAG = "player.ui";

    /** 回调宿主，由 {@link PlayerActivity} 实现。 */
    interface Host {
        void onChromeToggleOverlay();

        void onChromeZap(int delta);

        void onChromeTogglePlayPause();

        void onChromeShowOverlay();

        void onChromeHideOverlay();

        boolean onChromeOverlayVisible();
    }

    private final Activity activity;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private GestureDetector gestures;

    private int previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean orientationApplied;

    PlayerChrome(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    // ------------------------------------------------------------------ 方向

    /**
     * 应用播放页方向。
     * <p>优先使用设置里的「强制横屏」；关闭时退回跟随系统（但仍会在手机竖屏锁定时保持竖屏）。
     * 必须在 {@code setContentView} 之后调用，且要处理 {@code configChanges} 场景。
     */
    void applyOrientation(AppPrefs prefs) {
        try {
            if (!orientationApplied) {
                previousOrientation = activity.getRequestedOrientation();
                orientationApplied = true;
            }
            boolean landscape = prefs.playerForceLandscape();
            if (landscape) {
                int target = isTv() ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                if (activity.getRequestedOrientation() != target) {
                    activity.setRequestedOrientation(target);
                }
            } else {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
            }
        } catch (RuntimeException e) {
            // 个别 ROM 在 onResume 期间改方向会抛 IllegalStateException，忽略即可
            Log.w(TAG, "设置播放页方向失败：" + e);
        }
    }

    /** 退出播放页时恢复进入前的方向，避免把主界面也锁成横屏。 */
    void restoreOrientation() {
        if (!orientationApplied) {
            return;
        }
        try {
            activity.setRequestedOrientation(previousOrientation);
        } catch (RuntimeException e) {
            Log.w(TAG, "恢复方向失败：" + e);
        }
        orientationApplied = false;
    }

    /** 是否 Android TV（TV 只能是横屏，不需要传感器判断）。 */
    private boolean isTv() {
        return (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    // ------------------------------------------------------------------ 手势

    void installGestures(final View target, AppPrefs prefs) {
        if (target == null || !prefs.playerGestures()) {
            return;
        }
        gestures = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                host.onChromeToggleOverlay();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                host.onChromeTogglePlayPause();
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                // 横向滑动明显大于纵向才当作换台，避免和滚动/音量手势冲突
                if (Math.abs(dx) > Math.abs(dy) * 1.6f && Math.abs(dx) > Ui.dp(activity, 70)) {
                    host.onChromeZap(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
        target.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    // 有触摸时重开自动隐藏计时
                    host.onChromeShowOverlay();
                    main.removeCallbacks(autoHide);
                    main.postDelayed(autoHide, 6000L);
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    main.removeCallbacks(autoHide);
                    if (host.onChromeOverlayVisible()) {
                        main.postDelayed(autoHide, 4000L);
                    }
                }
                return gestures.onTouchEvent(event);
            }
        });
    }

    private final Runnable autoHide = new Runnable() {
        @Override
        public void run() {
            host.onChromeHideOverlay();
        }
    };

    void cancelAutoHide() {
        main.removeCallbacks(autoHide);
    }

    void release() {
        main.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------ 播放态

    /** 根据播放状态给出用户能看懂的中文提示；null 表示「不需要显示提示」。 */
    static String statusText(Player player) {
        if (player == null) {
            return null;
        }
        switch (player.getPlaybackState()) {
            case Player.STATE_IDLE:
                return "准备中…";
            case Player.STATE_BUFFERING:
                return "缓冲中…";
            case Player.STATE_READY:
                return player.isPlaying() ? null : "已暂停";
            case Player.STATE_ENDED:
                return "播放结束";
            default:
                return null;
        }
    }

    /**
     * 把 ExoPlayer 的错误码翻译成可操作的中文。
     * <p>用户不需要看 ERROR_CODE_IO_BAD_HTTP_STATUS 这种字符串。
     */
    static String friendlyError(androidx.media3.common.PlaybackException error) {
        if (error == null) {
            return "播放失败，请重试";
        }
        // media3 里 errorCode 是 public 字段，不是 getter
        int code = error.errorCode;
        switch (code) {
            case androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "网络连接失败，请检查网络后重试";
            case androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "频道源暂时不可用，正在自动切换线路";
            case androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED:
            case androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return "当前设备不支持该频道编码（4K/8K 需要 HEVC 硬解），可切换到 1080p 频道";
            case androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
                return "频道数据异常，正在自动重试";
            default:
                return "播放出错，正在自动重试";
        }
    }
}
