import sys, glob, os, subprocess, zipfile
sys.stdout.reconfigure(encoding='utf-8')

APK = r'C:\1\ysp-android\app\build\outputs\apk\debug\YspLive-v9.5-debug.apk'
DEXDUMP = r'C:\AndroidSdkCli\build-tools\34.0.0\dexdump.exe'

need = [
    ('androidx/media3/ui/PlayerView', 'PlayerView 控件'),
    ('androidx/media3/exoplayer/ExoPlayer', 'ExoPlayer 接口'),
    ('androidx/media3/exoplayer/ExoPlayerImpl', 'ExoPlayer 实现'),
    ('androidx/media3/exoplayer/hls/HlsMediaSource', 'HLS 媒体源'),
    ('androidx/media3/exoplayer/DefaultLoadControl', '缓冲控制'),
    ('androidx/media3/datasource/DefaultHttpDataSource', 'HTTP 数据源'),
    ('androidx/media3/exoplayer/source/DefaultMediaSourceFactory', '媒体源工厂'),
    ('com/gary/ysplive/ui/MainActivity', '主界面'),
    ('com/gary/ysplive/ui/PlayerActivity', '播放页'),
    ('com/gary/ysplive/play/EngineService', '引擎服务'),
    ('com/gary/ysplive/core/engine/YspGateway', '引擎门面'),
]

tmp = r'C:\1\ysp-android\_chk\dexall.txt'
with zipfile.ZipFile(APK) as z:
    dexes = [n for n in z.namelist() if n.endswith('.dex')]
print('dex 文件:', dexes)
chunks = []
for d in dexes:
    out = subprocess.run([DEXDUMP, '-l', 'plain', os.path.join(os.path.dirname(APK), '..', '..', '..', '..', '..', d)],
                         capture_output=True, text=True, errors='replace')
    chunks.append(out.stdout)
# dexdump needs real paths; extract first
import tempfile
tmpdir = tempfile.mkdtemp()
with zipfile.ZipFile(APK) as z:
    for d in dexes:
        z.extract(d, tmpdir)
blob = []
for d in dexes:
    out = subprocess.run([DEXDUMP, '-l', 'plain', os.path.join(tmpdir, d)],
                         capture_output=True, text=True, errors='replace')
    blob.append(out.stdout)
text = '\n'.join(blob)
print('dex 反汇编总字符数:', len(text))
print()
ok = 0
for cls, desc in need:
    found = ('L' + cls + ';') in text
    if found:
        ok += 1
    print(('  OK   ' if found else '  MISS ') + desc.ljust(16) + ' ' + cls)
print()
print('%d/%d 关键类存在' % (ok, len(need)))
sys.exit(0 if ok == len(need) else 1)
