import sys, glob, os
import xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8')

d = os.path.join(r'C:\1\ysp-android', 'core', 'build', 'test-results', 'testDebugUnitTest')
tot = f = e = 0
rows = []
for p in sorted(glob.glob(os.path.join(d, '*.xml'))):
    t = ET.parse(p).getroot()
    n = int(t.get('tests') or 0)
    ff = int(t.get('failures') or 0)
    ee = int(t.get('errors') or 0)
    tot += n
    f += ff
    e += ee
    name = (t.get('name') or '').split('.')[-1]
    secs = float(t.get('time') or 0)
    rows.append((name, n, ff, ee, secs))
    for tc in t.iter('testcase'):
        bad = tc.find('failure') if tc.find('failure') is not None else tc.find('error')
        if bad is not None:
            rows.append(('  FAIL: ' + tc.get('name'), 0, 0, 0, 0.0))
            rows.append(('     ' + (bad.get('message') or '')[:200].replace('\n', ' | '), 0, 0, 0, 0.0))

for name, n, ff, ee, secs in rows:
    if n or ff or ee:
        print('%-34s %4d tests  %d fail  %d err  %6.2fs' % (name, n, ff, ee, secs))
    else:
        print(name)
print()
print('TOTAL %d tests, %d failed, %d errors, %d passed' % (tot, f, e, tot - f - e))
