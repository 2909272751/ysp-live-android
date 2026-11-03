# 交付说明 · 央视频全频道直播 Android 版

## 一、产物

| 文件 | 大小 | SHA-256 | 说明 |
|---|---|---|---|
| `dist/YspLive-v9.5.apk` | 3.58 MB | `9CBF45C6…48A00` | **发布版**，正式签名 |
| `dist/YspLive-v9.5-debug.apk` | 4.66 MB | `B8A50429…CDF5` | 调试版，带日志 |

- 包名 `com.gary.ysplive`，versionCode 905 / versionName 9.5.0
- `minSdk 21`（Android 5.0+），`targetSdk 34`
- 应用名「央视频直播」
- 同时注册 `LAUNCHER` 与 `LEANBACK_LAUNCHER` → **手机和 Android TV 同一个 APK**
- `apksigner verify --verbose` → Verifies，v1 + v2 均通过，签名证书 `CN=YSP Live, OU=Gary's Club`

## 二、核心结论

**把 Python 网关整体移植进了 App 进程内，Android 上不需要电脑、Docker、Python 或订阅地址。**

原版 `ysp-live` 是电脑上跑的 Python 服务，播放器要填 `http://localhost:8767/all.m3u`。
本版本把设备协议、频道引擎、本地 HTTP 网关全部用**纯 Java 重写**并打进 APK：

```
App 启动 → 前台服务拉起引擎 → 本地 127.0.0.1:8767 起 HTTP 网关 → ExoPlayer 直接播
```

所以对使用者而言：**装上、打开、就能看**。局域网共享也只需开一个开关（此时手机/电视本身就是网关）。

## 三、代码规模

| 模块 | 文件数 | 行数 | 说明 |
|---|---|---|---|
| `core` 主代码 | 43 | 13,146 | 纯 Java 17，**零 Android 依赖** |
| `core` 测试 | 20 | 5,776 | 255 个用例 |
| `app` | 14 | 3,764 | UI + 播放器 + 前台服务 |
| `app` 资源 | 38 | — | 9 个布局 + 29 个 drawable/values |

`core` 不依赖 Android 是有意设计：它能在**桌面 JVM 上直接跑真实联网联调**，
这也让本项目在没有安卓模拟器的机器上依然做到了端到端验证。

## 四、验证结果

### 4.1 单元测试：255 / 255 通过

```
CatchupTest            28    PlaylistTest           17    TsSegmentCacheTest     11
DeviceProtocolTest     45    DeviceResolverTest     19    JceTest                11
HttpTest               25    AllM3uGoldenTest        4    LocalServerTest        21
Aes256Test              8    AesGcmTest              9    JsonTest               27
RsaOaepTest             7    TeaTest                 9    UtilTest               14
                                          TOTAL 255, 0 failed, 0 errors
```

不是"跑通就算"，关键几项是**对着 Python 参考实现逐字节比对**：

- **AES-GCM**：固定 nonce 下密文逐字节相等（空明文 / 跨块 / 非整块 / 中文 UTF-8 / 带 AAD）
- **RSA-OAEP**：固定 seed 下逐字节相等 → 证明 MGF1 用的是 SHA-256 而非 SHA-1（经典平台坑）
- **JSON**：复现 Python `json.dumps(sort_keys=True, separators=(',',':'))` 的字节输出，
  另加 **3000 个随机浮点**验证 `1e+16` / `1e-05` / `-0.0` 等边界格式一致
- **JCE 二进制协议**：`_wrap` 完整包（含 gzip 层）逐字节相等
- **`/all.m3u`**：与参考服务器输出**逐字节相同**（仅端口不同）

### 4.2 真实联网端到端（桌面 JVM 跑同一份 core）

`DesktopHarness` 起完整网关后真连央视频，逐频道拉 m3u8 并下载分片：

