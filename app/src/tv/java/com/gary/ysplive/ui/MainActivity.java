package com.gary.ysplive.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gary.ysplive.R;
import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.data.QualityState;
import com.gary.ysplive.play.EngineBus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 电视版主界面 —— 10 英尺 / 遥控器优先。
 *
 * <p><b>为什么和手机版完全不同：</b>
 * 电视上没有触摸、没有鼠标，唯一输入是方向键（D-pad）+ 确认键 + 返回键。
 * 所以这里一切围绕「焦点」设计：
 * <ul>
 *   <li>所有可交互元素都 {@code setFocusable(true)}，且焦点必须有<span>强烈视觉反馈</span>
 *       （放大 + 亮边框 + 提亮背景），否则用户根本不知道自己选到了哪</li>
 *   <li>焦点路径是确定的：分类栏 ←/→ → 下进入频道网格 → 网格内四向移动 → 确认进播放</li>
 *   <li>不做输入框（电视上打字是灾难），搜索用分类 + 收藏替代</li>
 *   <li>GridView 默认的蓝色 selector 必须去掉，否则会和自定义焦点态打架</li>
 * </ul>
 *
 * <p>参考了 Android TV 官方设计规范与成熟电视应用（Kodi / Emby / Netflix TV）的通用做法：
 * 深色背景、大字号、聚焦放大、底部常驻操作提示。
 */
public final class MainActivity extends Activity implements EngineBus.Observer {

    private static final String TAG = "tv-main";

    // 配色（与手机版共享同一套品牌色，但对比度更高，适合远距离观看）
    private static final int C_BG = 0xFF070A0E;
    private static final int C_PANEL = 0xFF111721;
    private static final int C_CARD = 0xFF161E2A;
    private static final int C_CARD_FOCUS = 0xFF25344A;
    private static final int C_TEXT = 0xFFE8EEF6;
    private static final int C_TEXT_DIM = 0xFF93A2B5;
    private static final int C_BRAND = 0xFFC81C2B;
    private static final int C_FOCUS_RING = 0xFFFFD24A;
    private static final int C_BADGE = 0xFF1D2B3D;
    private static final int C_AMBER = 0xFFF0B840;

    private static final String F_ALL = "全部";
    private static final String F_CCTV = "央视";
    private static final String F_SAT = "卫视";
    private static final String F_4K = "4K";
    private static final String F_FAV = "收藏";

    private TextView statusText;
    private TextView emptyText;
    private LinearLayout chipRow;
    private GridView grid;
    private GridViewAdapter adapter;

