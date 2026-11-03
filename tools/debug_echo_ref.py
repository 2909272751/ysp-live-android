"""把参考实现的同一批请求打到本地回显服务器，用于和 Java 逐字节比对。"""
from __future__ import annotations

import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.path.join(ROOT, 'ysp-live-v9.5.py')
BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8799'


def load_ref():
    spec = importlib.util.spec_from_file_location('ysp_ref', REF)
    m = importlib.util.module_from_spec(spec)
    sys.modules['ysp_ref'] = m
    spec.loader.exec_module(m)
    return m


def main() -> int:
    m = load_ref()
    # 固定成与 Java 侧可复现的部分：device 用同一台
    template = m.DEVICE_PROFILE_POOL[20]  # lg_8k_pool.OLED88Z2PCA
    profile = m.device_profile_from_template(template, '0123456789abcdef', 'aa:bb:cc:dd:ee:ff')
    app_channel, version = 'dangbei', '1.4.1'
    identity = m.build_identity(profile, app_channel, version)
    client = m.HttpClient(8.0, False)

    h = m.fresh_headers(identity.headers, 'application/json', 'application/json', 1700000000000)
    h['UID'] = ''
    body = m.build_app_start_body(profile, identity.x_uid, app_channel, version, 1700000000000)
    resp = client.post_json(BASE + '/app/start', h, body)
    print('app/start ->', resp.status, flush=True)

    b1 = {'screenParam': '7680-4320-320', 'rate': '', 'systemType': 'ios',
          'model': 'OLED88Z2PCA', 'id': 'Live1717729995180256',
          'userId': m.DEFAULT_LIVE_USER_ID, 'clientSign': 'cctvVideo',
          'deviceId': {'serial': '', 'imei': '', 'android_id': ''}}
    h1 = m.fresh_headers(identity.headers, 'application/json', 'application/json', None)
    resp = client.post_json(BASE + '/live/v1/01', h1, b1)
    print('live/v1/01 ->', resp.status, flush=True)

    vh = m.fresh_headers(identity.headers, 'application/x-www-form-urlencoded', None, None)
    vh['APPID'] = m.AK
    vh['APPSIGN'] = '0123456789abcdef0123456789abcdef'
    vh['APPRANDOMSTR'] = '00000000-0000-0000-0000-000000000000'
    appcommon = m.build_vdn_appcommon(version)
    resp = client.post_form(BASE + '/vdn', vh, [('appcommon', appcommon),
                                                ('url', 'http://example.com/a.m3u8?x=1&y=2')])
    print('vdn ->', resp.status, flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
