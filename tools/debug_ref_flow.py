"""用参考实现自己的 Resolver 打一遍 live/v1/01，打印真实请求与响应。

这样可以确定"能跑通"的请求到底长什么样，不用猜。
不碰 8767 上的服务（独立设备状态文件、不启服务）。
"""
from __future__ import annotations

import argparse
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

    # 拦截 post_json / post_form / post_bytes，打印请求体与响应
    orig_json = m.HttpClient.post_json
    orig_form = m.HttpClient.post_form
    orig_bytes = m.HttpClient.post_bytes

    def show(tag, url, headers, data, resp):
        print('=' * 70)
        print(tag, url)
        print('  req headers:', json.dumps({k: v for k, v in headers.items()}, ensure_ascii=False))
        try:
            print('  req body   :', data.decode('utf-8')[:1200])
        except Exception:
            print('  req body   : <binary>', len(data))
        print('  resp status:', resp.status)
        print('  resp body  :', resp.text()[:1500])
        print()

    def post_json(self, url, headers, body):
        resp = orig_json(self, url, headers, body)
        show('POST-JSON', url, headers,
             json.dumps(body, separators=(',', ':'), ensure_ascii=False, sort_keys=True).encode(),
             resp)
        return resp

    m.HttpClient.post_json = post_json

    # 用独立设备状态文件，避开正在运行的服务
    data_dir = os.path.join(ROOT, '_probe', 'devdbg')
    os.makedirs(data_dir, exist_ok=True)
    engine_args = argparse.Namespace(
        host='127.0.0.1', port=0, timeout=10.0, insecure_tls=False, cache_ttl=60.0,
        stale_while_refresh_ttl=0.0, refresh_error_cooldown=5.0, playlist_cache_ttl=2.0,
        background_refresh_queue_limit=4, http_workers=16, http_queue_limit=1000,
        identity_reset_error_threshold=0, identity_reset_cooldown=300.0, refresh_interval=0.0,
        control_step_jitter_min_ms=0, control_step_jitter_max_ms=0, heartbeat_interval=30.0,
        heartbeat_ttl_guard=60.0, session_ttl=7200.0,
        meta_json=os.path.join(data_dir, 'meta.json'),
        device_json=os.path.join(data_dir, 'device.json'),
        links_per_device=6, zap_debounce=0.0,
    )
    resolver = m.Resolver(engine_args)
    target = sys.argv[1] if len(sys.argv) > 1 else 'cctv1'
    try:
        sess = resolver.ensure_slot_session(0)
        print('### session ok, android_id =', sess.profile.android_id,
              'screen =', sess.screen_param, 'cast =', sess.cast_model)
        live_id = m.channel_by_name(target)
        print('### live_id for', target, '=', live_id)
        entry = resolver.resolve_channel_once(sess, target, live_id, 1)
        print()
        print('### RESULT')
        print('  final_url =', entry.final_url)
        print('  rate      =', entry.rate, entry.rate_name)
        print('  host      =', entry.final_host)
        print('  headers   =', json.dumps(entry.playback_headers, ensure_ascii=False))
    except Exception as e:
        print('### FAILED:', type(e).__name__, e)
        import traceback
        traceback.print_exc()
    return 0


if __name__ == '__main__':
    sys.exit(main())
