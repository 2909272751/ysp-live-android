"""确认 live/v1/01 的 400 是不是 CDN 负缓存（响应里的 time 字段被冻住）。

如果 `time` 字段一直不变、且远早于当前时间，说明是缓存住的错误响应，
而不是我们的请求有问题。
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys
import time

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
    app_channel, version = 'dangbei', '1.4.1'
    identity = m.build_identity(profile, app_channel, version)
    client = m.HttpClient(10.0, False)

    token_time = m.current_time_ms()
    xfp, ts, _ = m.compute_fingerprint(identity.x_uid, token_time)
    identity.x_fingerprint = xfp
    identity.headers['X-Fingerprint'] = xfp
    body = m.build_app_start_body(profile, identity.x_uid, app_channel, version, m.current_time_ms())
    headers = m.fresh_headers(identity.headers, 'application/json', 'application/json', ts)
    headers['UID'] = ''
    resp = client.post_bytes(m.APP_START_URL, headers, m.compact_json_bytes(body, True))
    print('app/start ->', resp.status, ' time字段 =', json.loads(resp.text()).get('time'))
    print('本机当前 ms =', m.current_time_ms())
    print()

    live_id = m.channel_by_name('cctv4k')
    print('连续打 5 次 live/v1/01，观察响应 time 字段是否变化：')
    for i in range(5):
        b1 = {'screenParam': st.screen_param, 'rate': '', 'systemType': 'ios',
              'model': st.cast_model, 'id': live_id,
              'userId': m.DEFAULT_LIVE_USER_ID, 'clientSign': 'cctvVideo',
              'deviceId': {'serial': '', 'imei': '', 'android_id': ''}}
        h1 = m.fresh_headers(identity.headers, 'application/json', 'application/json', None)
        r = client.post_json(m.LIVE_V1_01_URL, h1, b1)
        txt = r.text()
        try:
            t = json.loads(txt).get('time')
        except Exception:
            t = '?'
        print('  #%d HTTP %s  time=%s  %s' % (i + 1, r.status, t, txt[:110]))
        time.sleep(1.0)
    return 0


if __name__ == '__main__':
    sys.exit(main())
