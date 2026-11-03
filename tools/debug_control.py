"""对照实验：
1) 用一个明显不存在的 live_id 打 live/v1/01，看 400 的 time 字段。
2) 用正确 live_id，但加 cache-buster 查询参数。

若"错误 id"与"正确 id"返回同一个冻结 time，说明该 time 只是服务端常量，
400 是真实结论（频道下线/风控）；若只有正确 id 是冻结值，那是负缓存。
"""
from __future__ import annotations

import importlib.util
import json
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
    st = m.default_device_state()
    profile = st.profile
    identity = m.build_identity(profile, 'dangbei', '1.4.1')
    client = m.HttpClient(10.0, False)
    token_time = m.current_time_ms()
    xfp, ts, _ = m.compute_fingerprint(identity.x_uid, token_time)
    identity.x_fingerprint = xfp
    identity.headers['X-Fingerprint'] = xfp
    body = m.build_app_start_body(profile, identity.x_uid, 'dangbei', '1.4.1',
                                  m.current_time_ms())
    headers = m.fresh_headers(identity.headers, 'application/json', 'application/json', ts)
    headers['UID'] = ''
    client.post_bytes(m.APP_START_URL, headers, m.compact_json_bytes(body, True))

    def hit(live_id, url=None, label=''):
        b1 = {'screenParam': st.screen_param, 'rate': '', 'systemType': 'ios',
              'model': st.cast_model, 'id': live_id,
              'userId': m.DEFAULT_LIVE_USER_ID, 'clientSign': 'cctvVideo',
              'deviceId': {'serial': '', 'imei': '', 'android_id': ''}}
        h1 = m.fresh_headers(identity.headers, 'application/json', 'application/json', None)
        r = client.post_json(url or m.LIVE_V1_01_URL, h1, b1)
        try:
            t = json.loads(r.text()).get('time')
        except Exception:
            t = '?'
        print('  %-28s HTTP %s  time=%s' % (label, r.status, t))
        return r

    print('--- 对照：不同 live_id ---')
    hit('Live1717729995180256', label='cctv1 (真实 id)')
    hit('Live0000000000000000', label='伪造 id')
    hit('garbage', label='垃圾 id')
    hit('', label='空 id')
    print()
    print('--- cache-buster 查询参数 ---')
    for i in range(3):
        import time as _t
        u = m.LIVE_V1_01_URL + '?_t=%d' % (m.current_time_ms() + i)
        hit('Live1717729995180256', url=u, label='cctv1 +_t #%d' % (i + 1))
    print()
    print('--- 不同 screenParam（可能进 cache key）---')
    for sp in ('3840-2160-260', '1920-1080-240'):
        b1 = {'screenParam': sp, 'rate': '', 'systemType': 'ios',
              'model': st.cast_model, 'id': 'Live1717729995180256',
              'userId': m.DEFAULT_LIVE_USER_ID, 'clientSign': 'cctvVideo',
              'deviceId': {'serial': '', 'imei': '', 'android_id': ''}}
        h1 = m.fresh_headers(identity.headers, 'application/json', 'application/json', None)
        r = client.post_json(m.LIVE_V1_01_URL, h1, b1)
        try:
            t = json.loads(r.text()).get('time')
        except Exception:
            t = '?'
        print('  screenParam=%-14s HTTP %s  time=%s' % (sp, r.status, t))
    return 0


if __name__ == '__main__':
    sys.exit(main())
