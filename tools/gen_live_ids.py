"""从参考实现提取 channels() 里的 live_id 表，生成 Java 源。

`live/v1/01` 的 `id` 字段要的是 Live ID（例如 Live1717729995180256），
**不是** ChannelTable 里的 sid（2024078201）。两者是不同体系的编号，混用会 400。

用法: python C:\\1\\ysp-android\\tools\\gen_live_ids.py
产出: core/src/main/java/com/gary/ysplive/core/device/LiveIds.java
"""
from __future__ import annotations

import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.path.join(ROOT, 'ysp-live-v9.5.py')
OUT = os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'gary', 'ysplive',
                   'core', 'device', 'LiveIds.java')

HEADER = '''package com.gary.ysplive.core.device;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 频道 slug → 央视频 <b>Live ID</b> 映射。
 *
 * <p><b>不要和 {@code ChannelSpec.sid} 搞混</b>：
 * <ul>
 *   <li>{@code sid}（如 {@code 2024078201}）是 JCE 时移/bkliveinfo 用的频道编号</li>
 *   <li>Live ID（如 {@code Live1717729995180256}）是 {@code live/v1/01} 接口要的直播间编号</li>
 * </ul>
 * 拿 sid 去请求 {@code live/v1/01} 会直接返回 {@code {"result":"400","data":{}}}，
 * 且不带任何错误说明 —— 这个坑踩过一次。
 *
 * <p>表逐字提取自参考实现 ysp-live-v9.5.py 的 {@code channels()}
 * （机器生成，勿手工编辑；要改请改 tools/gen_live_ids.py）。
 */
public final class LiveIds {

    private LiveIds() { }

    private static final Map<String, String> MAP;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>(__COUNT__);
__ENTRIES__
        MAP = Collections.unmodifiableMap(m);
    }

    /** 全部 slug → Live ID。 */
    public static Map<String, String> all() {
        return MAP;
    }

    /**
     * 取某频道的 Live ID。
     *
     * @param slug 频道 slug（会用 {@code ChannelTable.canonical} 归一）
     * @return Live ID；未知频道返回空串
     */
    public static String forSlug(String slug) {
        if (slug == null) {
            return "";
        }
        String key = com.gary.ysplive.core.data.ChannelTable.canonical(slug);
        String v = MAP.get(key);
        if (v != null) {
            return v;
        }
        v = MAP.get(slug.trim().toLowerCase(java.util.Locale.ROOT));
        return v == null ? "" : v;
    }

    /** 该频道是否有 Live ID（即是否走设备协议）。 */
    public static boolean has(String slug) {
        return !forSlug(slug).isEmpty();
    }
}
'''


def load_ref():
    spec = importlib.util.spec_from_file_location('ysp_ref', REF)
    m = importlib.util.module_from_spec(spec)
    sys.modules['ysp_ref'] = m
    spec.loader.exec_module(m)
    return m


def jstr(s: str) -> str:
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def main() -> int:
    m = load_ref()
    pairs = m.channels()
    lines = []
    for slug, live_id in pairs:
        lines.append('        m.put(%s, %s);' % (jstr(slug), jstr(live_id)))
    src = (HEADER.replace('__COUNT__', str(len(pairs)))
                 .replace('__ENTRIES__', '\n'.join(lines)))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(src)
    print('wrote', OUT)
    print('channels =', len(pairs))
    for slug, live_id in pairs:
        print('  %-10s %s' % (slug, live_id))
    return 0


if __name__ == '__main__':
    sys.exit(main())
