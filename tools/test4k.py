import sys, base64, urllib.request
sys.stdout.reconfigure(encoding='utf-8')

pl = open(r'C:\1\ysp-android\cctv4k.m3u8', encoding='utf-8').read()
tok = [l for l in pl.splitlines() if '/proxy.ts?' in l][0].split('/proxy.ts?=')[1].strip()
raw = base64.urlsafe_b64decode(tok + '=' * (-len(tok) % 4))
print('4K real url:', raw.decode()[:150])
url = raw.decode()

for label, hdrs in [
    ('no-header', {}),
    ('ua', {'User-Agent': 'Mozilla/5.0'}),
]:
    try:
        req = urllib.request.Request(url, headers=hdrs)
        with urllib.request.urlopen(req, timeout=20) as r:
            d = r.read()
            print(f'{label}: OK {r.status} {len(d)}B ct={r.headers.get("Content-Type")}')
    except Exception as e:
        print(f'{label}: FAIL {type(e).__name__} {e}')

# Also fetch the 4K m3u8 via the local proxy and look at what headers the server used
print()
print('=== check device state file ===')
import json, os
p = r'C:\1\ysp-android\data\device-state-rs.json'
if os.path.exists(p):
    d = json.load(open(p, encoding='utf-8'))
    print('keys:', list(d.keys()))
    print('profile:', json.dumps(d.get('profile'), ensure_ascii=False)[:300])
