package com.gary.ysplive.play;

import android.content.Context;

import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.ui.AppPrefs;

import java.io.File;

/**
 * 全局 {@link Gateway} 单例工厂。
 * <p>真实的引擎实现 {@code com.gary.ysplive.core.engine.YspGateway} 由 core 组并行开发，
 * 本类通过反射按约定的构造函数 {@code (Gateway.Config)} 创建它；若类不存在或构造失败，
 * 就退回到 {@link StubGateway}，保证 UI 永远能跑起来。
 * <p>反射只出现在这一个类里，符合项目约定。
 */
public final class GatewayProvider {

    private static final String TAG = "gateway";

    /** 约定的引擎实现类名（不要在此之外散落第二处反射）。 */
    private static final String ENGINE_CLASS = "com.gary.ysplive.core.engine.YspGateway";

    private static final Object LOCK = new Object();

    private static Gateway instance;
    private static boolean usingStub;
    private static String stubReason = "";

    private GatewayProvider() {
    }

    /** 当前引擎实例；未创建时返回 null（不会触发创建）。 */
    public static Gateway peek() {
        synchronized (LOCK) {
            return instance;
        }
    }

    /** 是否正在用占位引擎（诊断页会显示）。 */
    public static boolean isUsingStub() {
        synchronized (LOCK) {
            return usingStub;
        }
    }

    /** 占位原因，真实引擎可用时为空串。 */
    public static String stubReason() {
        synchronized (LOCK) {
            return stubReason;
        }
    }

    /**
     * 取当前引擎，没有就按用户设置创建。可重复调用，返回同一实例。
     */
    public static Gateway get(Context ctx) {
        synchronized (LOCK) {
            if (instance == null) {
                instance = create(ctx.getApplicationContext());
            }
            return instance;
        }
    }

    /**
     * 丢弃当前实例（用于「重启引擎」）。调用前请先 {@link Gateway#stop()}。
     */
    public static void reset() {
        synchronized (LOCK) {
            if (instance != null) {
                try {
                    instance.stop();
                } catch (Throwable t) {
                    Log.w(TAG, "停止旧引擎失败：" + t);
                }
            }
            instance = null;
            usingStub = false;
            stubReason = "";
        }
    }

    private static Gateway create(Context ctx) {
        AppPrefs prefs = AppPrefs.get(ctx);
        Gateway.Config cfg = buildConfig(ctx, prefs);

        Gateway real = tryCreateReal(cfg);
        if (real != null) {
            usingStub = false;
            stubReason = "";
            Log.i(TAG, "已加载真实引擎 " + ENGINE_CLASS + "，端口 " + cfg.port);
            return real;
        }

        usingStub = true;
        if (stubReason.isEmpty()) {
            stubReason = "未找到 " + ENGINE_CLASS;
        }
        Log.w(TAG, "使用占位引擎（" + stubReason + "）");
        return new StubGateway(cfg);
    }

    /** 按用户设置组装引擎配置。 */
    public static Gateway.Config buildConfig(Context ctx, AppPrefs prefs) {
        Gateway.Config cfg = new Gateway.Config();
        cfg.port = prefs.port();
        cfg.allowLan = prefs.allowLan();
        cfg.cacheMb = prefs.cacheMb();
        cfg.linksPerDevice = prefs.linksPerDevice();
        cfg.zapDebounceSec = prefs.zapDebounceSec();
        cfg.disableHighBitrate = prefs.disableHighBitrate();
        cfg.only4kHighBitrate = prefs.only4kHighBitrate();
        cfg.dataDir = dataDir(ctx);
        cfg.timeoutSec = 10.0;
        return cfg;
    }

    /** 引擎的持久化目录；优先外部私有目录，不可用时退回内部目录。 */
    public static File dataDir(Context ctx) {
        File dir = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            File base = ctx.getExternalFilesDir("engine");
            if (base != null) {
                dir = base;
            }
        }
        if (dir == null) {
            dir = ctx.getDir("engine", Context.MODE_PRIVATE);
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建引擎数据目录：" + dir);
        }
        return dir;
    }

    /**
     * 反射创建真实引擎；任何一步失败都返回 null 并记录原因，绝不让异常冒泡。
     */
    private static Gateway tryCreateReal(Gateway.Config cfg) {
        Class<?> cls;
        try {
            cls = Class.forName(ENGINE_CLASS);
        } catch (Throwable t) {
            stubReason = "引擎类未就绪";
            return null;
        }
        try {
            Object obj = cls.getConstructor(Gateway.Config.class).newInstance(cfg);
            if (obj instanceof Gateway) {
                return (Gateway) obj;
            }
            stubReason = ENGINE_CLASS + " 未实现 Gateway";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            stubReason = "引擎构造失败：" + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : " " + cause.getMessage());
        }
        return null;
    }
}
