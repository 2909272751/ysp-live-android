package com.gary.ysplive.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.gary.ysplive.R;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.data.ChannelSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道网格适配器。
 * <p>每个条目：台标（异步加载，失败时用「首字 + 彩色圆角块」占位）、频道名、清晰度徽章、收藏星标。
 * <p>条目自身 focusable，配合 {@code bg_card} 的 state_focused 样式做 D-pad 焦点高亮。
 */
final class ChannelAdapter extends BaseAdapter {

    /** 占位块底色，按 slug 稳定取色，避免每次滚动变色。 */
    private static final int[] PLACEHOLDER_COLORS = {
            0xFF2E4A6B, 0xFF3C3A6B, 0xFF6B2E3E, 0xFF2E6B55,
            0xFF6B552E, 0xFF4A2E6B, 0xFF2E5B6B, 0xFF5B2E2E,
    };

    private final Context context;
    private final LayoutInflater inflater;
    private final List<ChannelSpec> items = new ArrayList<>();

    ChannelAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    /** 替换数据源。 */
    void setItems(List<ChannelSpec> next) {
        items.clear();
        if (next != null) {
            items.addAll(next);
        }
        notifyDataSetChanged();
    }

    List<ChannelSpec> items() {
        return items;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public ChannelSpec getItem(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_channel, parent, false);
        }
        ChannelSpec spec = getItem(position);
        if (spec == null) {
            return view;
        }

        TextView placeholder = (TextView) view.findViewById(R.id.channel_placeholder);
        ImageView logo = (ImageView) view.findViewById(R.id.channel_logo);
        TextView name = (TextView) view.findViewById(R.id.channel_name);
        TextView quality = (TextView) view.findViewById(R.id.channel_quality);
        TextView fav = (TextView) view.findViewById(R.id.channel_fav);
        TextView group = (TextView) view.findViewById(R.id.channel_group);

        name.setText(spec.name);
        quality.setText(spec.defnLabel());
        group.setText(ChannelTable.isTrue4k(spec.slug) ? "4K" : spec.group);
        fav.setVisibility(AppPrefs.get(context).isFavorite(spec.slug)
                ? View.VISIBLE : View.GONE);

        // 占位块：始终先铺好，台标到了再覆盖，避免闪白。
        placeholder.setText(initialOf(spec.name));
        placeholder.setBackgroundColor(colorFor(spec.slug));
        placeholder.setVisibility(View.VISIBLE);
        logo.setImageDrawable(null);

        Bitmap hit = LogoLoader.cached(spec.logo);
        if (hit != null) {
            logo.setTag(LogoLoader.TAG_URL, spec.logo);
            logo.setImageBitmap(hit);
            placeholder.setVisibility(View.GONE);
        } else {
            // 图片真正贴上去之后再隐藏占位（GONE 与 ImageView 同层，需按加载结果切换）。
            final TextView ph = placeholder;
            LogoLoader.load(spec.logo, logo, new Runnable() {
                @Override
                public void run() {
                    ph.setVisibility(View.GONE);
                }
            });
        }

        view.setContentDescription(spec.name + " " + spec.defnLabel());
        return view;
    }

    /** 取名称里第一个非 ASCII 字符（中文台名）或第一段字母数字。 */
    private static String initialOf(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "?";
        }
        // "CCTV-1 综合" -> "CC"，纯中文台名 -> 取前两字里的首字，视觉更清爽
        if (trimmed.length() >= 2 && isAscii(trimmed.charAt(0)) && isAscii(trimmed.charAt(1))) {
            return trimmed.substring(0, 2).toUpperCase(java.util.Locale.ROOT);
        }
        return trimmed.substring(0, 1);
    }

    private static boolean isAscii(char c) {
        return c < 0x80 && Character.isLetterOrDigit(c);
    }

    private static int colorFor(String slug) {
        if (slug == null || slug.isEmpty()) {
            return PLACEHOLDER_COLORS[0];
        }
        int hash = 0;
        for (int i = 0; i < slug.length(); i++) {
            hash = hash * 31 + slug.charAt(i);
        }
        return PLACEHOLDER_COLORS[Math.abs(hash) % PLACEHOLDER_COLORS.length];
    }

    /** 供调试用：把颜色稍微提亮，保证白字可读。 */
    static int brighten(int color) {
        int r = Math.min(255, Color.red(color) + 20);
        int g = Math.min(255, Color.green(color) + 20);
        int b = Math.min(255, Color.blue(color) + 20);
        return Color.rgb(r, g, b);
    }
}
