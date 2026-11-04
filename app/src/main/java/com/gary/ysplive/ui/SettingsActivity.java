package com.gary.ysplive.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.CompoundButton;
import android.widget.TextView;

import java.util.function.Consumer;

import com.gary.ysplive.R;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.play.EngineBus;
import com.gary.ysplive.play.GatewayProvider;

/**
 * 设置页。
 * <p>手写「分区 + 行」布局（不使用 PreferenceFragment），每行都 focusable，TV 遥控器可直接操作。
 * 所有改动立即写入 {@link AppPrefs}；影响引擎的行会提示重启引擎。
 */
public final class SettingsActivity extends Activity {

    private static final String TAG = "settings";

    private AppPrefs prefs;
    private LinearLayout container;

    /** 打开页面时的引擎参数指纹，用于判断「需要重启引擎」。 */
    private String signatureOnOpen;
    private boolean restartPending;

    public static void start(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.startActivity(new Intent(ctx, SettingsActivity.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "打开设置页失败：" + e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = AppPrefs.get(this);
        signatureOnOpen = prefs.engineSignature();
        container = (LinearLayout) findViewById(R.id.settings_container);

        findViewById(R.id.btn_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_logs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogsActivity.start(SettingsActivity.this);
            }
        });

        buildRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildRows();
    }

    // ---------------- 构建界面 ----------------

