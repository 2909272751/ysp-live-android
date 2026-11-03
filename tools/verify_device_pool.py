"""校验 DevicePool.java 是否逐字包含了参考实现的 DEVICE_PROFILE_POOL。"""
import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.path.join(ROOT, 'ysp-live-v9.5.py')
JAVA = os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'gary', 'ysplive',
                    'core', 'device', 'DevicePool.java')

FIELDS = ['source', 'brand', 'manufacturer', 'model', 'report_model',
          'hardware', 'board', 'version_id', 'screen_param', 'cast_model']


def load_ref():
    spec = importlib.util.spec_from_file_location('ysp_ref', REF)
    m = importlib.util.module_from_spec(spec)
    sys.modules['ysp_ref'] = m
    spec.loader.exec_module(m)
    return m


def main() -> int:
    m = load_ref()
    src = open(JAVA, encoding='utf-8').read()
    missing = []
    for t in m.DEVICE_PROFILE_POOL:
        for f in FIELDS:
            v = t[f].replace('\\', '\\\\').replace('"', '\\"')
            if ('"' + v + '"') not in src:
                missing.append((t['source'], f, t[f]))
    print('参考池条数:', len(m.DEVICE_PROFILE_POOL))
    print('缺失字段数:', len(missing))
    for x in missing[:10]:
        print('  缺失:', x)
    # 每条模板都要有 all.add(new Template(...)) 且顺序一致
    import re
    order = re.findall(r'all\.add\(new Template\("([^"]+)"', src)
    ref_order = [t['source'] for t in m.DEVICE_PROFILE_POOL]
    print('生成条数:', len(order))
    print('顺序一致:', order == ref_order)
    if order != ref_order:
        for i, (a, b) in enumerate(zip(order, ref_order)):
            if a != b:
                print('  首个顺序差异 @', i, a, '!=', b)
                break
    return 0 if (not missing and order == ref_order) else 1


if __name__ == '__main__':
    sys.exit(main())
