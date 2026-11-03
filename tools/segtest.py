import sys
sys.stdout.reconfigure(encoding='utf-8')
import re, sys, urllib.request

pl = open(r'C:\1\ysp-android\cctv1.m3u8', encoding='utf-8').read()
segs = [l.strip() for l in pl.splitlines() if l.strip() and not l.startswith('#')]
print('segments:', len(segs))
url = segs[0]
print('URL head:', url[:120])

UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36'

def try_fetch(label, headers):
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = r.read()
            print(f'{label}: OK {r.status} {len(data)} bytes  ct={r.headers.get("Content-Type")}')
            return True
    except Exception as e:
        print(f'{label}: FAIL {type(e).__name__} {e}')
        return False

try_fetch('no-header', {})
try_fetch('ua-only', {'User-Agent': UA})
try_fetch('ua+referer', {'User-Agent': UA, 'Referer': 'https://live.cctv.cn/'})

# also test bkliveinfo's mobilelive CDN seg (cctv6)
pl2 = open(r'C:\1\ysp-android\cctv6.m3u8', encoding='utf-8').read()
segs2 = [l.strip() for l in pl2.splitlines() if l.strip() and not l.startswith('#')]
print('\ncctv6 segments:', len(segs2))
url = segs2[0]
print('URL head:', url[:120])
try_fetch('bk-no-header', {})
try_fetch('bk-ua', {'User-Agent': 'qqlive'})
