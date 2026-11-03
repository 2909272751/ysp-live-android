"""对照实验：同一时刻，Python 参考服务器给出的 cctv4k / cctv164k 分片能否真的取到。"""
import sys
sys.stdout.reconfigure(encoding='utf-8')
import urllib.request

UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36'


def fetch(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except Exception as e:
        body = b''
        try:
            body = e.read()
        except Exception:
            pass
        return getattr(e, 'code', -1), body


for slug in ('cctv4k', 'cctv164k', 'cctv8k'):
    print('=====', slug)
    st, body = fetch('http://127.0.0.1:8767/%s.m3u8' % slug)
    print('  playlist from Python 8767: HTTP', st, len(body), 'bytes')
    if st != 200:
        continue
    text = body.decode('utf-8', 'replace')
    segs = [ln.strip() for ln in text.splitlines()
            if ln.strip() and not ln.strip().startswith('#')]
    print('  segments:', len(segs))
    if not segs:
        continue
    seg = segs[0]
    host = seg.split('/')[2]
    print('  first seg host:', host)
    st2, data = fetch(seg)
    print('  fetch segment: HTTP', st2, len(data), 'bytes',
          'sync=0x%02X' % data[0] if data else '')
