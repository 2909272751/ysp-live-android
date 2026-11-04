package com.gary.ysplive.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

import com.gary.ysplive.R;
import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.play.EngineBus;
import com.gary.ysplive.play.GatewayProvider;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 播放页。
 * <p>ExoPlayer + HLS，播放地址来自本地代理（{@link Gateway#getChannelUrl(String)}）。
 * 支持回看（{@link Gateway#getTimeshiftUrl}）、上/下一频道快速换台、收藏、诊断。
 * D-pad：OK 呼出/隐藏控制条；上/下键或 CHANNEL_UP/DOWN、MEDIA_NEXT/PREVIOUS 换台。
 */
public final class PlayerActivity extends Activity
        implements EngineBus.Observer, PlayerChrome.Host {

    private static final String TAG = "player";

    private static final String EXTRA_SLUG = "slug";
    private static final String EXTRA_FILTER = "filter";
    private static final String KEY_SLUG = "state_slug";
    private static final String KEY_TIMESHIFT_START = "state_ts_start";
    private static final String KEY_TIMESHIFT_END = "state_ts_end";

    private static final String UA = "YSPLive/9.5 (Android)";
    /** 直播缓冲：前向 15s，后向 30s。 */
    private static final long BUFFER_FORWARD_MS = 15_000L;
    private static final long BUFFER_BACK_MS = 30_000L;
    private static final int MAX_RETRY = 3;
    private static final long RETRY_DELAY_MS = 1_500L;
    private static final long OVERLAY_HIDE_MS = 4_000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private DefaultHttpDataSource.Factory dataSourceFactory;
    private PlayerView playerView;
    private PlayerChrome chrome;
    private AppPrefs prefs;

    private LinearLayout overlay;
    private TextView overlayName;
    private TextView overlayMeta;
    private TextView overlayQuality;
    private TextView btnPlayPause;
    private TextView btnFav;
    private View bufferingBox;
    private ProgressBar bufferingSpinner;
    private TextView bufferingText;
    private View errorCard;
    private TextView errorText;
    private TextView hintKey;

    private String slug;
    private String filter = "全部";
    private List<ChannelSpec> playlist = new ArrayList<>();
    private ChannelSpec current;

    /** 当前生效的时移区间（秒）；0/0 表示直播。 */
    private long timeshiftStart;
    private long timeshiftEnd;

    private int retryCount;
    private boolean prepareRequested;
    private boolean overlayVisible;
    private boolean engineWaiting;
    private boolean resumeOnStart;

    private final Runnable hideOverlayTask = new Runnable() {
        @Override
        public void run() {
            hideOverlay();
        }
    };

    // ---------------- 入口 ----------------

    static Intent intentFor(Context ctx, String slug, String filter) {
        Intent intent = new Intent(ctx, PlayerActivity.class);
        intent.putExtra(EXTRA_SLUG, slug);
        if (filter != null) {
            intent.putExtra(EXTRA_FILTER, filter);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (savedInstanceState != null) {
            slug = savedInstanceState.getString(KEY_SLUG);
            timeshiftStart = savedInstanceState.getLong(KEY_TIMESHIFT_START, 0L);
            timeshiftEnd = savedInstanceState.getLong(KEY_TIMESHIFT_END, 0L);
        }
        if (slug == null) {
            Intent intent = getIntent();
            slug = intent == null ? null : intent.getStringExtra(EXTRA_SLUG);
            String f = intent == null ? null : intent.getStringExtra(EXTRA_FILTER);
            if (f != null) {
                filter = f;
            }
        }
        if (savedInstanceState == null) {
            timeshiftStart = 0L;
            timeshiftEnd = 0L;
        }

        current = slug == null ? null : ChannelTable.bySlug(slug);
        if (current == null) {
            Log.w(TAG, "缺少有效频道参数，关闭播放页");
            Ui.toast(this, "频道不存在");
            finish();
            return;
        }

        bindViews();
        buildPlaylist();
        initPlayer();

        chrome = new PlayerChrome(this, this);
        prefs = AppPrefs.get(this);
        // 手机用户普遍开着竖屏锁定，fullUser 会让播放页一直竖屏 —— 这里强制横屏。
        chrome.applyOrientation(prefs);
        chrome.installGestures(playerView, prefs);

        playCurrent(false);
        showOverlay();

        // 引擎可能还在启动：先等一下，就绪后自动开播。
        EngineBus.addObserver(this);
        EngineBus.ensureStarted(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        String next = intent.getStringExtra(EXTRA_SLUG);
        if (next != null && !next.equals(slug)) {
            slug = next;
            current = ChannelTable.bySlug(slug);
            timeshiftStart = 0L;
            timeshiftEnd = 0L;
            buildPlaylist();
            playCurrent(true);
            showOverlay();
        }
    }

    private void bindViews() {
        playerView = (PlayerView) findViewById(R.id.player_view);
        // 这些属性原本在 XML 里用 app: 设置，改成代码设置，避免依赖 media3 自定义属性。
        playerView.setUseController(false);
        playerView.setShutterBackgroundColor(android.graphics.Color.BLACK);
        overlay = (LinearLayout) findViewById(R.id.overlay);
        overlayName = (TextView) findViewById(R.id.overlay_name);
        overlayMeta = (TextView) findViewById(R.id.overlay_meta);
        overlayQuality = (TextView) findViewById(R.id.overlay_quality);
        btnPlayPause = (TextView) findViewById(R.id.btn_play_pause);
        btnFav = (TextView) findViewById(R.id.btn_fav);
        bufferingBox = findViewById(R.id.buffering_box);
        bufferingSpinner = (ProgressBar) findViewById(R.id.buffering_spinner);
        bufferingText = (TextView) findViewById(R.id.buffering_text);
        errorCard = findViewById(R.id.error_card);
        errorText = (TextView) findViewById(R.id.error_text);
        hintKey = findViewById(R.id.hint_key);

        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 点击控制层空白处 = 收起
                hideOverlay();
            }
        });
        // ------------------------------------------------------------------
        // 触摸手势（手机版核心交互；电视版无触摸，这些分支不会触发）
        //
        // 参考成熟播放器（MX Player / VLC / 哔哩哔哩）的通用约定：
        //   单击          -> 显隐控制层
        //   双击          -> 播放 / 暂停
        //   左右滑        -> 上/下一个频道（比找按钮快）
        //   右半边上下滑  -> 音量
        //   左半边上下滑  -> 亮度
        // 之前只有「单击显隐控制层」，换台/音量都得先点开控制层再找按钮，
        // 在手机上是明显的体验落差。
        // ------------------------------------------------------------------
        playerView.setClickable(false);
        final android.view.GestureDetector gestures =
                new android.view.GestureDetector(this,
                        new android.view.GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                                toggleOverlay();
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(android.view.MotionEvent e) {
                                togglePlayPause();
                                return true;
                            }

                            @Override
                            public boolean onFling(android.view.MotionEvent e1,
                                                   android.view.MotionEvent e2,
                                                   float vx, float vy) {
                                if (e1 == null || e2 == null) {
                                    return false;
                                }
                                float dx = e2.getX() - e1.getX();
                                float dy = e2.getY() - e1.getY();
                                float min = 72 * getResources().getDisplayMetrics().density;
                                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > min) {
                                    // 左滑 -> 下一个频道；右滑 -> 上一个频道
                                    zap(dx < 0 ? 1 : -1);
                                    return true;
                                }
                                return false;
                            }
                        });

        playerView.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean vertical;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                gestures.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        vertical = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dx = e.getX() - downX;
                        float dy = e.getY() - downY;
                        float step = 24 * getResources().getDisplayMetrics().density;
                        if (!vertical && Math.abs(dy) > Math.abs(dx)
                                && Math.abs(dy) > step) {
                            vertical = true;
                        }
                        if (vertical) {
                            if (dy < -step) {
                                adjustBySide(downX, v.getWidth(), 1);
                                downY = e.getY();
                            } else if (dy > step) {
                                adjustBySide(downX, v.getWidth(), -1);
                                downY = e.getY();
                            }
                        }
                        return true;
                    }
                    default:
                        return true;
                }
            }
        });


        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayPause();
            }
        });
        findViewById(R.id.btn_prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zap(-1);
            }
        });
        findViewById(R.id.btn_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zap(1);
            }
        });
        btnFav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFavorite();
            }
        });
        findViewById(R.id.btn_timeshift).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTimeshiftDialog();
            }
        });
        findViewById(R.id.btn_channel_list).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChannelList();
            }
        });
        findViewById(R.id.btn_diag).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.showDiagnostics(PlayerActivity.this);
            }
        });
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.error_retry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                retryCount = 0;
                playCurrent(true);
            }
        });
        findViewById(R.id.error_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        updateHeader();
    }

    // ---------------- 播放器 ----------------

    private void initPlayer() {
        dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                        dataSourceFactory))
                .setLoadControl(buildLoadControl())
                .build();

        player.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK);
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                updateBuffering(playbackState);
                if (playbackState == Player.STATE_READY) {
                    retryCount = 0;
                    hideError();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseLabel();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                handlePlaybackError(error);
            }
        });

        playerView.setPlayer(player);
    }

    /** 直播稳定性优先的缓冲策略：前向 15s、后向 30s。 */
    private androidx.media3.exoplayer.DefaultLoadControl buildLoadControl() {
        return new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        (int) (BUFFER_FORWARD_MS + BUFFER_BACK_MS),
                        (int) (BUFFER_FORWARD_MS + BUFFER_BACK_MS),
                        1_500,
                        3_000)
                .build();
    }

    /** 按当前 slug 组装播放地址并播放。 */
    private void playCurrent(boolean userInitiated) {
        if (current == null) {
            return;
        }
        Gateway gw = GatewayProvider.peek();
        if (gw == null || !gw.isRunning()) {
            engineWaiting = true;
            showError("引擎尚未就绪" + (engineWaitingHint() == null ? "" : "（" + engineWaitingHint() + "）")
                    + "，稍候将自动重试");
            scheduleEngineWait();
            return;
        }
        engineWaiting = false;
        engineWaitTicks = 0;
        String url;
        if (timeshiftStart > 0 && timeshiftEnd > timeshiftStart) {
            url = safeTimeshift(gw);
            if (url == null || url.isEmpty()) {
                timeshiftStart = 0L;
                timeshiftEnd = 0L;
                Ui.toast(this, "该频道不支持回看，已切回直播");
                url = safeChannelUrl(gw);
            }
        } else {
            url = safeChannelUrl(gw);
        }

        if (url == null || url.isEmpty()) {
            showError("引擎暂未提供该频道的播放地址");
            scheduleEngineWait();
            return;
        }

        if (userInitiated) {
            retryCount = 0;
        }
        hideError();
        prepareRequested = true;

        AppPrefs.get(this).setLastChannelSlug(current.slug);

        MediaItem item = MediaItem.fromUri(Uri.parse(url));
        HlsMediaSource source = new HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item);

        // 换台：先 stop 再换源，保证旧分片连接立刻释放，首帧更快。
        player.stop();
        player.clearMediaItems();
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);

        updateHeader();
        updatePlayPauseLabel();
        bufferingBox.setVisibility(View.VISIBLE);
        bufferingText.setText("正在连接…");
        Log.i(TAG, "播放 " + current.slug + " -> " + url);
    }

    private String engineWaitingHint() {
        String reason = GatewayProvider.stubReason();
        return reason == null || reason.isEmpty() ? null : reason;
    }

    private String safeChannelUrl(Gateway gw) {
        try {
            return gw.getChannelUrl(current.slug);
        } catch (RuntimeException e) {
            Log.e(TAG, "获取播放地址异常：" + e);
            return null;
        }
    }

    private String safeTimeshift(Gateway gw) {
        try {
            return gw.getTimeshiftUrl(current.slug, timeshiftStart, timeshiftEnd);
        } catch (RuntimeException e) {
            Log.e(TAG, "获取时移地址异常：" + e);
            return null;
        }
    }

    /** 等引擎就绪时的轮询次数上限（约 30 秒）。 */
    private int engineWaitTicks;

    private void scheduleEngineWait() {
        if (engineWaitTicks >= 20) {
            // 等太久了，静默放弃轮询，让用户手动点「重试」。
            return;
        }
        engineWaitTicks++;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || !engineWaiting) {
                    return;
                }
                Gateway gw = GatewayProvider.peek();
                if (gw != null && gw.isRunning()) {
                    engineWaiting = false;
                    engineWaitTicks = 0;
                    playCurrent(false);
                } else {
                    scheduleEngineWait();
                }
            }
        }, 1_500L);
    }

    private void handlePlaybackError(PlaybackException error) {
        // 面向用户的友好文案；技术细节只进日志。
        String friendly = PlayerChrome.friendlyError(error);
        Log.w(TAG, "播放出错：" + (error == null ? "未知" : describe(error)));

        if (retryCount < MAX_RETRY) {
            retryCount++;
            showError(friendly + "\n（第 " + retryCount + "/" + MAX_RETRY + " 次自动重试）");
            bufferingBox.setVisibility(View.GONE);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || player == null) {
                        return;
                    }
                    playCurrent(false);
                }
            }, RETRY_DELAY_MS);
            return;
        }
        showError(friendly + "\n\n已重试 " + MAX_RETRY + " 次仍未成功，可以换个频道或稍后再试");
        bufferingBox.setVisibility(View.GONE);
    }

    private static String describe(PlaybackException error) {
        StringBuilder sb = new StringBuilder();
        sb.append(error.getErrorCodeName());
        Throwable cause = error.getCause();
        if (cause != null && cause.getMessage() != null) {
            sb.append("：").append(cause.getMessage());
        }
        return sb.toString();
    }

    private void updateBuffering(int playbackState) {
        if (errorCard.getVisibility() == View.VISIBLE) {
            bufferingBox.setVisibility(View.GONE);
            return;
        }
        boolean buffering = playbackState == Player.STATE_BUFFERING
                || playbackState == Player.STATE_IDLE;
        bufferingBox.setVisibility(buffering ? View.VISIBLE : View.GONE);
        if (buffering) {
            bufferingText.setText(playbackState == Player.STATE_IDLE ? "正在连接…" : "正在缓冲…");
        }
    }

    private void updatePlayPauseLabel() {
        if (btnPlayPause == null || player == null) {
            return;
        }
        btnPlayPause.setText(player.isPlaying() ? R.string.pause : R.string.play);
    }

    private void togglePlayPause() {
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            if (player.getPlaybackState() == Player.STATE_IDLE) {
                playCurrent(true);
            } else {
                player.play();
            }
        }
        updatePlayPauseLabel();
        showOverlay();
    }

    // ---------------- 头部 / 控制层 ----------------

    private void updateHeader() {
        if (current == null) {
            return;
        }
        overlayName.setText(current.name);
        overlayQuality.setText(current.defnLabel());
        String meta;
        if (timeshiftStart > 0) {
            meta = "回看 " + formatTime(timeshiftStart) + " — " + formatTime(timeshiftEnd)
                    + " · " + current.group;
        } else {
            meta = current.group + " · 直播";
        }
        overlayMeta.setText(meta);
        if (btnFav != null) {
            boolean fav = AppPrefs.get(this).isFavorite(current.slug);
            btnFav.setText(fav ? R.string.unfavorite : R.string.favorite);
        }
    }

    private void toggleOverlay() {
        if (overlayVisible) {
            hideOverlay();
        } else {
            showOverlay();
        }
    }

    private void showOverlay() {
        overlayVisible = true;
        overlay.setVisibility(View.VISIBLE);
        hintKey.setVisibility(View.GONE);
        main.removeCallbacks(hideOverlayTask);
        main.postDelayed(hideOverlayTask, OVERLAY_HIDE_MS);
    }

    private void hideOverlay() {
        overlayVisible = false;
        overlay.setVisibility(View.GONE);
        hintKey.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideOverlayTask);
    }

    // ------------------------------------------------------------------
    // 手势辅助：音量 / 亮度
    // ------------------------------------------------------------------

    /** 记录 hintKey 的原始文案，手势提示用完要还原。 */
    private CharSequence hintKeyOriginal;

    private final Runnable restoreHintTask = new Runnable() {
        @Override
        public void run() {
            if (hintKey == null) {
                return;
            }
            hintKey.setText(hintKeyOriginal == null ? "" : hintKeyOriginal);
            if (overlayVisible) {
                hintKey.setVisibility(View.GONE);
            }
        }
    };

    /** 短暂显示一条手势提示，不打断控制层状态。 */
    private void showGestureHint(String msg) {
        if (hintKey == null) {
            return;
        }
        if (hintKeyOriginal == null) {
            hintKeyOriginal = hintKey.getText();
        }
        hintKey.setText(msg);
        hintKey.setVisibility(View.VISIBLE);
        main.removeCallbacks(restoreHintTask);
        main.postDelayed(restoreHintTask, 900);
    }

    /**
     * 屏幕右半边上下滑 = 音量；左半边 = 亮度。
     * direction &gt; 0 为增大。
     */
    private void adjustBySide(float downX, int viewWidth, int direction) {
        boolean rightSide = downX > viewWidth / 2f;
        if (rightSide) {
            try {
                android.media.AudioManager am =
                        (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                if (am == null) {
                    return;
                }
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        direction > 0
                                ? android.media.AudioManager.ADJUST_RAISE
                                : android.media.AudioManager.ADJUST_LOWER,
                        0);
                int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                int pct = max <= 0 ? 0 : Math.round(cur * 100f / max);
                showGestureHint("音量 " + pct + "%");
            } catch (RuntimeException ignored) {
                // 某些设备/权限场景下取不到 AudioManager，静默失败即可
            }
        } else {
            try {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                float cur = lp.screenBrightness;
                if (cur < 0f) {
                    cur = 0.5f;   // 跟随系统时先取中间值作为起点
                }
                cur += direction * 0.05f;
                cur = Math.max(0.05f, Math.min(1f, cur));
                lp.screenBrightness = cur;
                getWindow().setAttributes(lp);
                showGestureHint("亮度 " + Math.round(cur * 100) + "%");
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
    }

    private void resetOverlayTimer() {
        main.removeCallbacks(hideOverlayTask);
        main.postDelayed(hideOverlayTask, OVERLAY_HIDE_MS);
    }

    private void showError(String message) {
        errorCard.setVisibility(View.VISIBLE);
        errorText.setText(message);
        bufferingBox.setVisibility(View.GONE);
        if (overlayVisible) {
            hideOverlay();
        }
        View retry = findViewById(R.id.error_retry);
        if (retry != null) {
            retry.requestFocus();
        }
    }

    private void hideError() {
        errorCard.setVisibility(View.GONE);
    }

    // ---------------- 换台 ----------------

    private void buildPlaylist() {
        List<ChannelSpec> all;
        try {
            all = ChannelTable.all();
        } catch (Throwable t) {
            Log.e(TAG, "构建播放列表失败：" + t);
            all = new ArrayList<>();
        }
        // 换台顺序跟随主界面当前筛选，用户看到什么就切什么。
        List<ChannelSpec> filtered = new ArrayList<>();
        boolean favOnly = "收藏".equals(filter);
        AppPrefs prefs = AppPrefs.get(this);
        for (ChannelSpec s : all) {
            boolean keep;
            if (favOnly) {
                keep = prefs.isFavorite(s.slug);
            } else if ("央视".equals(filter)) {
                keep = "央视".equals(s.group);
            } else if ("卫视".equals(filter)) {
                keep = "卫视".equals(s.group);
            } else if ("4K".equals(filter)) {
                keep = ChannelTable.isTrue4k(s.slug) || s.name.contains("4K")
                        || s.name.contains("8K");
            } else {
                keep = true;
            }
            if (keep) {
                filtered.add(s);
            }
        }
        if (filtered.isEmpty()) {
            filtered.addAll(all);
        }
        playlist = filtered;
        if (current != null && !playlist.contains(current)) {
            playlist.add(current);
        }
    }

    /** 在当前列表里切上/下一台。 */
    private void zap(int delta) {
        if (playlist.isEmpty()) {
            return;
        }
        int index = current == null ? -1 : playlist.indexOf(current);
        if (index < 0) {
            index = 0;
        }
        int next = (index + delta + playlist.size()) % playlist.size();
        ChannelSpec target = playlist.get(next);
        if (target == null || target.equals(current)) {
            return;
        }
        switchTo(target);
    }

    private void switchTo(ChannelSpec target) {
        current = target;
        slug = target.slug;
        timeshiftStart = 0L;
        timeshiftEnd = 0L;
        retryCount = 0;
        // 立刻显示频道名：不用等首帧。
        updateHeader();
        showOverlay();
        resetOverlayTimer();
        playCurrent(true);
    }

    private void toggleFavorite() {
        if (current == null) {
            return;
        }
        boolean added = AppPrefs.get(this).toggleFavorite(current.slug);
        Ui.toast(this, added ? "已收藏 " + current.name : "已取消收藏 " + current.name);
        updateHeader();
        resetOverlayTimer();
    }

    // ---------------- 频道列表 ----------------

    private void showChannelList() {
        if (playlist.isEmpty()) {
            return;
        }
        final int[] index = new int[playlist.size()];
        String[] names = new String[playlist.size()];
        int checked = 0;
        for (int i = 0; i < playlist.size(); i++) {
            ChannelSpec spec = playlist.get(i);
            index[i] = i;
            boolean fav = AppPrefs.get(this).isFavorite(spec.slug);
            names[i] = (fav ? "★ " : "   ") + spec.name + "   " + spec.defnLabel();
            if (current != null && spec.slug.equals(current.slug)) {
                checked = i;
            }
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("选择频道")
                .setNegativeButton(R.string.cancel, null)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which >= 0 && which < playlist.size()) {
                            ChannelSpec target = playlist.get(which);
                            if (target != null && !target.equals(current)) {
                                switchTo(target);
                            }
                        }
                        requestOverlayFocus();
                    }
                });
        AlertDialog dialog = b.create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                // TV：让列表自己拿到焦点，D-pad 才能滚动。
                if (d instanceof AlertDialog) {
                    ListView lv = ((AlertDialog) d).getListView();
                    if (lv != null) {
                        lv.requestFocus();
                    }
                }
            }
        });
        dialog.show();
        main.removeCallbacks(hideOverlayTask);
    }

    // ---------------- 回看 ----------------

    private void showTimeshiftDialog() {
        if (current == null) {
            return;
        }
        if (!ChannelTable.supportsTimeshift(current.slug)) {
            Ui.toast(this, "该频道不支持回看");
            return;
        }
        final String[] days = new String[7];
        final Calendar cal = Calendar.getInstance();
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd E", Locale.CHINA);
        for (int i = 0; i < 7; i++) {
            Calendar c = (Calendar) cal.clone();
            c.add(Calendar.DAY_OF_YEAR, -i);
            String label = i == 0 ? "今天" : (i == 1 ? "昨天" : fmt.format(new Date(c.getTimeInMillis())));
            days[i] = label + "（" + fmt.format(new Date(c.getTimeInMillis())) + "）";
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("选择回看日期")
                .setNegativeButton(R.string.cancel, null)
                .setItems(days, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Calendar c = (Calendar) cal.clone();
                        c.add(Calendar.DAY_OF_YEAR, -which);
                        showSlotDialog(c);
                    }
                });
        b.show();
        main.removeCallbacks(hideOverlayTask);
    }

    private void showSlotDialog(final Calendar day) {
        // 半小时粒度：48 个槽位，但只允许已经播完的时段。
        final List<String> labels = new ArrayList<>();
        final List<long[]> ranges = new ArrayList<>();
        Calendar slot = (Calendar) day.clone();
        slot.set(Calendar.HOUR_OF_DAY, 0);
        slot.set(Calendar.MINUTE, 0);
        slot.set(Calendar.SECOND, 0);
        slot.set(Calendar.MILLISECOND, 0);
        long now = System.currentTimeMillis();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.CHINA);

        for (int i = 0; i < 48; i++) {
            long start = slot.getTimeInMillis();
            long end = start + 30L * 60L * 1000L;
            if (end > now) {
                break;
            }
            labels.add(hm.format(new Date(start)) + " — " + hm.format(new Date(end)));
            ranges.add(new long[]{start / 1000L, end / 1000L});
            slot.add(Calendar.MINUTE, 30);
        }
        if (labels.isEmpty()) {
            Ui.toast(this, "该日期还没有可回看的时段");
            return;
        }

        final String[] items = labels.toArray(new String[0]);
        AlertDialog.Builder b = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("选择回看时段")
                .setNegativeButton(R.string.cancel, null)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < 0 || which >= ranges.size()) {
                            return;
                        }
                        long[] range = ranges.get(which);
                        timeshiftStart = range[0];
                        timeshiftEnd = range[1];
                        retryCount = 0;
                        playCurrent(true);
                        showOverlay();
                    }
                });
        b.show();
        main.removeCallbacks(hideOverlayTask);
    }

    private static String formatTime(long epochSec) {
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        return fmt.format(new Date(epochSec * 1000L));
    }

    private void requestOverlayFocus() {
        if (btnPlayPause != null) {
            btnPlayPause.requestFocus();
        }
    }

    // ---------------- 引擎状态 ----------------

    @Override
    public void onEngineState(EngineBus.State state) {
        if (state == null || current == null || isFinishing()) {
            return;
        }
        if (state.ready && engineWaiting) {
            engineWaiting = false;
            playCurrent(false);
            showOverlay();
            return;
        }
        if (state.failed) {
            // 引擎挂了：出错卡片常驻，不再自动轮询，等用户点「重试」。
            engineWaiting = false;
            showError(state.lastError.isEmpty()
                    ? "引擎启动失败，请到设置页重启引擎"
                    : "引擎启动失败：" + state.lastError);
        }
    }

    // ---------------- 按键 / 生命周期 ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 控制条展开且焦点落在某个按钮上时，把方向键/OK 交还给按钮自己处理。
        boolean overlayFocused = overlayVisible && overlay.hasFocus();

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (errorCard.getVisibility() == View.VISIBLE) {
                    return super.onKeyDown(keyCode, event);
                }
                if (overlayFocused) {
                    // 让被聚焦的按钮拿到这次点击
                    return super.onKeyDown(keyCode, event);
                }
                if (overlayVisible) {
                    hideOverlay();
                } else {
                    showOverlay();
                    requestOverlayFocus();
                }
                return true;
            case KeyEvent.KEYCODE_SPACE:
                if (overlayFocused) {
                    return super.onKeyDown(keyCode, event);
                }
                toggleOverlay();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (overlayFocused) {
                    return super.onKeyDown(keyCode, event);
                }
                zap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (overlayFocused) {
                    return super.onKeyDown(keyCode, event);
                }
                zap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (overlayVisible) {
                    return super.onKeyDown(keyCode, event);
                }
                // 控制条收起时，左右键也用来换台，符合多数直播 App 的直觉。
                zap(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                togglePlayPause();
                return true;
            case KeyEvent.KEYCODE_MENU:
                toggleFavorite();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (overlayVisible) {
                    hideOverlay();
                    return true;
                }
                if (timeshiftStart > 0) {
                    timeshiftStart = 0L;
                    timeshiftEnd = 0L;
                    playCurrent(true);
                    Ui.toast(this, "已切回直播");
                    return true;
                }
                return super.onKeyDown(keyCode, event);
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        applyImmersive();
        // 从后台回来时恢复播放（onStop 里主动暂停过）。
        if (player != null && resumeOnStart) {
            player.setPlayWhenReady(true);
        }
        resumeOnStart = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            resumeOnStart = player.getPlayWhenReady();
            player.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (outState != null) {
            outState.putString(KEY_SLUG, slug);
            outState.putLong(KEY_TIMESHIFT_START, timeshiftStart);
            outState.putLong(KEY_TIMESHIFT_END, timeshiftEnd);
        }
    }

    @Override
    protected void onDestroy() {
        EngineBus.removeObserver(this);
        main.removeCallbacksAndMessages(null);
        if (chrome != null) {
            chrome.cancelAutoHide();
            chrome.restoreOrientation();
            chrome.release();
        }
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "释放播放器异常：" + e);
            }
            player = null;
        }
        super.onDestroy();
    }

    // ---------------- PlayerChrome.Host ----------------

    @Override
    public void onChromeToggleOverlay() {
        toggleOverlay();
    }

    @Override
    public void onChromeZap(int delta) {
        zap(delta);
    }

    @Override
    public void onChromeTogglePlayPause() {
        togglePlayPause();
        Ui.toast(this, player != null && player.isPlaying() ? "▶ 播放" : "⏸ 暂停");
    }

    @Override
    public void onChromeShowOverlay() {
        if (!overlayVisible) {
            showOverlay();
        }
    }

    @Override
    public void onChromeHideOverlay() {
        if (overlayVisible) {
            hideOverlay();
        }
    }

    @Override
    public boolean onChromeOverlayVisible() {
        return overlayVisible;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        try {
            View decor = getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            }
            decor.setSystemUiVisibility(flags);
        } catch (RuntimeException e) {
            Log.w(TAG, "设置沉浸式失败：" + e);
        }
    }
}
