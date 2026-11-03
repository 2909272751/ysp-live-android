package com.gary.ysplive.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gary.ysplive.R;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.play.EngineBus;
import com.gary.ysplive.play.GatewayProvider;

/**
 * 轻量 UI 工具：dp 换算、chip 构造、统一风格的对话框、诊断面板、剪贴板。
 * <p>全部手写控件，不依赖 AppCompat/Material。
 */
final class Ui {

    private Ui() {
    }

    static int dp(Context ctx, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics()));
    }

    static void toast(Context ctx, String text) {
        if (ctx == null || text == null) {
            return;
        }
        try {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) {
            // 窗口已销毁等极端情况，忽略
        }
    }

    /**
     * 造一个 filter chip。
     * <p><b>不要</b>调 {@code setFocusableInTouchMode(true)}：那会让 {@code View.onTouchEvent}
     * 在首次点击时只 {@code requestFocus()} 而<b>不</b> {@code performClick()}，
     * 手机上表现为「点一下没反应、要点两下」，电视上因为焦点已在控件上所以看不出问题。
     * 这里只保留 {@code setFocusable(true)}——它在触屏模式下照样能点击，且能被 D-pad 聚焦。
     */
    static TextView makeChip(Context ctx, String text, View.OnClickListener click) {
        TextView chip = new TextView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(ctx, 8);
        chip.setLayoutParams(lp);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8));
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setTextColor(ctx.getResources().getColorStateList(R.color.chip_text));
        chip.setFocusable(true);
        chip.setClickable(true);
        if (click != null) {
            chip.setOnClickListener(click);
        }
        return chip;
    }

    static void setChipSelected(TextView chip, boolean selected) {
        if (chip != null) {
            chip.setSelected(selected);
        }
    }

    static void copyToClipboard(Context ctx, String label, String text) {
        if (ctx == null || text == null) {
            return;
        }
        try {
            ClipboardManager cm =
                    (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                toast(ctx, ctx.getString(R.string.tip_copied));
            }
        } catch (RuntimeException e) {
            Log.w("ui", "复制到剪贴板失败：" + e);
        }
    }

    // ---------------- 对话框 ----------------

    private static AlertDialog.Builder builder(Context ctx) {
        AlertDialog.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b = new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert);
        } else {
            b = new AlertDialog.Builder(ctx);
        }
        return b;
    }

    /** 简单确认框；{@code onOk} 可为 null。 */
    static void confirm(Context ctx, String title, String message,
                        final Runnable onOk) {
        AlertDialog.Builder b = builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null);
        if (onOk != null) {
            b.setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    onOk.run();
                }
            });
        } else {
            b.setPositiveButton(R.string.confirm, null);
        }
        showSafely(ctx, b.create());
    }

    /** 单选列表对话框。 */
    static void choose(Context ctx, String title, String[] options, int checked,
                       final DialogInterface.OnClickListener listener) {
        AlertDialog dlg = builder(ctx)
                .setTitle(title)
                .setSingleChoiceItems(options, checked, listener)
                .setNegativeButton(R.string.cancel, null)
                .create();
        showSafely(ctx, dlg);
    }

    static void showSafely(Context ctx, AlertDialog dialog) {
        if (dialog == null || ctx == null) {
            return;
        }
        if (ctx instanceof Activity && ((Activity) ctx).isFinishing()) {
            return;
        }
        try {
            dialog.show();
        } catch (RuntimeException e) {
            Log.w("ui", "对话框显示失败：" + e);
        }
    }

    // ---------------- 诊断面板 ----------------

    /** 引擎诊断对话框：状态 + 快照 + 本地地址 + 直达日志/设置。 */
    static void showDiagnostics(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        EngineBus.State st = EngineBus.state();
        Gateway gw = GatewayProvider.peek();
        Gateway.Snapshot snap = gw != null ? safeSnapshot(gw) : st.snapshot;

        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(st.statusText()).append('\n');
        sb.append("引擎实现：")
                .append(GatewayProvider.isUsingStub() ? "占位（core 未就绪）" : "真实引擎")
                .append('\n');
        if (!GatewayProvider.stubReason().isEmpty()) {
            sb.append("说明：").append(GatewayProvider.stubReason()).append('\n');
        }
        sb.append('\n');
        if (snap == null) {
            sb.append("暂无运行快照");
        } else {
            sb.append("运行中：").append(snap.running ? "是" : "否").append('\n');
            sb.append("端口：").append(snap.port).append('\n');
            sb.append("本机地址：").append(emptyTo(snap.localOrigin, "—")).append('\n');
            sb.append("高码率协议：").append(snap.highBitrateReady ? "已就绪" : "未就绪（1080p）")
                    .append('\n');
            sb.append("频道数：").append(snap.channelCount).append('\n');
            sb.append("运行时长：").append(formatDuration(snap.uptimeMs)).append('\n');
            sb.append("分片缓存：").append(snap.cacheEntries).append(" 片，命中率 ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f%%",
                            snap.cacheHitRate * 100.0)).append('\n');
            sb.append("云端 GUID：").append(emptyTo(snap.cloudGuid, "—")).append('\n');
            sb.append("心跳次数：").append(snap.heartbeatCount).append('\n');
            if (!snap.lastError.isEmpty()) {
                sb.append("最近错误：").append(snap.lastError).append('\n');
            }
        }
        final String allPlaylist = gw == null ? "" : safeString(new StringGetter() {
            @Override
            public String get() {
                Gateway g = GatewayProvider.peek();
                return g == null ? "" : g.getAllPlaylistUrl();
            }
        });
        sb.append('\n').append("订阅地址：").append(emptyTo(allPlaylist, "（引擎未就绪）"));

        AlertDialog.Builder b = builder(activity)
                .setTitle(R.string.diagnostics_title)
                .setMessage(sb.toString())
                .setNegativeButton(R.string.menu_logs, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        LogsActivity.start(activity);
                    }
                })
                .setPositiveButton(R.string.copy_all, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Ui.copyToClipboard(activity, "ysp-diagnostics", sb.toString());
                    }
                });
        showSafely(activity, b.create());
    }

    private interface StringGetter {
        String get();
    }

    private static String safeString(StringGetter getter) {
        try {
            return getter.get();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Gateway.Snapshot safeSnapshot(Gateway gw) {
        try {
            return gw.snapshot();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    static String formatDuration(long ms) {
        if (ms <= 0) {
            return "0 秒";
        }
        long totalSec = ms / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(h).append(" 小时 ");
        }
        if (h > 0 || m > 0) {
            sb.append(m).append(" 分 ");
        }
        sb.append(s).append(" 秒");
        return sb.toString();
    }

    /** 生成一个纯文本滚动面板（日志/长文本用）。 */
    static View buildTextPanel(Context ctx, CharSequence text, int textSizeSp) {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        tv.setTextColor(ctx.getResources().getColor(R.color.text_primary));
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
        tv.setTextIsSelectable(true);
        scroll.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    static String joinLines(java.util.List<String> lines) {
        return TextUtils.join("\n", lines);
    }

    /** 给 Button 应用自定义背景（不同 API 上 AlertDialog 按钮样式会覆盖，这里兜底）。 */
    static void styleButton(Button button) {
        if (button != null) {
            button.setAllCaps(false);
        }
    }
}
