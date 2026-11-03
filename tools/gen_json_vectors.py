"""从参考实现 ysp-live-v9.5.py 的 compact_json_bytes / json.dumps 生成 JSON 序列化向量。

用法: python tools/gen_json_vectors.py
产物: tools/vectors-json.json

每个 case 形如:
  {"name": "...", "pythonValue": <用 Python 字面量描述的值, JSON 可表示>,
   "stringify": "...", "stringifyEscapingSlashes": "..."}
Java 侧 JsonTest 会构造等价对象再比对。

注意: 浮点 fixture 用 JSON 传输会丢精度，所以这里同时给出 hex 形式的 double 位模式，
Java 侧用 Double.longBitsToDouble 还原，保证比的是同一个 double。
"""
import importlib.util
import json
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
REF = os.path.join(ROOT, 'ysp-live-v9.5.py')


def load_reference():
    spec = importlib.util.spec_from_file_location('ysp_ref_json', REF)
    mod = importlib.util.module_from_spec(spec)
    sys.modules['ysp_ref_json'] = mod
    spec.loader.exec_module(mod)
    return mod


def dumps(value):
    return json.dumps(value, separators=(',', ':'), ensure_ascii=False, sort_keys=True)


def slash(v):
    return dumps(v).replace('/', '\\/')


def case(name, value):
    return {
        'name': name,
        'stringify': dumps(value),
        'stringifyEscapingSlashes': slash(value),
        'compactJsonBytes': None,
    }


def main():
    ref = load_reference()

    cases = []

    # --- 标量 ---
    cases.append(case('empty-object', {}))
    cases.append(case('empty-array', []))
    cases.append(case('int', 42))
    cases.append(case('negative-int', -7))
    cases.append(case('zero', 0))
    cases.append(case('true', True))
    cases.append(case('false', False))
    cases.append(case('null', None))
    cases.append(case('string-ascii', 'hello'))
    cases.append(case('string-empty', ''))
    cases.append(case('string-slash', 'a/b/c'))
    cases.append(case('string-cjk', '央视频电视投屏助手'))
    cases.append(case('string-escapes', 'q"b\\s\tt\tn\nl\rr'))
    cases.append(case('string-control', ''.join(chr(c) for c in range(32))))
    cases.append(case('string-del', chr(0x7F)))
    cases.append(case('string-emoji', '\U0001F600'))
    cases.append(case('string-unicode-sp', '\u2028\u2029'))
    cases.append(case('string-accent', '\u00e9\u00e8\u00ea'))

    # --- 排序 ---
    cases.append(case('sort-ascii', {'b': 2, 'a': 1, 'c': 3, 'A': 4, 'Z': 5, '0': 6}))
    cases.append(case('sort-across-types', {'z': 1, '中': 2, 'a': 3, '_': 4, '-': 5}))
    cases.append(case('sort-nested', {'z': {'y': 1, 'x': 2}, 'a': {'d': 4, 'c': 3}}))

    # --- 结构 ---
    cases.append(case('array-of-mixed', [1, 'two', True, None, 3.5, [4], {'e': 5}]))
    cases.append(case('deep-nest', {'a': {'b': {'c': {'d': [1, {'e': 2}]}}}}))
    cases.append(case('empty-string-key', {'': 1, 'a': 2}))
    cases.append(case('numbers-in-array', [0, 1, -1, 1000000, 1234567890123]))

    # --- 真实形状: app_start 请求体 ---
    cases.append(case('app-start-body', {
        'key': 'app_start_d1',
        'value': {
            'cctv_id': 'BAEBFF2B-C516-4F34-ABC0-A824A6461CBD',
            'device_id': 'abc123',
            'idfa': '', 'idfv': '', 'user_id': '', 'imei': '',
            'app_key': '1178c84d-4818-44ff-b415-02106e87e144',
            'mac': 'aa:bb:cc:dd:ee:ff',
            'device_resolution': '3840*2160',
            'system_type': 'Android', 'device_type': 'TV',
            'app_language': 'CHINESE', 'app_version': '1.0.0',
            'data_time': '1717729995180',
        }
    }))
    # --- 真实形状: report common value（含 URL，用来验 slash 转义） ---
    cases.append(case('report-with-url', {
        'app_key': '1178c84d-4818-44ff-b415-02106e87e144',
        'url': 'https://ytpaddr.cctv.cn/gsnw/api/app/start/v1/01?a=1&b=2',
        'referer': 'https://live.cctv.cn/',
    }))

    # --- 浮点（额外给 hex 位模式） ---
    floats = [1.0, 0.5, 3.0, -0.0, 2.5, 1 / 3, 0.1, 123.456, 1e16, 1e15, 1e17,
              1e-4, 1e-5, 1e-6, 1e-7, 1.5e-7, 1e20, float(2 ** 53),
              1.0000000000000002, 123456789.123, 0.0001, 0.00001]
    float_cases = []
    for f in floats:
        bits = struct.unpack('<Q', struct.pack('<d', f))[0]
        float_cases.append({'hexBits': '%016x' % bits,
                            'value': dumps(f),
                            'inObject': dumps({'f': f})})

    payload = {
        'generatedBy': 'tools/gen_json_vectors.py via ysp-live-v9.5.py json.dumps',
        'separators': ',:',
        'ensureAscii': False,
        'sortKeys': True,
        'cases': cases,
        'floatCases': float_cases,
    }
    path = os.path.join(HERE, 'vectors-json.json')
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(payload, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write('\n')
    print('wrote', path, os.path.getsize(path), 'bytes,', len(cases), 'cases,',
          len(float_cases), 'float cases')


if __name__ == '__main__':
    main()
