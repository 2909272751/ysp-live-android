"""从参考实现 ysp-live-v9.5.py 自身生成 AES-GCM / TEA / RSA-OAEP / 表单编码测试向量。

用法: python tools/gen_crypto_vectors.py

产物（供 Java 单元测试读取，断言 Java 与 Python 逐字节一致）:
  tools/vectors-aesgcm.json   AES-256-GCM: key/nonce/plaintext/aad -> ciphertext+tag
  tools/vectors-tea.json      TEA 单块加密 + _cksum（确定性部分）
  tools/vectors-rsa.json      RSA-OAEP-SHA256（确定性 seed -> 逐字节可比）
  tools/vectors-form.json     form_encode 逐字节

注意: 本脚本直接 import 参考实现（不复制它的算法），所以"对照"是真对照。
"""
import base64
import importlib.util
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
REF = os.path.join(ROOT, 'ysp-live-v9.5.py')


def load_reference():
    spec = importlib.util.spec_from_file_location('ysp_ref', REF)
    mod = importlib.util.module_from_spec(spec)
    # Python 3.13+/3.14 下 dataclass 需要模块已在 sys.modules 里，否则报
    # AttributeError: 'NoneType' object has no attribute '__dict__'
    sys.modules['ysp_ref'] = mod
    spec.loader.exec_module(mod)
    return mod


def h(b):
    return b.hex()


def gen_aesgcm(ref):
    cases = []
    # 覆盖: 空明文 / 恰好一个块 / 跨块 / 非整块 / 较长的中文 UTF-8 / 显式 AAD
    fixtures = [
        (bytes(range(32)), bytes(range(12)), b'', b''),
        (bytes(range(32)), bytes(range(12)), b'hello world', b''),
        (bytes([0x00] * 32), bytes([0x00] * 12), bytes(16), b''),
        (bytes([0xFF] * 32), bytes([0xAB] * 12), bytes(range(16)), b''),
        (bytes(range(32)), b'0123456789ab', bytes(range(1, 18)), b''),
        (bytes(range(32)), b'0123456789ab',
         '央视频电视投屏助手 / cctv1 / 1080p'.encode('utf-8'), b''),
        (bytes(range(32)), b'0123456789ab', b'aad-test-payload', b'additional-auth-data'),
        (bytes(range(32)), b'0123456789ab', bytes(range(32)) * 3, b''),
        # 240 字节: 15 个整块
        (bytes([0x5A] * 32), bytes([0x3C] * 12), bytes([(i * 7) & 0xFF for i in range(240)]), b''),
    ]
    for key, nonce, pt, aad in fixtures:
        ct = ref.aes_gcm_encrypt(key, nonce, pt, aad)
        cases.append({
            'key': h(key), 'nonce': h(nonce), 'plaintext': h(pt), 'aad': h(aad),
            'ciphertextAndTag': h(ct),
        })
    # 再补一条 aes_gcm_decrypt 的往返验证向量（用 Python 加密、Java 解密）
    key, nonce = bytes(range(32)), bytes(range(12))
    pt = '解密方向测试：中文 + ASCII + 符号 !@#$%^&*()'.encode('utf-8')
    ct = ref.aes_gcm_encrypt(key, nonce, pt)
    cases.append({
        'key': h(key), 'nonce': h(nonce), 'plaintext': h(pt), 'aad': '',
        'ciphertextAndTag': h(ct),
    })
    return {'generatedBy': 'tools/gen_crypto_vectors.py via ysp-live-v9.5.py aes_gcm_encrypt',
            'cases': cases}


def gen_tea(ref):
    cases = []
    for key_hex, block_hex in [
        ('000102030405060708090a0b0c0d0e0f', '0001020304050607'),
        ('110DBEC10C23E7D2E56A1CAD6914EF1B', '0011223344556677'),
        ('00000000000000000000000000000000', 'ffffffffffffffff'),
        ('0123456789abcdef0123456789abcdef', 'deadbeefcafebabe'),
    ]:
        key = bytes.fromhex(key_hex)
        blk = bytes.fromhex(block_hex)
        cases.append({
            'key': key_hex,
            'block': block_hex,
            'cipher': h(ref._tea_blk(blk, key)),
        })
    # _cksum 是确定性函数，可以逐字节对比
    ck = []
    for data in [b'', b'a', b'hello', bytes(range(64)), bytes([0xFF] * 32)]:
        ck.append({'data': h(data), 'cksum': ref._cksum(data)})
    return {'encryptBlockCases': cases, 'cksumCases': ck}


def gen_rsa(ref):
    der = base64.b64decode(ref.RSA_PUBLIC_KEY_B64, validate=True)
    n, e = ref.parse_spki_rsa_pubkey(der)
    k = (n.bit_length() + 7) // 8
    chunk_size = k - 2 * 32 - 2
    cases = []
    seed = bytes(range(32))
    for msg in [b'A', b'device-id-1234567890', '央视频-设备号'.encode('utf-8'),
                bytes(range(190))]:
        em = ref._oaep_encode_sha256(msg, k, seed)
        c = pow(int.from_bytes(em, 'big'), e, n).to_bytes(k, 'big')
        cases.append({
            'message': h(msg),
            'seed': h(seed),
            'ciphertext': h(c),
        })
    return {
        'publicKeyB64': ref.RSA_PUBLIC_KEY_B64,
        'modulusBits': n.bit_length(),
        'k': k,
        'chunkSize': chunk_size,
        'deterministicCases': cases,
    }


def gen_form(ref):
    samples = ['a', 'hello world', '中文', 'a=b&c=d', '!@#$%^&*()', '*-._~',
               'a+b', '100%', "quote'\"", 'line\nbreak', '\u00e9\u00e8']
    cases = []
    for s in samples:
        cases.append({'value': s, 'encoded': ref.form_urlencode_value(s)})
    pairs = [('appcommon', '{"a":1,"b":"中文 /x"}'), ('url', 'https://x.cn/a b?c=d&e=f')]
    cases.append({'pairs': [[k, v] for k, v in pairs],
                  'encoded': ref.form_encode(pairs).decode('utf-8')})
    return {'valueCases': cases}


def main():
    ref = load_reference()
    outs = {
        'vectors-aesgcm.json': gen_aesgcm(ref),
        'vectors-tea.json': gen_tea(ref),
        'vectors-rsa.json': gen_rsa(ref),
        'vectors-form.json': gen_form(ref),
    }
    for name, data in outs.items():
        path = os.path.join(HERE, name)
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(data, f, ensure_ascii=False, indent=1, sort_keys=True)
            f.write('\n')
        print('wrote', path, os.path.getsize(path), 'bytes')


if __name__ == '__main__':
    main()