| 频道 | 实测 |
|---|---|
| CCTV-4K 超高清 | **36M 超高码率，单分片 17.7 MB，0x47 同步字节** |
| CCTV-8K 超高清 | **36M 超高码率，单分片 12.3 MB** |
| CCTV-16 4K | 36M 超高码率，单分片 18.2 MB |
| CCTV-1/2/6/13/17、北京/东方/湖南/浙江/广东卫视、CETV-1、国学 | 1080p，单分片 1.5–3.7 MB |

**结论：真实 4K 与 8K 直播流已打通，不是降级后的 1080p。**
（cctv4k 的 content id `6134a64b…` 与参考服务器一致，可确认是同一路 4K 源。）

### 4.3 并发压测（engine-dev）

8 客户端 × 20 分片，3 频道每 10s 轮换，45 秒：

```
请求 6628 / 成功 6628 / 失败 0    146.9 req/s
传输 13,557 MB                    状态码 {200=6628}  ← 零 4xx/5xx/502
ts_cache: 57 条, 命中 6219 / 未中 88, 命中率 98.6%
堆 1.4→1.1 MB   RSS 114→129 MB    （传了 13.5 GB 只涨 15 MB）
压测后 /health 响应 0ms, /cctv1.m3u8 0ms
```

零连接泄漏、零超时、客户端之间互不影响。这是"换台+追播"的真实负载。

## 五、过程中修掉的真实缺陷（都会直接影响用户）

移植不是照抄，以下几个是实测才暴露出来的：

1. **`BkLive.teaPkt` 链式加密错误** —— `pc` 链的是 TEA 输出 `enc`，而 Python 链的是最终密文 `enc^pp`。
   结果第二块起全部错位，bkliveinfo 对**所有频道**回 `iretcode=32 权限验证失败`
   → 1080p 备用通道 100% 死掉。修复后 cctv4k/8k/6 全部返回有效地址。
2. **`Http.close()` 是空实现** —— `HttpURLConnection` 的阻塞读**不会**被 `Thread.interrupt()` 打断。
   导致"停止引擎"要干等读超时。改成记录在飞连接并在 `close()` 时 `disconnect()`。
3. **`device-telemetry` 线程泄漏** —— 局部变量、从未被 `stop()` 跟踪，退出后仍存活。
4. **live_id 与 sid 混用** —— `live/v1/01` 的 `id` 必须是 `Live1767871224782105` 这类
   直播间编号，不是 `2024078201` 这类 sid。传错只回一个无错误信息的 400。
   已把这 26 个编号提升为 `ChannelSpec.liveId`，全项目单一事实源。
5. **`javaUuidFromHashes` 符号扩展** —— 必须 `(long) h` 符号扩展，写成 `h & 0xFFFFFFFFL`
   会静默得到错误的 X-Uid，导致所有请求 400。
6. **413 三处接口定义错配**、**release 签名条件写反**（导致 `assembleRelease` 产出未签名
   APK，`apksigner` 报 `Missing META-INF/MANIFEST.MF`），均已修正。

## 六、交付期间的线上事故（重要，非本项目缺陷）

验证期间央视频**设备协议接口整体故障**，实测：

```
POST https://ytpaddr.cctv.cn/gsnw/api/live/v1/01
  → 400 {"data":{},"message":"","result":"400","time":1790251829105}
```

`time` 字段**冻结**在两个约 3.5 小时前的固定值上（正常应是当前时间戳），
且随便传垃圾 id / 空 id 都返回同一份冻结响应 —— 典型的 CDN 边缘负缓存。
**同机上的 Python 参考实现同样失败**，可以确认与我们无关。

在此故障期间，我们的四层兜底表现：

- `app/start` / `live/v1/01` 挂 → 自动回退 **bkliveinfo** 通道
- 部分 CDN 边缘整片 403 → 自动**按主机名前缀优选健康候选**并轮换
- 某清晰度全挂 → 沿 `fhd → uhd → shd → hd → sd` **逐级降级**重试

