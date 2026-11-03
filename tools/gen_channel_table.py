"""Generate Java constants/channel table from the reference ysp-live-v9.5.py."""
import sys, io, json, os
sys.stdout.reconfigure(encoding='utf-8')

REF = r'C:\1\ysp-android\ysp-live-v9.5.py'
OUT = r'C:\1\ysp-android\core\src\main\java\com\gary\ysplive\core\data'

os.makedirs(OUT, exist_ok=True)

import importlib.util
import types
spec = importlib.util.spec_from_file_location('yspref', REF)
m = importlib.util.module_from_spec(spec)
sys.modules['yspref'] = m
spec.loader.exec_module(m)

def jstr(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '') + '"'

live_ids = dict(m.channels())
print('live_ids:', len(live_ids))

chans = []
for slug, name, sid, pid, defn in m.DEFAULT_CHANNELS:
    grp = m.get_channel_group(slug)
    tid = m.TVG_IDS.get(slug, slug.upper())
    logo = m.LOGO_BASE + '/' + slug + '.png'
    chans.append(dict(slug=slug, name=name, sid=sid, pid=pid, defn=defn,
                      group=grp, tvgId=tid, logo=logo,
                      liveId=live_ids.get(slug, '')))
print('channels:', len(chans))

aliases = dict(m.ALIAS_MAP)
force_bk = sorted(m.FORCE_BK)
timeshift = sorted(m.TIMESHIFT_SUPPORTED)
backend = sorted(m.BACKEND_CHANNELS)
true4k = sorted(m.TRUE_4K_CHANNELS)

lines = []
w = lines.append
w('package com.gary.ysplive.core.data;')
w('')
w('import java.util.ArrayList;')
w('import java.util.Collections;')
w('import java.util.LinkedHashMap;')
w('import java.util.List;')
w('import java.util.Map;')
w('')
w('/**')
w(' * 频道清单与静态常量。')
w(' * <p>由 ysp-live v9.5 的 {@code DEFAULT_CHANNELS} / {@code ALIAS_MAP} / {@code TVG_IDS} 等')
w(' * 常量表机器生成，请勿手工修改条目顺序（顺序即界面默认排序）。')
w(' */')
w('public final class ChannelTable {')
w('')
w('    private ChannelTable() { }')
w('')
w('    public static final String LOGO_BASE = ' + jstr(m.LOGO_BASE) + ';')
w('')
w('    private static final String[][] RAW = {')
for c in chans:
    w('        {%s, %s, %s, %s, %s, %s, %s, %s},' % (
        jstr(c['slug']), jstr(c['name']), jstr(c['sid']), jstr(c['pid']),
        jstr(c['defn']), jstr(c['group']), jstr(c['tvgId']), jstr(c['liveId'])))
w('    };')
w('')
w('    private static final Map<String, String> ALIAS_RAW = new LinkedHashMap<>();')
w('    static {')
for k, v in sorted(aliases.items()):
    w('        ALIAS_RAW.put(%s, %s);' % (jstr(k), jstr(v)))
w('    }')
w('')
w('    private static final java.util.Set<String> FORCE_BK = new java.util.HashSet<>();')
w('    private static final java.util.Set<String> TIMESHIFT = new java.util.HashSet<>();')
w('    private static final java.util.Set<String> BACKEND = new java.util.HashSet<>();')
w('    private static final java.util.Set<String> TRUE_4K = new java.util.HashSet<>();')
w('    static {')
for s in force_bk:
    w('        FORCE_BK.add(%s);' % jstr(s))
for s in timeshift:
    w('        TIMESHIFT.add(%s);' % jstr(s))
for s in backend:
    w('        BACKEND.add(%s);' % jstr(s))
for s in true4k:
    w('        TRUE_4K.add(%s);' % jstr(s))
