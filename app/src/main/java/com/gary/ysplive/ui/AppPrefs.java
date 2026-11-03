package com.gary.ysplive.ui;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 全局用户设置（SharedPreferences 持久化）。
 * <p>所有读写都在内存态与 SP 之间同步，调用方无需关心线程；这里只处理简单标量，
 * 不会阻塞 UI（SP 在 app 启动时一次性加载）。
 */
public final class AppPrefs {

    private static final String FILE = "ysp_live_prefs";

    /** 高码率模式：全部频道走设备协议。 */
    public static final String HB_ALL = "all";
    /** 高码率模式：只有 4K/8K 频道走设备协议。 */
    public static final String HB_ONLY_4K = "only4k";
    /** 高码率模式：完全关闭，全部走 1080p。 */
    public static final String HB_OFF = "off";

    private static final String K_PORT = "engine_port";
    private static final String K_LAN = "engine_allow_lan";
    private static final String K_CACHE = "engine_cache_mb";
    private static final String K_LINKS = "engine_links_per_device";
    private static final String K_ZAP = "engine_zap_debounce";
    private static final String K_HB = "engine_high_bitrate";
    private static final String K_BOOT = "engine_boot_autostart";
    private static final String K_FAV = "favorites";
    private static final String K_LAST = "last_channel";

    public static final int DEFAULT_PORT = 8767;
    public static final int DEFAULT_CACHE_MB = 200;
    public static final int DEFAULT_LINKS = 6;
    public static final double DEFAULT_ZAP_SEC = 10.0;

    private static AppPrefs instance;

    private final SharedPreferences sp;

    private AppPrefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static synchronized AppPrefs get(Context ctx) {
        if (instance == null) {
            instance = new AppPrefs(ctx);
        }
        return instance;
    }

    /** 已在其他进程/入口初始化过时可直接取；未初始化返回 null。 */
    public static synchronized AppPrefs peek() {
        return instance;
    }

    // ---------------- 引擎参数 ----------------

    public int port() {
        return clamp(sp.getInt(K_PORT, DEFAULT_PORT), 1024, 65535);
    }

    public void setPort(int value) {
        sp.edit().putInt(K_PORT, clamp(value, 1024, 65535)).apply();
    }

    public boolean allowLan() {
        return sp.getBoolean(K_LAN, false);
    }

    public void setAllowLan(boolean value) {
        sp.edit().putBoolean(K_LAN, value).apply();
    }

    public int cacheMb() {
        return clamp(sp.getInt(K_CACHE, DEFAULT_CACHE_MB), 16, 1024);
    }

    public void setCacheMb(int value) {
        sp.edit().putInt(K_CACHE, clamp(value, 16, 1024)).apply();
    }

    public int linksPerDevice() {
        return clamp(sp.getInt(K_LINKS, DEFAULT_LINKS), 0, 32);
    }

    public void setLinksPerDevice(int value) {
        sp.edit().putInt(K_LINKS, clamp(value, 0, 32)).apply();
    }

    public double zapDebounceSec() {
        double v = sp.getFloat(K_ZAP, (float) DEFAULT_ZAP_SEC);
        if (Double.isNaN(v) || v < 0) {
            return DEFAULT_ZAP_SEC;
        }
        return Math.min(v, 60.0);
    }

    public void setZapDebounceSec(double value) {
        float safe = (float) Math.max(0.0, Math.min(value, 60.0));
        sp.edit().putFloat(K_ZAP, safe).apply();
    }

    public String highBitrateMode() {
        String v = sp.getString(K_HB, HB_ALL);
        if (HB_ONLY_4K.equals(v) || HB_OFF.equals(v) || HB_ALL.equals(v)) {
            return v;
        }
        return HB_ALL;
    }

    public void setHighBitrateMode(String mode) {
        String v = (HB_ONLY_4K.equals(mode) || HB_OFF.equals(mode)) ? mode : HB_ALL;
        sp.edit().putString(K_HB, v).apply();
    }

    /** 高码率模式的中文标签。 */
    public String highBitrateLabel() {
        String mode = highBitrateMode();
        if (HB_OFF.equals(mode)) {
            return "关闭（全部 1080p）";
        }
        if (HB_ONLY_4K.equals(mode)) {
            return "仅 4K 频道";
        }
        return "全部频道";
    }

    public boolean disableHighBitrate() {
        return HB_OFF.equals(highBitrateMode());
    }

    public boolean only4kHighBitrate() {
        return HB_ONLY_4K.equals(highBitrateMode());
    }

    public boolean bootAutoStart() {
        return sp.getBoolean(K_BOOT, false);
    }

    public void setBootAutoStart(boolean value) {
        sp.edit().putBoolean(K_BOOT, value).apply();
    }

    // ---------------- 收藏 ----------------

    public Set<String> favorites() {
        Set<String> raw = sp.getStringSet(K_FAV, null);
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(raw);
    }

    public boolean isFavorite(String slug) {
        if (slug == null) {
            return false;
        }
        Set<String> raw = sp.getStringSet(K_FAV, null);
        return raw != null && raw.contains(slug);
    }

    /** 切换收藏状态，返回切换后的状态。 */
    public boolean toggleFavorite(String slug) {
        if (slug == null) {
            return false;
        }
        Set<String> next = new HashSet<>(favorites());
        boolean added;
        if (next.contains(slug)) {
            next.remove(slug);
            added = false;
        } else {
            next.add(slug);
            added = true;
        }
        sp.edit().putStringSet(K_FAV, next).apply();
        return added;
    }

    public void clearFavorites() {
        sp.edit().remove(K_FAV).apply();
    }

    public int favoriteCount() {
        return favorites().size();
    }

    // ---------------- 上次观看 ----------------

    public String lastChannelSlug() {
        String v = sp.getString(K_LAST, null);
        return v == null || v.isEmpty() ? null : v;
    }

    public void setLastChannelSlug(String slug) {
        if (slug == null || slug.isEmpty()) {
            sp.edit().remove(K_LAST).apply();
        } else {
            sp.edit().putString(K_LAST, slug).apply();
        }
    }

    // ---------------- 杂项 ----------------

    /**
     * 与引擎启动参数相关的设置指纹；两枚指纹不同就说明需要重启引擎。
     */
    public String engineSignature() {
        return "v1|" + port() + "|" + allowLan() + "|" + cacheMb() + "|"
                + linksPerDevice() + "|" + zapDebounceSec() + "|" + highBitrateMode();
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
