package com.gary.ysplive.desktop;

import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 引擎生命周期管理（桌面版）。
 * <p>复用 {@code :core} 的 {@code YspGateway}——它本来就是纯 Java、零 Android 依赖，
 * 与 Android 版跑的是同一份协议实现。
 */
final class EngineController {

    private static final String TAG = "engine";

    private final DesktopConfig config;

    private volatile Gateway gateway;
    private volatile boolean running;
    private volatile String lastStage = "";
    private volatile String lastError = "";

    EngineController(DesktopConfig config) {
        this.config = config;
    }

    boolean isRunning() {
        Gateway gw = gateway;
        return running && gw != null && gw.isRunning();
    }

    String lastStage() {
        return lastStage;
    }

    String lastError() {
        return lastError;
    }

    Gateway gateway() {
        return gateway;
    }

    int port() {
        Gateway gw = gateway;
        if (gw == null) {
            return 0;
        }
        try {
            Gateway.Snapshot s = gw.snapshot();
            return s == null ? 0 : s.port;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    String localOrigin() {
        Gateway gw = gateway;
        if (gw == null) {
            return "";
        }
        try {
            Gateway.Snapshot s = gw.snapshot();
            return s == null ? "" : s.localOrigin;
        } catch (RuntimeException e) {
            return "";
        }
    }

    Gateway.Snapshot snapshot() {
        Gateway gw = gateway;
        if (gw == null) {
            return null;
        }
        try {
            return gw.snapshot();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 异步启动引擎；{@code onStage} 用于把阶段文案推给界面。
     *
     * @return 启动完成的 latch，调用方可选择等待
     */
    CountDownLatch startAsync(Consumer<String> onStage) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                Gateway gw = viaReflection();
                if (gw == null) {
                    lastError = "找不到引擎实现 com.gary.ysplive.core.engine.YspGateway";
                    failure.set(lastError);
                    return;
                }
                gateway = gw;
                gw.start(new Gateway.StartListener() {
                    @Override
                    public void onStage(String stage) {
                        lastStage = stage == null ? "" : stage;
                        Log.i(TAG, lastStage);
                        if (onStage != null) {
                            onStage.accept(lastStage);
                        }
                    }

                    @Override
                    public void onReady(Gateway.Snapshot snapshot) {
                        running = true;
                        lastStage = "已就绪";
                        Log.i(TAG, "引擎就绪: " + (snapshot == null ? "" : snapshot.localOrigin));
                        if (onStage != null) {
                            onStage.accept("已就绪");
                        }
                        done.countDown();
                    }

                    @Override
                    public void onFailed(String reason) {
                        lastError = reason == null ? "未知原因" : reason;
                        Log.e(TAG, "启动失败: " + lastError);
                        failure.set(lastError);
                        done.countDown();
                    }
                });
                if (!done.await(90, TimeUnit.SECONDS)) {
                    failure.set("启动超时");
                }
            } catch (Throwable e) {
                lastError = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                Log.e(TAG, "启动异常: " + lastError);
                failure.set(lastError);
            } finally {
                done.countDown();
            }
        }, "engine-start");
        t.setDaemon(true);
        t.start();
        return done;
    }

    /** 直接用反射构造，与 Android 版的 GatewayProvider 同一策略。 */
    private Gateway viaReflection() {
        try {
            Class<?> cls = Class.forName("com.gary.ysplive.core.engine.YspGateway");
            Object obj = cls.getConstructor(Gateway.Config.class)
                    .newInstance(config.toGatewayConfig());
            return obj instanceof Gateway ? (Gateway) obj : null;
        } catch (Throwable e) {
            Log.e(TAG, "构造引擎失败: " + e);
            return null;
        }
    }

    void stop() {
        Gateway gw = gateway;
        gateway = null;
        running = false;
        if (gw != null) {
            try {
                gw.stop();
                Log.i(TAG, "引擎已停止");
            } catch (Throwable e) {
                Log.w(TAG, "停止引擎异常: " + e);
            }
        }
    }
}
