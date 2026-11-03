package com.gary.ysplive.play;

import com.gary.ysplive.core.spi.Gateway;

/**
 * 引擎占位实现：当 {@code core.engine.YspGateway} 还没写完（或加载失败）时使用。
 * <p>它让整个 UI 仍然可以正常组合、启动、跳转，只是所有播放地址返回空串，
 * 界面会显示「引擎尚未就绪」。等真实引擎落地后，{@link GatewayProvider} 的反射会自动找到它，
 * 本类不再被实例化。
 */
final class StubGateway implements Gateway {

    private final Config config;
    private volatile Snapshot snapshot;
    private volatile String lastError = "引擎模块未就绪";

    StubGateway(Config config) {
        this.config = config == null ? new Config() : config;
        this.snapshot = new Snapshot(false, this.config.port, "", false, 0, 0L,
                0, 0.0, "", 0, lastError);
    }

    @Override
    public void start(final StartListener listener) {
        // 在后台线程回调，保持与真实引擎一致的行为（调用方无需特殊处理）。
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onStage("等待 core 引擎模块");
                    listener.onFailed(lastError);
                }
            }
        }, "ysp-stub-gateway");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Override
    public void stop() {
        // 占位实现无需释放资源
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public String getAllPlaylistUrl() {
        return "";
    }

    @Override
    public String getChannelUrl(String slug) {
        return "";
    }

    @Override
    public String getTimeshiftUrl(String slug, long startEpochSec, long endEpochSec) {
        return null;
    }
}
