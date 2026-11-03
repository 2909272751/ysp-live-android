import sys
sys.stdout.reconfigure(encoding='utf-8')
import importlib.util
spec = importlib.util.spec_from_file_location('r', r'C:\1\ysp-android\ysp-live-v9.5.py')
m = importlib.util.module_from_spec(spec)
sys.modules['r'] = m
spec.loader.exec_module(m)

targets = {
    'cctv4k': ('2029810301', '600002264', 'fhd'),
    'cctv8k': ('2026774101', '600156816', 'fhd'),
    'cctv6':  ('2013693901', '600108442', 'fhd'),
}
for slug, (sid, pid, defn) in targets.items():
    print('=====', slug, 'sid=', sid, 'pid=', pid, 'defn=', defn)
    try:
        urls = m.bk_playurls(sid, pid, defn)
        print('  bk_playurls OK, count=', len(urls))
        for u in urls[:3]:
            print('   ', u[:150])
        try:
            pl = m.fetch_abs_playlist(urls[0])
            print('  playlist OK, lines=', len(pl.splitlines()))
            for ln in pl.splitlines()[:4]:
                print('     ', ln[:150])
        except Exception as e:
            print('  playlist FAIL:', type(e).__name__, str(e)[:200])
    except Exception as e:
        print('  bk_playurls FAIL:', type(e).__name__, str(e)[:300])