w('    }')
w('')
w('    private static final List<ChannelSpec> ALL;')
w('    private static final Map<String, ChannelSpec> BY_SLUG;')
w('    static {')
w('        List<ChannelSpec> all = new ArrayList<>(RAW.length);')
w('        Map<String, ChannelSpec> bySlug = new LinkedHashMap<>(RAW.length * 2);')
w('        for (String[] r : RAW) {')
w('            ChannelSpec spec = new ChannelSpec(r[0], r[1], r[2], r[3], r[4], r[5], r[6],')
w('                    LOGO_BASE + "/" + r[0] + ".png", r[7]);')
w('            all.add(spec);')
w('            bySlug.put(spec.slug, spec);')
w('        }')
w('        ALL = Collections.unmodifiableList(all);')
w('        BY_SLUG = Collections.unmodifiableMap(bySlug);')
w('    }')
w('')
w('    /** 全部频道，顺序即默认展示顺序。 */')
w('    public static List<ChannelSpec> all() {')
w('        return ALL;')
w('    }')
w('')
w('    /** 按 slug 精确查找（不处理别名），找不到返回 null。 */')
w('    public static ChannelSpec bySlug(String slug) {')
w('        return slug == null ? null : BY_SLUG.get(slug);')
w('    }')
w('')
w('    /** 归一化频道名：去掉首尾空白与斜杠、去 .m3u8 后缀、转小写，并把 cctv16-4k 等写法归一。 */')
w('    public static String normalize(String raw) {')
w('        if (raw == null) {')
w('            return "";')
w('        }')
w('        String key = raw.trim();')
w('        while (key.startsWith("/")) {')
w('            key = key.substring(1);')
w('        }')
w('        while (key.endsWith("/")) {')
w('            key = key.substring(0, key.length() - 1);')
w('        }')
w('        key = key.toLowerCase(java.util.Locale.ROOT);')
w('        if (key.endsWith(".m3u8")) {')
w('            key = key.substring(0, key.length() - 5);')
w('        }')
w('        if (key.equals("cctv16-4k") || key.equals("cctv16_4k")')
w('                || key.equals("cctv16/4k") || key.equals("cctv16(4k)")) {')
w('            return "cctv164k";')
w('        }')
w('        return key;')
w('    }')
w('')
w('    /** 归一化并解析别名，得到规范 slug。 */')
w('    public static String canonical(String raw) {')
w('        String key = normalize(raw);')
w('        String alias = ALIAS_RAW.get(key);')
w('        return alias != null ? alias : key;')
w('    }')
w('')
w('    /** 归一化 + 别名 + 查表；找不到返回 null。 */')
w('    public static ChannelSpec lookup(String raw) {')
w('        ChannelSpec spec = bySlug(canonical(raw));')
w('        if (spec != null) {')
w('            return spec;')
w('        }')
w('        return bySlug(normalize(raw));')
w('    }')
w('')
w('    public static boolean isForceBk(String slug) {')
w('        return FORCE_BK.contains(slug);')
w('    }')
w('')
w('    public static boolean supportsTimeshift(String slug) {')
w('        return TIMESHIFT.contains(slug);')
w('    }')
w('')
w('    public static boolean isBackendChannel(String slug) {')
w('        return BACKEND.contains(slug);')
w('    }')
w('')
w('    public static boolean isTrue4k(String slug) {')
w('        return TRUE_4K.contains(slug);')
w('    }')
w('')
w('    public static List<ChannelSpec> group(String group) {')
w('        List<ChannelSpec> out = new ArrayList<>();')
w('        for (ChannelSpec s : ALL) {')
w('            if (s.group.equals(group)) {')
w('                out.add(s);')
w('            }')
w('        }')
w('        return out;')
w('    }')
w('}')
w('')

path = os.path.join(OUT, 'ChannelTable.java')
with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write('\n'.join(lines))
print('wrote', path, os.path.getsize(path), 'bytes')

# also dump a JSON snapshot for the test harness / UI to diff against
snap = dict(channels=chans, aliases=aliases, forceBk=force_bk, timeshift=timeshift,
            backend=backend, true4k=true4k, logoBase=m.LOGO_BASE)
with open(r'C:\1\ysp-android\tools\channels-snapshot.json', 'w', encoding='utf-8') as f:
    json.dump(snap, f, ensure_ascii=False, indent=1)
print('snapshot written')