    private final List<ChannelSpec> all = new ArrayList<>();
    private final List<ChannelSpec> shown = new ArrayList<>();
    private String filter = F_ALL;
    private long lastOpenAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        EngineBus.addObserver(this);
        EngineBus.ensureStarted(this);
        reloadChannels();
        applyEngineState(EngineBus.state());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放页返回时刷新收藏/画质角标
        applyFilter();
    }

    @Override
    protected void onDestroy() {
        EngineBus.removeObserver(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 视图

    private View buildContentView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1) 顶部标题 + 状态
        column.addView(buildHeader());

        // 2) 分类栏（遥控器左右切换，确认选中）
        column.addView(buildChipBar());

        // 3) 频道网格（占满剩余空间）
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setHorizontalSpacing(dp(16));
        grid.setVerticalSpacing(dp(16));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setColumnWidth(dp(240));
        grid.setPadding(dp(36), dp(12), dp(36), dp(12));
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        // 关键：去掉系统默认的焦点高亮，改用卡片自己的焦点态
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setDrawSelectorOnTop(false);
        adapter = new GridViewAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < shown.size()) {
                    openPlayer(shown.get(position));
                }
            }
        });
        // 长按确认键 = 收藏 / 取消收藏（电视上没有右键菜单，这是最顺手的替代）
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                                           int position, long id) {
                if (position < 0 || position >= shown.size()) {
                    return false;
                }
                ChannelSpec spec = shown.get(position);
                boolean added = AppPrefs.get(MainActivity.this).toggleFavorite(spec.slug);
                Ui.toast(MainActivity.this,
                        (added ? "已收藏 " : "已取消收藏 ") + spec.name);
                applyFilter();
                return true;
            }
        });

        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        column.addView(grid, gridLp);

        // 4) 空态提示（叠在网格上）
        emptyText = new TextView(this);
        emptyText.setTextColor(C_TEXT_DIM);
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setVisibility(View.GONE);
        column.addView(emptyText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        // 5) 底部键位提示
        column.addView(buildFooter());
        return root;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(36), dp(28), dp(36), dp(10));

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextColor(C_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(title);

        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        statusText = new TextView(this);
        statusText.setTextColor(C_TEXT_DIM);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusText.setGravity(Gravity.END);
        row.addView(statusText);
        return row;
    }

    private View buildChipBar() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        // ScrollView 本身不能获得焦点，否则左右键会被它吞掉只做滚动
        scroll.setFocusable(false);
        scroll.setHorizontalScrollBarEnabled(false);

        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(dp(36), dp(4), dp(36), dp(12));
        scroll.addView(chipRow);

        for (String f : new String[]{F_ALL, F_CCTV, F_SAT, F_4K, F_FAV}) {
            chipRow.addView(buildChip(f));
        }
        // 初次进入把焦点给第一个分类，用户一按右键就能看到焦点在动
        chipRow.post(new Runnable() {
            @Override
            public void run() {
                if (chipRow.getChildCount() > 0) {
                    chipRow.getChildAt(0).requestFocus();
                }
            }
        });
        return scroll;
    }

    private View buildChip(final String label) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(22), dp(10), dp(22), dp(10));
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(false);
        chip.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        chip.setLayoutParams(lp);
        chip.setBackground(chipBackground(false));

        chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(chipBackground(hasFocus));
                animateFocus(v, hasFocus, 1.08f);
            }
        });
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filter = label;
                applyFilter();
                refreshChipStyles();
                // 选完分类直接把焦点送进网格，省一次向下按
                if (!shown.isEmpty()) {
                    grid.requestFocus();
                    grid.setSelection(0);
                }
            }
        });
        return chip;
    }

    private void refreshChipStyles() {
        for (int i = 0; i < chipRow.getChildCount(); i++) {
            TextView c = (TextView) chipRow.getChildAt(i);
            boolean active = filter.equals(c.getText().toString());
            c.setTextColor(active ? Color.WHITE : C_TEXT_DIM);
            if (!c.isFocused()) {
                c.setBackground(chipBackground(active));
            }
        }
    }

    private android.graphics.drawable.Drawable chipBackground(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? C_FOCUS_RING
                : (filter != null && !filter.isEmpty() ? C_PANEL : C_PANEL));
        d.setCornerRadius(dp(22));
        if (focused) {
            d.setStroke(dp(3), C_FOCUS_RING);
            d.setColor(C_CARD_FOCUS);
        }
        return d;
    }

    private View buildFooter() {
        TextView hint = new TextView(this);
        hint.setText("◀ ▶ 切换分类    ▲ ▼ 选择频道    确认键 播放    长按确认 收藏    返回 退出");
        hint.setTextColor(C_TEXT_DIM);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(36), dp(6), dp(36), dp(20));
        return hint;
    }

    // ------------------------------------------------------------------ 数据

    private void reloadChannels() {
        all.clear();
        all.addAll(ChannelTable.all());
        applyFilter();
    }

    private void applyFilter() {
        shown.clear();
        Set<String> favs = new HashSet<>(AppPrefs.get(this).favorites());
        for (ChannelSpec s : all) {
            if (matches(s, favs)) {
                shown.add(s);
            }
        }
        adapter.notifyDataSetChanged();
        boolean empty = shown.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptyText.setText(F_FAV.equals(filter) ? "还没有收藏的频道\n在频道上长按确认键即可收藏" : "没有匹配的频道");
        }
        refreshChipStyles();
        if (!empty) {
            grid.setSelection(0);
        }
    }

    private boolean matches(ChannelSpec s, Set<String> favs) {
        if (F_FAV.equals(filter)) {
            return favs.contains(s.slug);
        }
        if (F_ALL.equals(filter)) {
            return true;
        }
        if (F_CCTV.equals(filter)) {
            return "央视".equals(s.group);
        }
        if (F_SAT.equals(filter)) {
            return !"央视".equals(s.group);
        }
        if (F_4K.equals(filter)) {
            return ChannelTable.isTrue4k(s.slug) || s.name.contains("4K")
                    || (s.defn != null && s.defn.trim().toLowerCase(Locale.ROOT).matches("uhd|4k"));
        }
        return true;
    }

    private void openPlayer(ChannelSpec spec) {
        long now = System.currentTimeMillis();
        // D-pad 的 OK 可能在同一帧内重复上报，做一次去抖，否则会连开两个播放页
        if (now - lastOpenAt < 600L) {
            return;
        }
        lastOpenAt = now;
        AppPrefs prefs = AppPrefs.get(this);
        prefs.setLastChannelSlug(spec.slug);
        prefs.setLastWatchedAt(now);
        Intent intent = PlayerActivity.intentFor(this, spec.slug, filter);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "打开播放页失败：" + e);
            Ui.toast(this, "无法打开播放页：" + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ 引擎

    @Override
    public void onEngineState(EngineBus.State state) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyEngineState(state);
            }
        });
    }

    private void applyEngineState(EngineBus.State state) {
        if (statusText == null || state == null) {
            return;
        }
        statusText.setText(state.statusText());
        statusText.setTextColor(state.failed ? C_AMBER : C_TEXT_DIM);
    }

    // ------------------------------------------------------------------ 按键

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 返回键：在分类栏焦点时先回到「全部」，否则交给系统退出
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            View f = getCurrentFocus();
            if (f != null && f.getParent() == chipRow && !F_ALL.equals(filter)) {
                filter = F_ALL;
                applyFilter();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ 网格适配器

    private final class GridViewAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CardView card = convertView instanceof CardView ? (CardView) convertView : new CardView();
            card.bind(shown.get(position));
            return card;
        }
    }

    /** 单个频道卡片：大台标 + 名称 + 画质角标，焦点时放大并亮边。 */
    private final class CardView extends LinearLayout {
        private final ImageView logo;
        private final TextView name;
        private final TextView badge;
        private ChannelSpec spec;

        CardView() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setFocusable(true);
            setFocusableInTouchMode(false);
            setClickable(true);
            setPadding(dp(14), dp(14), dp(14), dp(12));
            setBackground(cardBackground(false));

            logo = new ImageView(MainActivity.this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(logo, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));

            LinearLayout bottom = new LinearLayout(MainActivity.this);
            bottom.setOrientation(HORIZONTAL);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            bottom.setPadding(0, dp(10), 0, 0);

            name = new TextView(MainActivity.this);
            name.setTextColor(C_TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            bottom.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            badge = new TextView(MainActivity.this);
            badge.setTextColor(C_TEXT_DIM);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            badge.setPadding(dp(7), dp(2), dp(7), dp(2));
            bottom.addView(badge);

            addView(bottom);

            setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackground(cardBackground(hasFocus));
                    animateFocus(v, hasFocus, 1.07f);
                }
            });
        }

        void bind(ChannelSpec s) {
            this.spec = s;
            name.setText(s.name);
            String actual = QualityState.actual(s.slug);
            boolean degraded = QualityState.isDegraded(s.slug);
            badge.setText(actual != null ? actual : s.defnLabel());
            badge.setTextColor(degraded ? C_AMBER : C_TEXT_DIM);

            android.graphics.Bitmap hit = LogoLoader.cached(s.logo);
            logo.setTag(LogoLoader.TAG_URL, s.logo);
            if (hit != null) {
                logo.setImageBitmap(hit);
            } else {
                logo.setImageDrawable(null);
                LogoLoader.load(s.logo, logo, null);
            }
        }
    }

    private android.graphics.drawable.Drawable cardBackground(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? C_CARD_FOCUS : C_CARD);
        d.setCornerRadius(dp(14));
        d.setStroke(focused ? dp(3) : dp(1), focused ? C_FOCUS_RING : 0xFF222C3A);
        return d;
    }

    /** 焦点放大动画：电视上必须让用户一眼看出焦点在哪。 */
    private void animateFocus(View v, boolean focused, float scale) {
        v.animate()
                .scaleX(focused ? scale : 1f)
                .scaleY(focused ? scale : 1f)
                .setDuration(120)
                .start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