实测 15 个抽样频道里 **稳定 14/15 正常出片**（1.5–3.7 MB 真 TS 分片）。
失败的那 1 个 **每次运行都不一样**（依次观察到 cctv17、cctv4k、cctv164k 轮流中招，
且同一频道在另一轮里能正常播放），这是上游 CDN 边缘 **按请求随机** 的健康状态所致；
参考实现在同一时刻对这几个频道同样是失败或仅 1/3 分片可播。

已确认不存在「卡在低清晰度」的状态缺陷：`bk <清晰度> 已连续成功，清晰度复位到 fhd`
复位逻辑实测生效（每轮日志中可见），频道恢复后会自动回到原始清晰度。

> 播放器侧表现：这几个 4K/高清频道在事故期间可能首次打开失败或黑屏几秒，
> ExoPlayer 的重试退避会自动重试，引擎在重试间隙逐级推进清晰度阶梯，通常 1–2 次内出片。

> 待上游恢复后，4K/8K 高码率通道会自动重新激活（`heartbeat` 与设备会话会自动重建），
> 不需要用户做任何操作。

## 七、已知限制

1. **必须能直连 `*.cctv.cn`**。刻意不走系统代理——央视频对代理 IP 会 403。
   海外使用需在分流规则里把 `*.cctv.cn` / `*.ysp.cctv.cn` 设为直连。
2. **未在真机/模拟器上做最终启动验证**。本机 `HypervisorPresent=False`，
   Android 模拟器（x86_64）无硬件加速时无法完成启动（实测 CPU 占用 0.1s、始终 offline），
   且当时无可用真机。已改用以下替代验证：
   - `:app:assembleRelease` / `assembleDebug` 均 exit 0
   - `aapt2 dump badging` 确认 launcher + leanback launcher 均注册
   - `apksigner verify --verbose` 通过
   - dex 反查确认 `YspGateway` / `GatewayProvider` / `MainActivity` / `PlayerActivity` /
     `EngineService` / `LocalServer` / `DeviceResolver` / `HlsMediaSource` **全部打进 APK**
   - 静态校验 60 个 `R.id.*` 引用**全部**能在 9 个布局里找到（排除运行时 NPE）
   - 反射目标类名与构造函数签名逐字符核对一致
   - **同一套 `core` 代码在桌面 JVM 上跑通了完整链路**（含真实 4K/8K）

   建议首次安装后看一眼 App 内「诊断」页确认引擎就绪状态。
3. 4K/8K 需要设备 HEVC 硬解 + 带宽 ≥ 50 Mbps；手机解码能力不足时会自动回落 1080p。
4. 时移回看取决于源站保留策略（一般 7 天）。

## 八、目录

```
C:\1\ysp-android\
  dist\                        ← 交付物（两个 APK + README）
  README.md                    ← 面向使用者的说明
  RELEASE-NOTES.md             ← 本文件
  CONTRACT.md                  ← 并行开发的接口契约（含构建命令）
  core\                        ← 纯 Java 引擎（43 主 + 20 测试）
  app\                         ← Android UI 层
  ysp-live-v9.5.py             ← 参考实现（只读，未修改）
  tools\                       ← 生成器、向量、构建脚本、各类探针
```

构建命令见 `CONTRACT.md` 第 9 节。签名密钥 `app/ysp-release.jks`（alias `ysplive`），
口令可用环境变量 `YSP_KEYSTORE_PASS` / `YSP_KEY_PASS` 覆盖。

## 九、来源与声明

协议与算法移植自 **Gary's Club** 发布的 [ysp-live v9.5](https://garysclub.sharewithyou.dpdns.org/ysp-live/)。
原始声明：首发地址为社区 Gary's Club / 频道 Gary's Club Channel，其他任何分发渠道均非作者本人。

本项目为 Android 移植，仅供技术学习与个人使用，不对内容来源、可用性、合法性作任何担保。
所有视频内容版权归央视及各卫视所有。
