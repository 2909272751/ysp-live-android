"""从参考实现逐字提取 DEVICE_PROFILE_POOL，生成 Java 源。

用法: python C:\\1\\ysp-android\\tools\\gen_device_pool.py
产出: core/src/main/java/com/gary/ysplive/core/device/DevicePool.java
"""
from __future__ import annotations

import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.path.join(ROOT, 'ysp-live-v9.5.py')
OUT = os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'gary', 'ysplive',
                   'core', 'device', 'DevicePool.java')

FIELDS = ['source', 'brand', 'manufacturer', 'model', 'report_model',
          'hardware', 'board', 'version_id', 'screen_param', 'cast_model']


def load_ref():
    spec = importlib.util.spec_from_file_location('ysp_ref', REF)
    m = importlib.util.module_from_spec(spec)
    sys.modules['ysp_ref'] = m
    spec.loader.exec_module(m)
    return m


def jstr(s: str) -> str:
    out = []
    for ch in s:
        if ch == '"':
            out.append('\\"')
        elif ch == '\\':
            out.append('\\\\')
        elif ch == '\n':
            out.append('\\n')
        elif ch == '\t':
            out.append('\\t')
        elif ord(ch) < 0x20:
            out.append('\\u%04x' % ord(ch))
        else:
            out.append(ch)
    return '"' + ''.join(out) + '"'


HEADER = '''package com.gary.ysplive.core.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 设备档案模板池（随机电视身份）。
 * <p>{@link #POOL} 逐字复制自参考实现 ysp-live-v9.5.py 的 {@code DEVICE_PROFILE_POOL}
 * （机器生成，请勿手工增删改条目；要改请改 tools/gen_device_pool.py 后重新生成）。
 * <p>共 __COUNT__ 条，其中 __COUNT8K__ 条是 8K 电视（{@code source} 里带 {@code 8k}）。
 * {@link #randomDeviceTemplate()} 优先从 8K 池里抽，这样 {@code screenParam} 才够 7680-4320，
 * 央视频才会下发 4K/8K 码率。
 */
public final class DevicePool {

    private DevicePool() { }

    /** 一条设备模板，字段与 Python 字典一一对应。 */
    public static final class Template {
        public final String source;
        public final String brand;
        public final String manufacturer;
        public final String model;
        public final String reportModel;
        public final String hardware;
        public final String board;
        public final String versionId;
        public final String screenParam;
        public final String castModel;

        Template(String source, String brand, String manufacturer, String model, String reportModel,
                 String hardware, String board, String versionId, String screenParam, String castModel) {
            this.source = source;
            this.brand = brand;
            this.manufacturer = manufacturer;
            this.model = model;
            this.reportModel = reportModel;
            this.hardware = hardware;
            this.board = board;
            this.versionId = versionId;
            this.screenParam = screenParam;
            this.castModel = castModel;
        }

        /** 是否 8K 机型（对应 Python 的 {@code '8k' in source}）。 */
        public boolean is8k() {
            return source.toLowerCase(Locale.ROOT).contains("8k");
        }

        @Override
        public String toString() {
            return source + " (" + brand + " " + model + ")";
        }
    }

    /** 设备档案模板池。 */
    public static final List<Template> POOL;

    /** 只含 8K 机型的子池。 */
    public static final List<Template> POOL_8K;

    static {
        List<Template> all = new ArrayList<Template>(__COUNT__);
__ENTRIES__
        POOL = Collections.unmodifiableList(all);

        List<Template> eightK = new ArrayList<Template>();
        for (Template t : all) {
            if (t.is8k()) {
                eightK.add(t);
            }
        }
        POOL_8K = Collections.unmodifiableList(eightK);
    }

    /**
     * 随机取一条模板；优先 8K 池（与 Python {@code random_device_template} 一致）。
     */
    public static Template randomDeviceTemplate() {
        List<Template> src = POOL_8K.isEmpty() ? POOL : POOL_8K;
        return src.get(ThreadLocalRandom.current().nextInt(src.size()));
    }

    /**
     * 按 {@code source} 精确取模板；找不到返回 {@code null}。
     * <p>用于从磁盘恢复设备身份时保持机型不变。
     */
    public static Template bySource(String source) {
        if (source == null) {
            return null;
        }
        for (Template t : POOL) {
            if (t.source.equals(source)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 把档案 id 归一：只保留 ASCII 字母数字并小写，其余连续段折叠成单个下划线，首尾下划线去掉。
     * <p>对应 Python {@code sanitize_profile_id}。
     */
    public static String sanitizeProfileId(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean asciiAlnum = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
            if (asciiAlnum) {
                out.append(Character.toLowerCase(ch));
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                out.append('_');
                lastUnderscore = true;
            }
        }
        String s = out.toString();
        int b = 0;
        int e = s.length();
        while (b < e && s.charAt(b) == '_') {
            b++;
        }
        while (e > b && s.charAt(e - 1) == '_') {
            e--;
        }
        return s.substring(b, e);
    }

    /**
     * 把 {@code screen_param}（如 {@code 7680-4320-280}）转成 {@code 7680*2160} 形式的宽高。
     * <p>对应 Python {@code resolution_from_screen_param}：取前两段，缺省 {@code 3840*2160}。
     */
    public static String resolutionFromScreenParam(String screenParam) {
        if (screenParam != null) {
            String[] parts = screenParam.split("-", -1);
            if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                return parts[0] + "*" + parts[1];
            }
        }
        return "3840*2160";
    }
}
'''


def main() -> int:
    m = load_ref()
    pool = m.DEVICE_PROFILE_POOL
    count = len(pool)
    count8k = sum(1 for t in pool if '8k' in t.get('source', ''))

    lines = []
    for t in pool:
        vals = ', '.join(jstr(t[f]) for f in FIELDS)
        lines.append('        all.add(new Template(%s));' % vals)
    entries = '\n'.join(lines)

    src = (HEADER.replace('__COUNT__', str(count))
                 .replace('__COUNT8K__', str(count8k))
                 .replace('__ENTRIES__', entries))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(src)
    print('wrote', OUT)
    print('templates =', count, ', 8k =', count8k)
    return 0


if __name__ == '__main__':
    sys.exit(main())
