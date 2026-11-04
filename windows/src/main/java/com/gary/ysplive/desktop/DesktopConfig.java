package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 桌面版配置（持久化到 {@code %APPDATA%\YspLive\settings.properties}）。
 */
final class DesktopConfig {

    private static final String DIR_NAME = "YspLive";
    private static final String FILE_NAME = "settings.properties";

    private final Properties props = new Properties();
    private final Path file;

    int enginePort = 8767;
    boolean allowLan = false;
    int cacheMb = 200;
    int linksPerDevice = 6;
    double zapDebounceSec = 10.0;
    String highBitrateMode = "all";     // all | only4k | off
    boolean autoStartEngine = true;
    boolean fullScreenPlayer = false;
    /** 播放器音量（0-130，libVLC 允许到 130%）。 */
    int playerVolume = 100;
    String lastChannel = "";
    String filter = "全部";
    int windowWidth = 1120;
    int windowHeight = 760;
    /** 窗口位置；-1 表示未记录过（首次启动居中显示）。 */
    double windowX = -1;
    double windowY = -1;
    boolean windowMaximized = false;
    final List<String> favorites = new ArrayList<>();

    private DesktopConfig(Path file) {
        this.file = file;
    }

    static DesktopConfig load() {
        Path dir = dataDir();
        Path f = dir.resolve(FILE_NAME);
        DesktopConfig cfg = new DesktopConfig(f);
        if (Files.isRegularFile(f)) {
            try (java.io.InputStream in = Files.newInputStream(f)) {
                cfg.props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w("desktop", "读取配置失败：" + e.getMessage());
            }
        }
        cfg.readFromProps();
        return cfg;
    }

    /** 配置与数据目录：%APPDATA%\YspLive。 */
    static Path dataDir() {
        String appData = System.getenv("APPDATA");
        Path base;
        if (appData != null && !appData.trim().isEmpty()) {
            base = Paths.get(appData);
        } else {
            base = Paths.get(System.getProperty("user.home"), ".config");
        }
        Path dir = base.resolve(DIR_NAME);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            Log.w("desktop", "无法创建配置目录：" + e.getMessage());
        }
        return dir;
    }

    private void readFromProps() {
        enginePort = intOf("engine.port", enginePort);
        allowLan = boolOf("engine.allowLan", allowLan);
        cacheMb = intOf("engine.cacheMb", cacheMb);
        linksPerDevice = intOf("engine.linksPerDevice", linksPerDevice);
        zapDebounceSec = doubleOf("engine.zapDebounceSec", zapDebounceSec);
        highBitrateMode = props.getProperty("engine.highBitrate", highBitrateMode);
        autoStartEngine = boolOf("app.autoStart", autoStartEngine);
        fullScreenPlayer = boolOf("player.fullscreen", fullScreenPlayer);
        playerVolume = intOf("player.volume", playerVolume);
        lastChannel = props.getProperty("ui.lastChannel", lastChannel);
        filter = props.getProperty("ui.filter", filter);
        windowWidth = intOf("ui.width", windowWidth);
        windowHeight = intOf("ui.height", windowHeight);
        windowX = doubleOf("ui.x", windowX);
        windowY = doubleOf("ui.y", windowY);
        windowMaximized = boolOf("ui.maximized", windowMaximized);
        String fav = props.getProperty("favorites", "");
        favorites.clear();
        for (String s : fav.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                favorites.add(t);
            }
        }
    }

    void save() {
        props.setProperty("engine.port", String.valueOf(enginePort));
        props.setProperty("engine.allowLan", String.valueOf(allowLan));
        props.setProperty("engine.cacheMb", String.valueOf(cacheMb));
        props.setProperty("engine.linksPerDevice", String.valueOf(linksPerDevice));
        props.setProperty("engine.zapDebounceSec", String.valueOf(zapDebounceSec));
        props.setProperty("engine.highBitrate", highBitrateMode == null ? "all" : highBitrateMode);
        props.setProperty("app.autoStart", String.valueOf(autoStartEngine));
        props.setProperty("player.fullscreen", String.valueOf(fullScreenPlayer));
        props.setProperty("player.volume", String.valueOf(playerVolume));
        props.setProperty("ui.lastChannel", lastChannel == null ? "" : lastChannel);
        props.setProperty("ui.filter", filter == null ? "全部" : filter);
        props.setProperty("ui.width", String.valueOf(windowWidth));
        props.setProperty("ui.height", String.valueOf(windowHeight));
        props.setProperty("ui.x", String.valueOf(windowX));
        props.setProperty("ui.y", String.valueOf(windowY));
        props.setProperty("ui.maximized", String.valueOf(windowMaximized));
        props.setProperty("favorites", String.join(",", favorites));
        try {
            Files.createDirectories(file.getParent());
            try (java.io.OutputStream out = Files.newOutputStream(file)) {
                props.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8),
                        "YSP Live Desktop settings");
            }
        } catch (Exception e) {
            Log.w("desktop", "保存配置失败：" + e.getMessage());
        }
    }

    // ---------------- 收藏 ----------------

    boolean isFavorite(String slug) {
        return slug != null && favorites.contains(slug);
    }

    boolean toggleFavorite(String slug) {
        if (slug == null) {
            return false;
        }
        if (favorites.remove(slug)) {
            save();
            return false;
        }
        favorites.add(slug);
        save();
        return true;
    }

    // ---------------- 转为引擎配置 ----------------

    Gateway.Config toGatewayConfig() {
        Gateway.Config c = new Gateway.Config();
        c.port = enginePort;
        c.allowLan = allowLan;
        c.cacheMb = cacheMb;
        c.linksPerDevice = linksPerDevice;
        c.zapDebounceSec = zapDebounceSec;
        c.disableHighBitrate = "off".equals(highBitrateMode);
        c.only4kHighBitrate = "only4k".equals(highBitrateMode);
        c.timeoutSec = 10.0;
        c.dataDir = dataDir().toFile();
        return c;
    }

    // ---------------- 工具 ----------------

    private int intOf(String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private double doubleOf(String key, double def) {
        try {
            return Double.parseDouble(props.getProperty(key, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private boolean boolOf(String key, boolean def) {
        return Boolean.parseBoolean(props.getProperty(key, String.valueOf(def)).trim());
    }

    /** 按当前筛选返回频道列表。 */
    List<ChannelSpec> filteredChannels() {
        List<ChannelSpec> all = ChannelTable.all();
        List<ChannelSpec> out = new ArrayList<>();
        String f = filter == null ? "全部" : filter;
        for (ChannelSpec s : all) {
            boolean keep;
            switch (f) {
                case "央视":
                    keep = "央视".equals(s.group);
                    break;
                case "卫视":
                    keep = "卫视".equals(s.group);
                    break;
                case "4K":
                    keep = ChannelTable.isTrue4k(s.slug) || s.name.contains("4K")
                            || s.name.contains("8K");
                    break;
                case "收藏":
                    keep = isFavorite(s.slug);
                    break;
                default:
                    keep = true;
            }
            if (keep) {
                out.add(s);
            }
        }
        return out;
    }
}
