import sys
sys.stdout.reconfigure(encoding='utf-8')
targets = [
    'com/gary/ysplive/core/engine/YspGateway;',
    'com/gary/ysplive/play/GatewayProvider;',
    'com/gary/ysplive/ui/MainActivity;',
    'com/gary/ysplive/ui/PlayerActivity;',
    'com/gary/ysplive/ui/SettingsActivity;',
    'com/gary/ysplive/ui/LogsActivity;',
    'com/gary/ysplive/play/EngineService;',
    'com/gary/ysplive/play/StubGateway;',
    'com/gary/ysplive/core/server/LocalServer;',
    'com/gary/ysplive/core/device/DeviceResolver;',
    'com/gary/ysplive/core/channel/ChannelEngine;',
    'com/gary/ysplive/core/util/Json;',
    'com/gary/ysplive/core/util/AesGcm;',
    'com/gary/ysplive/core/jce/JceRequest;',
    'com/gary/ysplive/core/data/ChannelTable;',
    'androidx/media3/exoplayer/hls/HlsMediaSource;',
]
path = sys.argv[1] if len(sys.argv) > 1 else r'C:\1\ysp-android\_dexchk\dex2.txt'
text = open(path, encoding='utf-8', errors='replace').read()
print('dex dump chars:', len(text))
for t in targets:
    print(('FOUND  ' if t in text else 'MISSING') + '  ' + t)