    private void buildRows() {
        container.removeAllViews();

        LinearLayout engine = addSection("引擎参数");
        addRow(engine, "引擎端口", "本地代理监听端口，被占用时自动向后查找",
                String.valueOf(prefs.port()), true, new Runnable() {
                    @Override
                    public void run() {
                        promptNumber("引擎端口", prefs.port(), 1024, 65535, new IntConsumer() {
                            @Override
                            public void accept(int value) {
                                prefs.setPort(value);
                                afterEngineSettingChanged();
                            }
                        });
                    }
                });
        addSwitchRow(engine, "允许局域网访问", "开启后同一 WiFi 下的电视盒子可订阅播放列表",
                prefs.allowLan(), new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean on) {
                        prefs.setAllowLan(on);
                        afterEngineSettingChanged();
                    }
                });
        addRow(engine, "分片缓存（MB）", "内存里缓存的直播分片大小",
                prefs.cacheMb() + " MB", true, new Runnable() {
                    @Override
                    public void run() {
                        promptNumber("分片缓存（MB）", prefs.cacheMb(), 16, 1024, new IntConsumer() {
                            @Override
                            public void accept(int value) {
                                prefs.setCacheMb(value);
                                afterEngineSettingChanged();
                            }
                        });
                    }
                });
        addRow(engine, "单设备并发路数", "0 表示关闭主动轮换",
                String.valueOf(prefs.linksPerDevice()), true, new Runnable() {
                    @Override
                    public void run() {
                        promptNumber("单设备并发路数", prefs.linksPerDevice(), 0, 32,
                                new IntConsumer() {
                                    @Override
                                    public void accept(int value) {
                                        prefs.setLinksPerDevice(value);
                                        afterEngineSettingChanged();
                                    }
                                });
                    }
                });
        addRow(engine, "换台防抖（秒）", "快速连续换台时延后解析，减少无效请求",
                String.format(java.util.Locale.ROOT, "%.1f 秒", prefs.zapDebounceSec()), true,
                new Runnable() {
                    @Override
                    public void run() {
                        promptDecimal("换台防抖（秒）", prefs.zapDebounceSec(), 0.0, 60.0,
                                new DoubleConsumer() {
                                    @Override
                                    public void accept(double value) {
                                        prefs.setZapDebounceSec(value);
                                        afterEngineSettingChanged();
                                    }
                                });
                    }
                });
        addRow(engine, "高码率模式", "设备协议清晰度策略；关闭后全部频道走 1080p",
                prefs.highBitrateLabel(), true, new Runnable() {
                    @Override
                    public void run() {
                        chooseHighBitrate();
                    }
                });
        addSwitchRow(engine, "开机自启", "开机后自动拉起直播引擎",
                prefs.bootAutoStart(), new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean on) {
                        prefs.setBootAutoStart(on);
                        refreshRows();
                    }
                });

        LinearLayout play = addSection("播放体验");
        addSwitchRow(play, "播放页强制横屏", "手机开着竖屏锁定时也能横屏看直播",
                prefs.playerForceLandscape(), new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean on) {
                        prefs.setPlayerForceLandscape(on);
                        refreshRows();
                    }
                });
        addSwitchRow(play, "播放页手势", "单击呼出控制条 · 双击播放暂停 · 左右滑换台",
                prefs.playerGestures(), new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean on) {
                        prefs.setPlayerGestures(on);
                        refreshRows();
                    }
                });

        LinearLayout status = addSection("运行状态");
        EngineBus.State st = EngineBus.state();
        addRow(status, "引擎状态", "", st.statusText(), false, null);
        Gateway gw = GatewayProvider.peek();
        String origin = "";
        if (gw != null) {
            try {
                Gateway.Snapshot snap = gw.snapshot();
                if (snap != null) {
                    origin = snap.localOrigin;
                }
            } catch (RuntimeException ignored) {
                // 快照不可用时展示空
            }
        }
        String ip = LocalNet.ipv4(this);
        addRow(status, "本机地址", "局域网内其他设备可用此地址订阅",
                ip.isEmpty() ? "未连接网络" : ip, false, null);
        final String playlistUrl = LocalNet.playlistUrl(ip, prefs.port());
        if (!playlistUrl.isEmpty()) {
            addRow(status, "局域网订阅地址", "点击复制到剪贴板", playlistUrl, true,
                    new Runnable() {
                        @Override
                        public void run() {
                            Ui.copyToClipboard(SettingsActivity.this, "ysp-playlist", playlistUrl);
                        }
                    });
        }
        if (!origin.isEmpty()) {
            addRow(status, "本地服务地址", "", origin, false, null);
        }
        addRow(status, "重启引擎", "修改引擎参数后需要重启才能生效", "点击重启", true,
                new Runnable() {
                    @Override
                    public void run() {
                        confirmRestartEngine();
                    }
                });

        LinearLayout favorites = addSection("收藏");
        addRow(favorites, "收藏数量", "", prefs.favoriteCount() + " 路", false, null);
        addRow(favorites, "清空收藏", "移除所有已收藏频道", "", true, new Runnable() {
            @Override
            public void run() {
                Ui.confirm(SettingsActivity.this, "清空收藏", "确定要清空全部收藏吗？",
                        new Runnable() {
                            @Override
                            public void run() {
                                prefs.clearFavorites();
                                Ui.toast(SettingsActivity.this, "已清空收藏");
                                refreshRows();
                            }
                        });
            }
        });

        LinearLayout tools = addSection("诊断");
        addRow(tools, "运行日志", "查看引擎与播放器的实时日志", "", true, new Runnable() {
            @Override
            public void run() {
                LogsActivity.start(SettingsActivity.this);
            }
        });
        addRow(tools, "清空日志", "清空内存中的日志缓冲", "", true, new Runnable() {
            @Override
            public void run() {
                Log.clear();
                Ui.toast(SettingsActivity.this, "日志已清空");
            }
        });
        addRow(tools, "引擎诊断", "查看端口、缓存命中率、云端身份等", "", true, new Runnable() {
            @Override
            public void run() {
                Ui.showDiagnostics(SettingsActivity.this);
            }
        });
        addRow(tools, "恢复默认设置", "仅重置引擎参数，不影响收藏", "", true, new Runnable() {
            @Override
            public void run() {
                Ui.confirm(SettingsActivity.this, "恢复默认设置",
                        "将端口、缓存、高码率等参数恢复为默认值（收藏保留）。",
                        new Runnable() {
                            @Override
                            public void run() {
                                resetDefaults();
                                afterEngineSettingChanged();
                            }
                        });
            }
        });

        addRow(tools, "版本", "", com.gary.ysplive.BuildConfig.VERSION_NAME + " · " + ChannelTable.all().size() + " 频道", false, null);
    }

    private void refreshRows() {
        buildRows();
    }

    private LinearLayout addSection(String title) {
        View section = LayoutInflater.from(this)
                .inflate(R.layout.item_settings_section, container, false);
        TextView tv = (TextView) section.findViewById(R.id.section_title);
        tv.setText(title);
        LinearLayout rows = (LinearLayout) section.findViewById(R.id.section_rows);
        container.addView(section);
        return rows;
    }

    private void addRow(LinearLayout parent, String title, String desc, String value,
                        boolean clickable, final Runnable click) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_settings_row, parent, false);
        TextView t = (TextView) row.findViewById(R.id.row_title);
        TextView d = (TextView) row.findViewById(R.id.row_desc);
        TextView v = (TextView) row.findViewById(R.id.row_value);
        TextView arrow = (TextView) row.findViewById(R.id.row_arrow);

        t.setText(title);
        if (TextUtils.isEmpty(desc)) {
            d.setVisibility(View.GONE);
        } else {
            d.setVisibility(View.VISIBLE);
            d.setText(desc);
        }
        if (TextUtils.isEmpty(value)) {
            v.setVisibility(View.GONE);
        } else {
            v.setVisibility(View.VISIBLE);
            v.setText(value);
        }
        arrow.setVisibility(clickable ? View.VISIBLE : View.GONE);
        row.setFocusable(clickable);
        row.setClickable(clickable);
        if (clickable && click != null) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    click.run();
                }
            });
        } else {
            row.setBackgroundResource(0);
        }
        parent.addView(row);
    }

    /**
     * 布尔型设置行：左侧说明 + 右侧内联开关。
     *
     * <p>比「点一下弹窗再确认」少两次交互，且状态一眼可见。
     * 触摸点整行任意位置都切换；遥控器把焦点放到开关上按确认键切换。
     * 回调里负责写 settings 并刷新界面。
     */
    private void addSwitchRow(LinearLayout parent, String title, String desc,
                              boolean checked, final Consumer<Boolean> onChanged) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.item_settings_switch, parent, false);
        TextView t = (TextView) row.findViewById(R.id.row_title);
        TextView d = (TextView) row.findViewById(R.id.row_desc);
        final Switch sw = (Switch) row.findViewById(R.id.row_switch);

        t.setText(title);
        if (TextUtils.isEmpty(desc)) {
            d.setVisibility(View.GONE);
        } else {
            d.setVisibility(View.VISIBLE);
            d.setText(desc);
        }
        sw.setChecked(checked);

        // 只在用户操作时回调；setChecked 触发的回调会被这个标记挡掉，
        // 否则刷新界面时会写回配置、造成反复重建。
        final boolean[] suppress = {true};
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (suppress[0]) {
                    return;
                }
                onChanged.accept(isChecked);
            }
        });
        suppress[0] = false;

        // 触摸：点整行也能切换（不用精确点中小开关）
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });

        parent.addView(row);
    }

    // ---------------- 交互 ----------------

    private void chooseHighBitrate() {
        final String[] modes = {AppPrefs.HB_ALL, AppPrefs.HB_ONLY_4K, AppPrefs.HB_OFF};
        final String[] labels = {"全部频道（最高画质）", "仅 4K 频道", "关闭（全部 1080p）"};
        int checked = 0;
        String current = prefs.highBitrateMode();
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(current)) {
                checked = i;
            }
        }
        Ui.choose(this, "高码率模式", labels, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which >= 0 && which < modes.length) {
                    prefs.setHighBitrateMode(modes[which]);
                }
                dialog.dismiss();
                afterEngineSettingChanged();
            }
        });
    }

    private void promptNumber(String title, int current, int min, int max,
                              final IntConsumer consumer) {
        final EditText input = buildNumberInput(String.valueOf(current));
        Ui.showSafely(this, new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int parsed;
                        try {
                            parsed = Integer.parseInt(input.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            Ui.toast(SettingsActivity.this, "请输入数字");
                            return;
                        }
                        if (parsed < min || parsed > max) {
                            Ui.toast(SettingsActivity.this, "范围应在 " + min + " — " + max);
                            return;
                        }
                        consumer.accept(parsed);
                    }
                })
                .create());
    }

    private void promptDecimal(String title, double current, double min, double max,
                               final DoubleConsumer consumer) {
        final EditText input = buildNumberInput(
                String.format(java.util.Locale.ROOT, "%.1f", current));
        Ui.showSafely(this, new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        double parsed;
                        try {
                            parsed = Double.parseDouble(input.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            Ui.toast(SettingsActivity.this, "请输入数字");
                            return;
                        }
                        if (parsed < min || parsed > max) {
                            Ui.toast(SettingsActivity.this,
                                    "范围应在 " + min + " — " + max);
                            return;
                        }
                        consumer.accept(parsed);
                    }
                })
                .create());
    }

    private EditText buildNumberInput(String value) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(value);
        input.setSelection(value.length());
        input.setTextColor(getResources().getColor(R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int pad = Ui.dp(this, 20);
        lp.setMargins(pad, pad / 2, pad, 0);
        input.setLayoutParams(lp);
        input.setGravity(Gravity.START);
        return input;
    }

    private void resetDefaults() {
        prefs.setPort(AppPrefs.DEFAULT_PORT);
        prefs.setAllowLan(false);
        prefs.setCacheMb(AppPrefs.DEFAULT_CACHE_MB);
        prefs.setLinksPerDevice(AppPrefs.DEFAULT_LINKS);
        prefs.setZapDebounceSec(AppPrefs.DEFAULT_ZAP_SEC);
        prefs.setHighBitrateMode(AppPrefs.HB_ALL);
        Ui.toast(this, "已恢复默认设置");
    }

    /** 引擎参数变化后的统一处理：刷新界面，并在需要时提示重启。 */
    private void afterEngineSettingChanged() {
        refreshRows();
        if (!prefs.engineSignature().equals(signatureOnOpen)) {
            if (!restartPending) {
                restartPending = true;
                Ui.confirm(this, "需要重启引擎", getString(R.string.tip_restart_needed),
                        new Runnable() {
                            @Override
                            public void run() {
                                restartEngine();
                            }
                        });
            }
        }
    }

    private void confirmRestartEngine() {
        Ui.confirm(this, "重启引擎", "重启会短暂中断当前播放，确定继续吗？", new Runnable() {
            @Override
            public void run() {
                restartEngine();
            }
        });
    }

    private void restartEngine() {
        restartPending = false;
        Log.i(TAG, "用户请求重启引擎");
        try {
            GatewayProvider.reset();
            EngineBus.restart(this);
            signatureOnOpen = prefs.engineSignature();
            Ui.toast(this, "正在重启引擎…");
        } catch (RuntimeException e) {
            Log.e(TAG, "重启引擎失败：" + e);
            Ui.toast(this, "重启失败：" + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private interface DoubleConsumer {
        void accept(double value);
    }
}
