package com.gary.ysplive.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gary.ysplive.R;
import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.play.EngineBus;
import com.gary.ysplive.play.GatewayProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：频道列表（手机 + TV 通用）。
 * <p>顶部是标题与引擎状态条，下面是过滤 chip（全部/央视/卫视/4K/收藏），
 * 主体是 D-pad 友好的频道网格。长按频道可收藏；有上次观看记录时显示「继续观看」。
 */
public final class MainActivity extends Activity implements EngineBus.Observer {

    private static final String TAG = "main";

    private static final String FILTER_ALL = "全部";
    private static final String FILTER_CCTV = "央视";
    private static final String FILTER_WS = "卫视";
    private static final String FILTER_4K = "4K";
    private static final String FILTER_FAV = "收藏";

    private static final String KEY_FILTER = "ui_filter";

    private final Handler main = new Handler(Looper.getMainLooper());

    private GridView grid;
    private ChannelAdapter adapter;
    private LinearLayout filterRow;
    private TextView statusDot;
    private TextView statusText;
    private TextView statusAction;
    private TextView btnContinue;
    private View emptyView;
    private TextView emptyText;

    private String currentFilter = FILTER_ALL;
    private String lastWatchedSlug;

    private final List<TextView> chips = new ArrayList<>();
    private boolean engineRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString(KEY_FILTER);
            if (saved != null) {
                currentFilter = saved;
            }
        }

        grid = (GridView) findViewById(R.id.channel_grid);
        filterRow = (LinearLayout) findViewById(R.id.filter_row);
        statusDot = (TextView) findViewById(R.id.status_dot);
        statusText = (TextView) findViewById(R.id.status_text);
        statusAction = (TextView) findViewById(R.id.status_action);
        btnContinue = (TextView) findViewById(R.id.btn_continue);
        emptyView = findViewById(R.id.empty_view);
        emptyText = (TextView) findViewById(R.id.empty_text);

        adapter = new ChannelAdapter(this);
        // 点击/长按走 adapter 里绑在条目上的监听，而不是依赖 AdapterView.onItemClick：
        // 后者的触摸分发在部分 ROM / 存在 focusable 子项时会不触发（v9.5.0 的点击失效根因）。
        adapter.setOnChannelClickListener(new ChannelAdapter.OnChannelClickListener() {
            @Override
            public void onChannelClick(ChannelSpec spec) {
                openPlayer(spec);
            }
        });
        adapter.setOnChannelLongClickListener(new ChannelAdapter.OnChannelLongClickListener() {
            @Override
            public boolean onChannelLongClick(ChannelSpec spec) {
                toggleFavorite(spec);
                return true;
            }
        });
        grid.setAdapter(adapter);

        // 注意：不再使用 grid.setOnItemClickListener / setOnItemLongClickListener。
        // AdapterView 的点击分发在部分 ROM（以及视图树中存在 focusable 子项时）不触发，
        // 这正是 v9.5.0「点频道没反应」的根因；点击统一由 ChannelAdapter 绑在条目上。

        buildChips();

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsActivity.start(MainActivity.this);
            }
        });
        findViewById(R.id.btn_logs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogsActivity.start(MainActivity.this);
            }
        });
        findViewById(R.id.status_bar).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.showDiagnostics(MainActivity.this);
            }
        });
        btnContinue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resumeLastChannel();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        EngineBus.addObserver(this);
        refreshLastWatched();
        reloadChannels();
        ensureEngineStarted();

        // 焦点：TV 上首次进入要落在网格或「继续观看」上，否则 D-pad 无处可去。
        if (!hasFocusTarget()) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    focusFirstTarget();
                }
            }, 120L);
        }
    }

    @Override
    protected void onPause() {
        EngineBus.removeObserver(this);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (outState != null) {
            outState.putString(KEY_FILTER, currentFilter);
        }
    }

    // ---------------- 引擎 ----------------

    private void ensureEngineStarted() {
        if (engineRequested) {
            return;
        }
        engineRequested = true;
        // 从 Activity 启动前台服务是允许的；内部已做 O+ 分支与异常兜底。
        try {
            EngineBus.ensureStarted(this);
        } catch (RuntimeException e) {
            Log.w(TAG, "启动引擎服务失败：" + e);
        }
    }

    @Override
    public void onEngineState(EngineBus.State state) {
        applyEngineState(state);
        // 引擎从「未就绪」变成「就绪」时刷新一次，让诊断/地址可点。
        if (state.ready && adapter.getCount() == 0) {
            reloadChannels();
        }
    }

    private void applyEngineState(EngineBus.State state) {
        if (statusText == null || state == null) {
            return;
        }
        statusText.setText(state.statusText());

        int color;
        if (state.failed) {
            color = getResources().getColor(R.color.error);
        } else if (state.ready) {
            color = state.usingStub
                    ? getResources().getColor(R.color.warning)
                    : getResources().getColor(R.color.success);
        } else {
            color = getResources().getColor(R.color.text_hint);
        }
        statusDot.setTextColor(color);
        statusAction.setVisibility(View.VISIBLE);
        updateSubtitle(state);
    }

    private void updateSubtitle(EngineBus.State state) {
        TextView subtitle = (TextView) findViewById(R.id.subtitle);
        if (subtitle == null) {
            return;
        }
        if (state.ready && !state.usingStub && state.port() > 0) {
            subtitle.setText("端口 " + state.port() + " · " + state.channelCount() + " 路");
        } else {
            subtitle.setText("");
        }
    }

    // ---------------- 过滤 ----------------

    private void buildChips() {
        filterRow.removeAllViews();
        chips.clear();
        final String[] labels = {FILTER_ALL, FILTER_CCTV, FILTER_WS, FILTER_4K, FILTER_FAV};
        for (final String label : labels) {
            final TextView chip = Ui.makeChip(this, label, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectFilter(label);
                }
            });
            // TV 遥控器的「菜单键」在这类界面上常被映射成长按，长按 chip 也切到收藏。
            if (FILTER_FAV.equals(label)) {
                chip.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        selectFilter(FILTER_FAV);
                        return true;
                    }
                });
            }
            chips.add(chip);
            filterRow.addView(chip);
        }
        applyChipSelection();
    }

    private void selectFilter(String label) {
        if (label == null || label.equals(currentFilter)) {
            return;
        }
        currentFilter = label;
        applyChipSelection();
        reloadChannels();
        // 切换后把焦点放回网格，TV 体验更顺。
        if (grid != null) {
            grid.requestFocus();
        }
    }

    private void applyChipSelection() {
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            Ui.setChipSelected(chip, chip.getText().toString().equals(currentFilter));
        }
    }

    // ---------------- 数据 ----------------

    private void reloadChannels() {
        List<ChannelSpec> all;
        try {
            all = ChannelTable.all();
        } catch (Throwable t) {
            Log.e(TAG, "读取频道表失败：" + t);
            all = new ArrayList<>();
        }
        List<ChannelSpec> filtered = filter(all, currentFilter);
        adapter.setItems(filtered);

        boolean empty = filtered.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        grid.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            emptyText.setText(FILTER_FAV.equals(currentFilter)
                    ? getString(R.string.tip_no_favorites)
                    : "没有匹配的频道");
        }
    }

    private List<ChannelSpec> filter(List<ChannelSpec> all, String label) {
        List<ChannelSpec> out = new ArrayList<>();
        if (label == null) {
            return all;
        }
        switch (label) {
            case FILTER_CCTV:
                for (ChannelSpec s : all) {
                    if ("央视".equals(s.group)) {
                        out.add(s);
                    }
                }
                return out;
            case FILTER_WS:
                for (ChannelSpec s : all) {
                    if ("卫视".equals(s.group)) {
                        out.add(s);
                    }
                }
                return out;
            case FILTER_4K:
                for (ChannelSpec s : all) {
                    if (ChannelTable.isTrue4k(s.slug) || s.name.contains("4K")
                            || s.name.contains("8K")) {
                        out.add(s);
                    }
                }
                return out;
            case FILTER_FAV: {
                AppPrefs prefs = AppPrefs.get(this);
                for (ChannelSpec s : all) {
                    if (prefs.isFavorite(s.slug)) {
                        out.add(s);
                    }
                }
                return out;
            }
            default:
                return all;
        }
    }

    // ---------------- 收藏 / 继续观看 ----------------

    private void toggleFavorite(ChannelSpec spec) {
        boolean added = AppPrefs.get(this).toggleFavorite(spec.slug);
        Ui.toast(this, added ? "已收藏 " + spec.name : "已取消收藏 " + spec.name);
        reloadChannels();
        if (FILTER_FAV.equals(currentFilter)) {
            applyChipSelection();
        }
    }

    private void refreshLastWatched() {
        AppPrefs prefs = AppPrefs.get(this);
        lastWatchedSlug = prefs.lastChannelSlug();
        ChannelSpec spec = lastWatchedSlug == null ? null : ChannelTable.bySlug(lastWatchedSlug);
        if (spec != null) {
            btnContinue.setVisibility(View.VISIBLE);
            btnContinue.setText("继续观看 · " + spec.name);
        } else {
            lastWatchedSlug = null;
            btnContinue.setVisibility(View.GONE);
        }
        // 有继续观看时，TV 首次焦点优先给「继续观看」。
        if (spec != null && !hasFocusTarget()) {
            btnContinue.requestFocus();
        }
    }

    private void resumeLastChannel() {
        ChannelSpec spec = lastWatchedSlug == null ? null : ChannelTable.bySlug(lastWatchedSlug);
        if (spec == null) {
            Ui.toast(this, "没有观看记录");
            refreshLastWatched();
            return;
        }
        openPlayer(spec);
    }

    private void openPlayer(ChannelSpec spec) {
        // 防重入：触屏点击可能经由 GridView.onItemClick 与条目自身 click 两条路径进来，
        // D-pad 的 OK 也可能在同一帧内重复上报，这里统一做一次去抖。
        long now = System.currentTimeMillis();
        if (now - lastOpenAt < 600L) {
            return;
        }
        lastOpenAt = now;

        AppPrefs.get(this).setLastChannelSlug(spec.slug);
        lastWatchedSlug = spec.slug;
        Intent intent = PlayerActivity.intentFor(this, spec.slug, currentFilter);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "打开播放页失败：" + e);
            Ui.toast(this, "无法打开播放页：" + e.getClass().getSimpleName());
        }
    }

    /** 上次打开播放页的时间，用于点击去抖。 */
    private long lastOpenAt;

    // ---------------- 焦点 ----------------

    private boolean hasFocusTarget() {
        View focus = getCurrentFocus();
        return focus != null;
    }

    private void focusFirstTarget() {
        if (btnContinue.getVisibility() == View.VISIBLE) {
            btnContinue.requestFocus();
            return;
        }
        if (adapter.getCount() > 0) {
            grid.requestFocus();
            grid.setSelection(0);
        }
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                // TV 遥控器的菜单键：切到收藏筛选。
                selectFilter(FILTER_FAV);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                // 主界面上翻台等于「上一个/下一个收藏」之外的语义，这里直接忽略，交给网格滚动。
                return super.onKeyDown(keyCode, event);
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public void onBackPressed() {
        if (!FILTER_ALL.equals(currentFilter)) {
            selectFilter(FILTER_ALL);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        EngineBus.removeObserver(this);
        super.onDestroy();
    }
}
