# 央视频设备协议移植 — 交付说明（device-dev）

## 交付物

### 主代码 `core/src/main/java/com/gary/ysplive/core/`

| 文件 | 说明 |
|---|---|
| `jce/JceWriter.java` | JCE 二进制 TLV 写入器，逐字节复刻 Python `W` |
| `jce/JceReader.java` | JCE 读取器，复刻 Python `R` |
| `jce/JceRequest.java` | `_qua`/`_head`/`_wrap`/`_unwrap` + `jceTimeshiftUrl` + `DeadHostException` |
| `jce/JceFormatException.java` | JCE 负载格式错误 |
| `bk/BkLive.java` | `ckGuard`/`ckey`/`teaPkt`/`cksum` + `bkPlayurls` + `fetchAbsPlaylist` |
| `device/DevicePool.java` | 53 条设备模板（8K 池 28 条），机器生成自参考实现 |
| `device/DeviceProfile.java` | 设备档案 + `inferOsVersion`/`inferSdkInt` |
| `device/DeviceState.java` | 持久化状态（档案 + guid），JSON 落盘 |
| `device/Identity.java` | X-Uid/X-Fingerprint 派生、各套请求头 |
| `device/LiveIds.java` | slug → Live ID（优先读 `ChannelSpec.liveId`） |
| `device/Protocol.java` | 请求体/响应解析、playlist 改写、URL 工具 |
| `device/DeviceResolver.java` | **主体**：实现 `Resolver`，全部协议 flow + 缓存/冷却/防抖/配额/轮换 |

### 测试 `core/src/test/java/com/gary/ysplive/core/`

| 文件 | 用例数 | 说明 |
|---|---|---|
| `jce/JceTest.java` | 11 | 类型回环 + **金标向量逐字节比对**（含 gzip 层） |
| `device/DeviceProtocolTest.java` | 45 | 设备池/身份派生/上报体/playlist 改写，全离线 |
| `device/DeviceResolverTest.java` | 19 | 健壮性：不崩、不泄漏线程、冷却、降级 |
| `device/LiveProbe.java` | 7 | **联网探针**，`YSP_LIVE_PROBE=1` 才跑 |

### 工具 `tools/`

| 文件 | 说明 |
|---|---|
| `gen_jce_vectors.py` | import 参考实现，生成 `vectors-jce.json` |
| `gen_device_pool.py` | 从参考实现逐字提取设备池 → `DevicePool.java` |
| `gen_live_ids.py` | 从 `channels()` 提取 Live ID 表 |
| `verify_device_pool.py` | 校验设备池与参考实现逐字一致 |
| `echo_server.py` | 回显服务器，用于 Java/Python 线上字节比对 |
| `debug_*.py` | 诊断脚本（X-Uid 中间量、参考流程、缓存探测、对照实验） |

## 协议关键点（踩过的坑）

1. **`live/v1/01` 的 `id` 是 Live ID，不是 sid。**
   `Live1717729995180256` ≠ `2024078201`。传错会返回一个没有任何错误说明的
   `{"result":"400","data":{}}`。用 `ChannelSpec.liveId` / `LiveIds.forSlug()`。

2. **`javaUuidFromHashes` 必须符号扩展 int→long**（`(long) h`），
   不能 `h & 0xFFFFFFFFL`。Python 是 `h & 0xFFFFFFFFFFFFFFFF`。
   写错会得到一个"看起来正常"但完全错误的 X-Uid，导致所有请求 400。

3. **`device_fingerprint` 里 manufacturer 后面只有一个斜杠**：
   `LGE/lg_oled88z2pca/lg_oled88z2pca:13/...`，不是 `//`。

4. **JCE `w.long()` 会降级**：`long(1900000000)` 实际写 **type 2（int32）**，
   超过 int32 才升 type 3。所以读出来是 `Integer` 是正常的，不是 bug。

5. **`_wrap` 里 gzip 必须与 Python 逐字节一致**：
   level 9、raw deflate、`mtime=0`、头 `1f 8b 08 00 00 00 00 00 02 ff`。
   Java `Deflater(9, true)` 的输出与 Python zlib 完全相同（已验证）。

6. **`inner` 的长度字段等于 inner 总长**：`len(reqcmd)+17`，而
   `inner = 1+4+1+10+reqcmd+1 = reqcmd+17`，所以 `lenField == inner.length`。

## 构建与测试

```powershell
$env:JAVA_HOME='C:\re-toolkit\jdk'
$env:ANDROID_SDK_ROOT='C:\AndroidSdkCli'; $env:ANDROID_HOME='C:\AndroidSdkCli'
$env:GRADLE_USER_HOME='C:\1\ysp-android\.gradle-home'
& 'C:\1\ysp-android\tools\gradle-8.9\bin\gradle.bat' --project-dir C:\1\ysp-android :core:test --no-daemon
# → 255 tests, 0 failures
```

联网探针（会真连央视频，慎用）：

```powershell
$env:YSP_LIVE_PROBE='1'
& 'C:\1\ysp-android\tools\gradle-8.9\bin\gradle.bat' --project-dir C:\1\ysp-android `
  :core:testDebugUnitTest --tests '*LiveProbe*' --no-daemon
```

## 联网验证记录

**2026-11-04 04:51（成功）** 真实央视频服务器，全部 HTTP 200 且 body 以 `#EXTM3U` 开头：

| 频道 | rate | host | 签名头 | 首次 `finalUrl` |
|---|---|---|---|---|
| cctv1 | `10p` 高码率(10M) | liveali-tpgq.cctv.cn | ✔ | `.../live/30a8228c89a864b186be13047a4b3b60.m3u8` |
| cctv4k | `36p` 4K超高清(36M) | liveali-tpgq.cctv.cn | ✔ | `.../live/6134a64ba55c4ba1e8fec745a699066e.m3u8` |
| cctv8k | `36p` 4K超高清(36M) | liveali-tp4k.cctv.cn | ✔ | `.../live/fJx12C/playlist.m3u8` |
| cctv164k | `36p` 4K超高清(36M) | liveali-tpgq.cctv.cn | ✔ | `.../live/9c4863bac7c60e3ee3fa774c6d14b312.m3u8` |

`cctv4k` 的 content id `6134a64b...` 与当时 8767 参考服务器输出**完全一致**，
证明拿到的是真正的 4K 流，不是降级的 1080p。

**2026-11-04 05:00 之后（上游故障）** `live/v1/01` 开始对所有请求返回
`{"data":{},"message":"","result":"400","time":1790251829105}`。
**Python 参考实现同样失败**，且：
- 400 响应里的 `time` 字段被冻住（只有 2 个交替值，比当前时间早约 3.5 小时）
- 伪造/空的 live_id 返回完全相同的冻结 `time`
- cache-buster、换 screenParam、复用已注册身份都无效
- 8767 参考服务器仍 200，但已降级到 `bkhlsliveali` 通道

结论：**上游 CDN 负缓存/服务端故障，与本实现无关**。恢复后重跑 `LiveProbe` 即可复验。

## 已知限制

- `bk/BkLive.java` 需要 `core.util.Tea`（crypto-dev 提供）。`Tea.pack`
  用随机填充，跨语言密文不可能相同；只保证解密往返正确。
- 设备协议对风控敏感：`DeviceResolver` 已实现 5 分钟/每日 5 次的换设备上限，
  但高频重启仍可能触发上游限流。
