"""用**已注册**的设备身份（8767 服务正在用的那个）去打 live/v1/01。

目的：区分"新注册的设备被风控"和"我们的请求格式有问题"。
如果这个已注册身份能成功，说明格式没问题，是新身份被限流。
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.path.join(ROOT, 'ysp-live-v9.5.py')
DEVICE_JSON = os.path.join(ROOT, 'data', 'device-state-rs.json')


def load_ref():
    spec = importlib.util.spec_from_file_location('ysp_ref', REF)
    m = importlib.util.module_from_spec(spec)
    sys.modules['ysp_ref'] = m
    spec.loader.exec_module(m)
    return m


def main() -> int:
    m = load_ref()
    if not os.path.isfile(DEVICE_JSON):
        print('缺少', DEVICE_JSON)
        return 1
    data = json.load(open(DEVICE_JSON, encoding='utf-8'))
    profile = m.DeviceProfile.from_dict(data.get('profile', {}))
    screen_param = data.get('screen_param') or '7680-4320-280'
    cast_model = data.get('cast_model') or profile.model
    cloud_guid = data.get('cloud_guid') or ''
    print('复用身份: %s %s  android_id=%s' % (profile.brand, profile.model, profile.android_id))
    print('  screen_param =', screen_param, ' cast_model =', cast_model)
    print('  cloud_guid   =', cloud_guid)
    print()

    app_channel, version = 'dangbei', '1.4.1'
    identity = m.build_identity(profile, app_channel, version)
    client = m.HttpClient(10.0, False)

    # app/start 拿 session_key
    token_time = m.current_time_ms()
    xfp, ts, _ = m.compute_fingerprint(identity.x_uid, token_time)
    identity.x_fingerprint = xfp
    identity.fingerprint_timestamp_ms = ts
    identity.headers['X-Fingerprint'] = xfp
    body = m.build_app_start_body(profile, identity.x_uid, app_channel, version, m.current_time_ms())
    headers = m.fresh_headers(identity.headers, 'application/json', 'application/json', ts)
    headers['UID'] = ''
    resp = client.post_bytes(m.APP_START_URL, headers, m.compact_json_bytes(body, True))
    print('app/start ->', resp.status)
    value = m.json.loads(resp.text())
    data2 = value.get('data') or {}
    enc = data2.get('key', '') if isinstance(data2, dict) else ''
    session_key = m.aes_gcm_decrypt_b64(enc, xfp[:32])
    print('session_key =', session_key)
    print()

    # live/v1/01 —— 用这个已注册身份
    for slug in ('cctv1', 'cctv4k'):
        live_id = m.channel_by_name(slug)
        b1 = {'screenParam': screen_param, 'rate': '', 'systemType': 'ios',
              'model': cast_model, 'id': live_id,
              'userId': m.DEFAULT_LIVE_USER_ID, 'clientSign': 'cctvVideo',
              'deviceId': {'serial': '', 'imei': '', 'android_id': ''}}
        h1 = m.fresh_headers(identity.headers, 'application/json', 'application/json', None)
        r = client.post_json(m.LIVE_V1_01_URL, h1, b1)
        print('%s (live_id=%s) -> live/v1/01 HTTP %s' % (slug, live_id, r.status))
        if r.status == 200:
            v = m.json.loads(r.text())
            vids = (v.get('data') or {}).get('videoList') or []
            print('   成功! videoList 数量 =', len(vids),
                  ' rates =', [x.get('rate') for x in vids])
        else:
            print('   ', r.text()[:200])
        print()
    return 0


if __name__ == '__main__':
    sys.exit(main())
