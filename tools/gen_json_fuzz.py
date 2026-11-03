"""模糊对拍: 随机生成大量 double, 导出 Python repr 风格 JSON, 供 Java 侧比对。

用法: python tools/gen_json_fuzz.py [count]
产物: tools/vectors-json-fuzz.json
"""
import json
import os
import random
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 3000
    rnd = random.Random(20241104)
    cases = []
    for _ in range(count):
        # 混合: 完全随机位模式 / 常见量级 / 短数字
        mode = rnd.randrange(4)
        if mode == 0:
            bits = rnd.getrandbits(64)
            d = struct.unpack('<d', struct.pack('<Q', bits))[0]
            if d != d or d in (float('inf'), float('-inf')):
                continue
        elif mode == 1:
            d = rnd.uniform(-1e6, 1e6)
        elif mode == 2:
            d = rnd.uniform(-1, 1) * (10 ** rnd.randrange(-20, 21))
        else:
            d = float(rnd.randrange(-1000000, 1000000)) / (10 ** rnd.randrange(0, 8))
        bits = struct.unpack('<Q', struct.pack('<d', d))[0]
        cases.append({
            'hexBits': '%016x' % bits,
            'value': json.dumps(d, separators=(',', ':'), ensure_ascii=False),
        })

    path = os.path.join(HERE, 'vectors-json-fuzz.json')
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump({'generatedBy': 'tools/gen_json_fuzz.py', 'floatCases': cases},
                  f, ensure_ascii=False, sort_keys=True)
        f.write('\n')
    print('wrote', path, len(cases), 'float cases')


if __name__ == '__main__':
    main()
