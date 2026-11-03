"""逐个频道对照：Python 参考服务器 vs 直连，判断失败是否属于上游问题。"""
import sys
sys.stdout.reconfigure(encoding='utf-8')
import urllib.request

CHANNELS = ['cctv17', 'cctv4k', 'cctv164k']


def fetch(url, headers=None, timeout=30):
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except Exception as e:
        body = b''
        try:
            body = e.read()
        except Exception:
            pass
        return getattr(e, 'code', -1), body


for slug in CHANNELS:
    print('=====', slug)
    st, body = fetch('http://127.0.0.1:8767/%s.m3u8' % slug)
    print('  Python 8767 playlist: HTTP', st, len(body), 'bytes')
    text = body.decode('utf-8', 'replace')
    segs = [ln.strip() for ln in text.splitlines()
            if ln.strip() and not ln.strip().startswith('#')]
    print('  segments:', len(segs))
    ok = 0
    for seg in segs[:3]:
        host = seg.split('/')[2] if '//' in seg else '?'
        st2, data = fetch(seg)
        mark = 'OK ' if st2 == 200 and data else 'BAD'
        print('   %s HTTP %-4s %9d B  host=%s' % (mark, st2, len(data), host))
        if st2 == 200 and data:
            ok += 1
    print('  -> 可播分片 %d/%d' % (ok, min(3, len(segs))))
