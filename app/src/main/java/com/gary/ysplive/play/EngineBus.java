package com.gary.ysplive.play;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 引擎状态的进程内广播中心。
 * <p>{@link EngineService} 是唯一持有引擎的组件，它把启动阶段、快照、错误写到这里；
 * 各个 Activity 通过注册 {@link Observer} 拿到更新，完全不需要绑定服务。
 * <p>回调统一切到主线程。
 */
public final class EngineBus {

    private static final String TAG = "engine";

    /** 引擎状态快照（不可变，用于跨线程传递）。 */
    public static final class State {
        public final boolean starting;
        public final boolean ready;
        public final boolean failed;
        public final String stage;
        public final String lastError;
        public final Gateway.Snapshot snapshot;
        public final boolean usingStub;

        State(boolean starting, boolean ready, boolean failed, String stage,
              String lastError, Gateway.Snapshot snapshot, boolean usingStub) {
            this.starting = starting;
            this.ready = ready;
            this.failed = failed;
            this.stage = stage == null ? "" : stage;
            this.lastError = lastError == null ? "" : lastError;
            this.snapshot = snapshot;
            this.usingStub = usingStub;
        }

        public int channelCount() {
            return snapshot == null ? 0 : snapshot.channelCount;
        }

        public int port() {
            return snapshot == null ? 0 : snapshot.port;
        }

        public boolean highBitrateReady() {
            return snapshot != null && snapshot.highBitrateReady;
        }

        /** 顶部状态条文案。 */
        public String statusText() {
            if (failed) {
                return lastError.isEmpty() ? "引擎启动失败" : "引擎启动失败：" + lastError;
            }
            if (ready) {
                if (usingStub) {
                    return "引擎模块未就绪（界面预览模式）";
                }
                String mode = highBitrateReady() ? "高码率已就绪" : "1080p 模式";
                return mode + " · 端口 " + port() + " · " + channelCount() + " 频道";
            }
            if (starting) {
                return stage.isEmpty() ? "正在启动…" : stage;
            }
            return "引擎未运行";
        }
    }

    /** 状态观察者；回调在主线程。 */
    public interface Observer {
        void onEngineState(State state);
    }

    private static final Object LOCK = new Object();
    private static final List<Observer> OBSERVERS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile State state = new State(false, false, false, "", "", null, false);

    private EngineBus() {
    }

    public static State state() {
        return state;
    }

    public static void addObserver(Observer observer) {
        if (observer == null) {
            return;
        }
        synchronized (LOCK) {
            if (!OBSERVERS.contains(observer)) {
                OBSERVERS.add(observer);
            }
        }
        final Observer target = observer;
        final State current = state;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                target.onEngineState(current);
            }
        });
    }

    public static void removeObserver(Observer observer) {
        synchronized (LOCK) {
            OBSERVERS.remove(observer);
        }
    }

    // ---------------- 由 EngineService 调用 ----------------

    static void publishStarting(String stage) {
        publish(new State(true, false, false, stage, "", state.snapshot, state.usingStub));
    }

    static void publishReady(Gateway.Snapshot snapshot, boolean usingStub) {
        publish(new State(false, true, false, "", "", snapshot, usingStub));
    }

    static void publishFailed(String reason) {
        Log.w(TAG, "引擎启动失败：" + reason);
        publish(new State(false, false, true, "", reason, null, state.usingStub));
    }

    static void publishStopped() {
        publish(new State(false, false, false, "", "", null, state.usingStub));
    }

    private static void publish(final State next) {
        state = next;
        final List<Observer> copy;
        synchronized (LOCK) {
            copy = new ArrayList<>(OBSERVERS);
        }
        if (copy.isEmpty()) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Observer o : copy) {
                    try {
                        o.onEngineState(next);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "引擎状态回调异常：" + e);
                    }
                }
            }
        });
    }

    // ---------------- 便捷入口 ----------------

    /** 确保前台服务已启动（幂等）。 */
    public static void ensureStarted(Context ctx) {
        EngineService.start(ctx);
    }

    /** 请求重启引擎。 */
    public static void restart(Context ctx) {
        EngineService.restart(ctx);
    }

    /** 停止引擎服务。 */
    public static void stop(Context ctx) {
        EngineService.stop(ctx);
    }
}
