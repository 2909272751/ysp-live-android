package com.gary.ysplive.play;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.gary.ysplive.R;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.ui.MainActivity;

/**
 * 引擎前台服务。
 * <p>全进程唯一的 {@link Gateway} 实例归属这里：Activity 通过 {@link EngineBus} 观察状态，
 * 通过 {@link GatewayProvider#peek()} 取播放地址。通知栏常驻，展示引擎阶段与频道数。
 */
public final class EngineService extends Service {

    private static final String TAG = "service";

    public static final String ACTION_START = "com.gary.ysplive.action.START_ENGINE";
    public static final String ACTION_STOP = "com.gary.ysplive.action.STOP_ENGINE";
    public static final String ACTION_RESTART = "com.gary.ysplive.action.RESTART_ENGINE";

    private static final String CHANNEL_ID = "ysp_engine";
    private static final int NOTIFICATION_ID = 0x5950;

    /** 防止重复 post 同一个阶段名。 */
    private String lastStage = "";
    private Gateway gateway;
    private Handler main;
    private boolean startRequested;

    // ---------------- 静态入口 ----------------

    public static void start(Context ctx) {
        sendAction(ctx, ACTION_START);
    }

    public static void stop(Context ctx) {
        sendAction(ctx, ACTION_STOP);
    }

    public static void restart(Context ctx) {
        sendAction(ctx, ACTION_RESTART);
    }

    private static void sendAction(Context ctx, String action) {
        if (ctx == null) {
            return;
        }
        Intent intent = new Intent(ctx.getApplicationContext(), EngineService.class);
        intent.setAction(action);
        try {
            if (ACTION_START.equals(action)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.getApplicationContext().startForegroundService(intent);
            } else {
                ctx.getApplicationContext().startService(intent);
            }
        } catch (RuntimeException e) {
            // 后台限制（Android 12+ 从后台启动前台服务会抛异常）等情况：降级，不崩。
            Log.w(TAG, "启动引擎服务被拒绝：" + e);
        }
    }

    // ---------------- 生命周期 ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        createChannel();
        Log.i(TAG, "引擎服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEngine();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            Log.i(TAG, "收到重启请求");
            stopEngine();
        }

        // 无论如何都要在 5 秒内 startForeground，否则 O+ 会 ANR。
        startForegroundSafely(buildNotification("正在启动…", 0));

        if (!startRequested || ACTION_RESTART.equals(action)) {
            startRequested = true;
            startEngine();
        }
        // 被系统杀掉后自动重建，避免引擎消失导致播放全断。
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "引擎服务销毁");
        stopEngine();
        startRequested = false;
        EngineBus.publishStopped();
        super.onDestroy();
    }

    // ---------------- 引擎控制 ----------------

    private void startEngine() {
        EngineBus.publishStarting("正在启动引擎…");
        lastStage = "";

        gateway = GatewayProvider.get(this);
        if (gateway == null) {
            EngineBus.publishFailed("无法创建引擎实例");
            return;
        }

        try {
            gateway.start(new Gateway.StartListener() {
                @Override
                public void onStage(String stage) {
                    if (stage == null || stage.equals(lastStage)) {
                        return;
                    }
                    lastStage = stage;
                    Log.i(TAG, stage);
                    EngineBus.publishStarting(stage);
                    updateNotification(stage, 0);
                }

                @Override
                public void onReady(Gateway.Snapshot snapshot) {
                    int count = snapshot != null && snapshot.channelCount > 0
                            ? snapshot.channelCount : ChannelTable.all().size();
                    Log.i(TAG, "引擎就绪，端口 " + (snapshot == null ? 0 : snapshot.port)
                            + "，频道 " + count);
                    EngineBus.publishReady(snapshot, GatewayProvider.isUsingStub());
                    updateNotification("运行中", count);
                }

                @Override
                public void onFailed(String reason) {
                    EngineBus.publishFailed(reason == null ? "未知原因" : reason);
                    updateNotification("启动失败", 0);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "启动引擎异常：" + t);
            EngineBus.publishFailed("启动异常：" + t.getClass().getSimpleName());
        }
    }

    private void stopEngine() {
        Gateway gw = gateway != null ? gateway : GatewayProvider.peek();
        if (gw != null) {
            try {
                gw.stop();
                Log.i(TAG, "引擎已停止");
            } catch (Throwable t) {
                Log.w(TAG, "停止引擎异常：" + t);
            }
        }
        gateway = null;
    }

    // ---------------- 通知 ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "直播引擎", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("央视频直播的本地代理引擎运行状态");
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String status, int channels) {
        String title = "央视频直播 · " + status;
        StringBuilder text = new StringBuilder();
        if (channels > 0) {
            text.append(channels).append(" 频道 · ");
        }
        EngineBus.State st = EngineBus.state();
        text.append(st.ready ? ("http://127.0.0.1:" + st.port()) : "本地代理准备中");

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        builder.setContentTitle(title)
                .setContentText(text.toString())
                .setSmallIcon(R.drawable.ic_stat_live)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        PendingIntent content = contentIntent();
        if (content != null) {
            builder.setContentIntent(content);
        }
        return builder.build();
    }

    private PendingIntent contentIntent() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            return PendingIntent.getActivity(this, 0, intent, flags);
        } catch (RuntimeException e) {
            Log.w(TAG, "构造通知点击意图失败：" + e);
            return null;
        }
    }

    private void startForegroundSafely(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground 失败：" + e);
        }
    }

    private void updateNotification(final String status, final int channels) {
        if (main == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    NotificationManager nm =
                            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.notify(NOTIFICATION_ID, buildNotification(status, channels));
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "刷新通知失败：" + e);
                }
            }
        });
    }
}
