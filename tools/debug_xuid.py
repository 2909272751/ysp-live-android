"""打印参考实现算 X-Uid 的中间量，用于与 Java 侧逐项比对。"""
from __future__ import annotations

import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.path.join(ROOT, 'ysp-live-v9.5.py')


def load_ref():
    spec = importlib.util.spec_from_file_location('ysp_ref', REF)
    m = importlib.util.module_from_spec(spec)
    sys.modules['ysp_ref'] = m
    spec.loader.exec_module(m)
    return m


def main() -> int:
    m = load_ref()
    template = m.DEVICE_PROFILE_POOL[20]
    p = m.device_profile_from_template(template, '0123456789abcdef', 'aa:bb:cc:dd:ee:ff')
    print('profile fields:')
    for f in ('android_id', 'mac', 'hardware', 'board', 'brand', 'device', 'manufacturer',
              'model', 'product', 'tags', 'build_type', 'user', 'resolution', 'display',
              'version_id', 'host', 'fingerprint', 'report_model'):
        print('  %-15s = %r' % (f, getattr(p, f)))

    build_identity_str = ('1698' + p.hardware + p.board + p.brand + p.device
                          + p.manufacturer + p.model + p.product + p.tags
                          + p.build_type + p.user + p.resolution + p.mac)
    print()
    print('build_identity_str =', repr(build_identity_str))
    h1 = m.java_string_hashcode(build_identity_str)
    h2 = m.java_string_hashcode(p.model)
    print('hash(build_identity_str) =', h1, hex(h1 & 0xFFFFFFFF))
    print('hash(model)              =', h2, hex(h2 & 0xFFFFFFFF))
    uuid_part = m.java_uuid_from_hashes(h1, h2)
    print('uuid_part =', uuid_part)
    print('x_uid     =', m.compute_x_uid(p))
    return 0


if __name__ == '__main__':
    sys.exit(main())
