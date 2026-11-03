# 央视频全频道直播 · Android 版 (YSP Live for Android)

**一个 APK，装到手机或电视上就能看。不需要电脑、不需要服务器、不需要填订阅地址。**

原版 [`ysp-live`](https://garysclub.sharewithyou.dpdns.org/ysp-live/) 是一个跑在电脑/Docker 上的 Python 网关，
播放器需要手动填 `http://localhost:8767/all.m3u`。本项目把整套网关**用纯 Java 重写并打进 App 进程内部**——
启动时本地引擎自动在 `127.0.0.1:8767` 起服务，播放器直接连它。所以对使用者来说：**打开就是直播**。

> ⚠️ **这是第三方移植项目，非原作者发布。** 详见下方[来源与声明](#来源与声明)。

---

## 下载

| 文件 | 大小 | 说明 |
|---|---|---|
| [`dist/YspLive-v9.5.1.apk`](dist/YspLive-v9.5.1.apk) | 3.58 MB | **发布版，推荐** |
| [`dist/YspLive-v9.5.1-debug.apk`](dist/YspLive-v9.5.1-debug.apk) | 4.56 MB | 调试版，带完整日志，排障用 |

- 包名 `com.gary.ysplive`，versionCode `906`，versionName `9.5.1`
- `minSdk 21`（Android 5.0+），`targetSdk 34`
- **同一个 APK 同时支持手机和 Android TV**（同时注册 `LAUNCHER` 与 `LEANBACK_LAUNCHER`）

### v9.5.1 修复了什么

**v9.5.0 在手机上点不动**（点频道不进播放器、顶部筛选 tag 切换无反应），电视上却正常。
两个都是触屏/焦点语义的 bug：

1. `item_channel.xml` 的根布局带了 `android:clickable="true"`。
   子视图可点击时，`GridView.onItemClick` **永远不会触发**（父列表认为触摸已被子视图消费），
   而条目自身又没有绑点击监听 → 点频道完全无反应。
2. `Ui.makeChip()` 与若干 item 布局用了 `focusableInTouchMode="true"`。
   `View.onTouchEvent` 在这种情况下首次点击只 `requestFocus()`、**不** `performClick()`，
   所以筛选 tag 必须点两下才生效。

电视上因为焦点始终在控件上，这两个问题都被掩盖了——这就是「只有电视能用」的原因。
v9.5.1 已全部移除并加了注释防止回退，同时给 `openPlayer` 加了点击去抖防止重复打开。


---

## 安装

- **手机**：直接安装 APK，桌面图标「央视频直播」。需允许「安装未知来源应用」。
- **电视 / 盒子**：用 U 盘或当贝市场类工具安装，会出现在电视的应用列表里，遥控器可直接选中。

只申请五项权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK`、`FOREGROUND_SERVICE`、
`RECEIVE_BOOT_COMPLETED`。不读取通讯录、存储、位置。

---

## 使用

打开 App 即自动启动引擎，几秒后频道列表出现。

1. **选台**：顶部过滤 `全部 / 央视 / 卫视 / 4K / 收藏`，点频道即播。
   电视上用遥控器方向键移动焦点，`OK` 播放。
2. **播放页**（按 `OK`/点击唤出控制条，4 秒自动隐藏）
   - `上下键` 或 `CH+ / CH-`：换台
   - `回看`：7 天时移
   - `收藏` / `诊断`
3. **长按频道**（手机）或菜单键（电视）：加入/取消收藏。
4. **设置**：端口、局域网访问、缓存大小、并发路数、换台防抖、高码率开关、开机自启。

### 局域网共享（可选）

设置里打开「允许局域网访问」并重启引擎：

- 本机：`http://127.0.0.1:8767/all.m3u`
- 其他设备：`http://<本机IP>:8767/all.m3u`

设置页直接显示当前 IP 和完整订阅地址。此时手机/电视本身就是网关，可以给 TiviMate / APTV / PotPlayer 等供源，
**不再需要 Python 或 Docker**。

### 诊断

| 地址 | 内容 |
|---|---|
| `http://127.0.0.1:8767/health` | JSON 健康状态（引擎就绪、设备会话、缓存命中率） |
| `http://127.0.0.1:8767/diag` | 纯文本逐频道诊断 |
| App 内「诊断」页 | 同上 + 最近 400 条日志，可一键复制 |

---

## 技术实现

| 层 | 实现 |
|---|---|
| 语言 | Java 17，无 Kotlin |
| 播放器 | AndroidX Media3 / ExoPlayer 1.4.1（HLS） |
| 依赖 | 仅 `media3-*` + `androidx.annotation`；**无 AppCompat / Material / Retrofit / OkHttp** |
| 引擎 | 纯 Java 移植，跑在独立线程池，前台 Service 保活 |
| 通信 | App ↔ 引擎走本机 HTTP（`127.0.0.1`），与外部播放器同一套接口 |

### 为什么 `core` 零 Android 依赖

`core` 模块**不 import 任何 `android.*`**。这样同一份代码能在桌面 JVM 上直接跑真实联网联调——
本项目正是在没有可用安卓模拟器的机器上完成端到端验证的（见下）。

### 双引擎四层兜底

```
① 设备协议高码率（4K / 8K / 36M 超高码率）
      ↓ 失败（400 / 会话失效 / 配额满）
② JCE 时移通道 1080p
      ↓ 失败（域名受限）
③ bkliveinfo 备用通道 1080p（按主机名优选健康 CDN + 多候选轮换 + 清晰度阶梯降级）
      ↓ 失败
④ 上游引擎代理兜底
```

- **换台防抖**：切台先出 1080p 秒开，停留超 10 秒才后台升 4K，避免频繁切台触发风控
- **单设备并发上限**：默认 6 路，空闲 15 秒释放配额
- **分片内存缓存**：默认 200MB LRU
- **清晰度阶梯**：`fhd → uhd → shd → hd → sd` 逐级降级，且**恢复后会自动复位**到原始清晰度

---

## 验证结果

### 单元测试 255 / 255 通过

```
CatchupTest            28    PlaylistTest           17    TsSegmentCacheTest     11
DeviceProtocolTest     45    DeviceResolverTest     19    JceTest                11
HttpTest               25    AllM3uGoldenTest        4    LocalServerTest        21
Aes256Test              8    AesGcmTest              9    JsonTest               27
RsaOaepTest             7    TeaTest                 9    UtilTest               14
                                          TOTAL 255, 0 failed, 0 errors
```

关键项不是「跑通就算」，而是**对着 Python 参考实现逐字节比对**：

- **AES-GCM**：固定 nonce 下密文逐字节相等（空明文 / 跨块 / 非整块 / 中文 UTF-8 / 带 AAD）
- **RSA-OAEP**：固定 seed 下逐字节相等 → 证明 MGF1 用的是 SHA-256 而非 SHA-1（经典平台坑）
- **JSON**：复现 Python `json.dumps(sort_keys=True, separators=(',',':'))` 字节输出，
  另加 **3000 个随机浮点**验证 `1e+16` / `1e-05` / `-0.0` 等边界格式
- **JCE 二进制协议**：`_wrap` 完整包（含 gzip 层）逐字节相等
- **`/all.m3u`**：与参考服务器输出**逐字节相同**（仅端口不同）

### 真实联网端到端

`DesktopHarness` 起完整网关后真连央视频，逐频道拉 m3u8 并下载分片：

| 频道 | 实测 |
|---|---|
| CCTV-4K 超高清 | **36M 超高码率，单分片 17.7 MB，0x47 同步字节** |
| CCTV-8K 超高清 | **36M 超高码率，单分片 12.3 MB** |
| CCTV-16 4K | 36M 超高码率，单分片 18.2 MB |
| CCTV-1/2/6/13/17、北京/东方/湖南/浙江/广东卫视、CETV-1、国学 | 1080p，单分片 1.5–3.7 MB |

cctv4k 的 content id 与参考服务器一致，可确认是同一路 4K 源，不是降级冒充。

### 并发压测

8 客户端 × 20 分片，3 频道每 10s 轮换，45 秒：

```
请求 6628 / 成功 6628 / 失败 0      146.9 req/s
传输 13,557 MB                     状态码 {200=6628}   ← 零 4xx/5xx/502
ts_cache 命中 6219 / 未中 88        命中率 98.6%
堆 1.4→1.1 MB   RSS 114→129 MB     （传 13.5 GB 只涨 15 MB）
```

零连接泄漏、零超时、客户端互不影响。

---

## 构建

需要 JDK 17+ 与 Android SDK（`platforms;android-34`、`build-tools;34.0.0`）。

```powershell
$env:JAVA_HOME='<你的 JDK>'
$env:ANDROID_SDK_ROOT='<你的 SDK>'
$env:GRADLE_USER_HOME='<缓存目录>'

# 若没有 app/ysp-release.jks，build.gradle 会自动回退到 tools/debug.keystore
& .\tools\gradle-8.9\bin\gradle.bat --project-dir . :core:test
& .\tools\gradle-8.9\bin\gradle.bat --project-dir . :app:assembleRelease
```

产物：`app/build/outputs/apk/release/YspLive-v9.5.1.apk`

仓库不含 Gradle 发行版（144 MB），从 <https://gradle.org/releases/> 下载 8.9 解压到 `tools/gradle-8.9/` 即可。
`tools/gradlew.ps1` 可用一行封装上述环境变量。

### 桌面端联调（不需要安卓设备）

```powershell
$env:JAVA_HOME='<你的 JDK>'
& "$env:JAVA_HOME\bin\java.exe" -Dfile.encoding=UTF-8 `
  -cp core\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes `
  com.gary.ysplive.core.engine.DesktopHarness --port 18767
```

会真连央视频，打印每个频道的 m3u8 与分片字节数，最后输出 `RESULT: PASS/FAIL`。

---

## 项目结构

```
CONTRACT.md                 并行开发的接口契约（含构建命令）
core/                       纯 Java 引擎，零 Android 依赖
  data/ChannelTable         64 路频道表（机器生成）
  spi/                      对外接口（Gateway / Resolver / Log）
  util/                     Hex/Base64X/Crypto/AesGcm/RsaOaep/Tea/Json（全手写，无第三方库）
  net/Http                  基于 HttpURLConnection 的 HTTP 客户端
  jce/                      JCE 二进制协议（Write/Read/_wrap/_unwrap）
  bk/BkLive                 bkliveinfo 备用通道（TEA 签名链）
  device/                   设备身份 + 全部设备协议 flow
  channel/                  频道刷新引擎、分片缓存、清晰度阶梯
  server/LocalServer        本地 HTTP 网关（裸 ServerSocket）
  engine/YspGateway         门面：装配以上全部
app/                        Android UI 层（14 类 + 38 资源）
  ui/                       频道列表 / 播放器 / 设置 / 日志
  play/                     前台服务、引擎总线、反射装配点
tools/                      生成器、测试向量、构建脚本、诊断探针
dist/                       交付的 APK
```

---

## 已知限制

1. **必须能直连 `*.cctv.cn`**。刻意不走系统代理——央视频对代理 IP 会 403。
   海外使用需在分流规则里把 `*.cctv.cn` / `*.ysp.cctv.cn` 设为直连。
2. **未在真机/模拟器上做最终启动验证。** 开发机 `HypervisorPresent=False`，
   x86_64 模拟器无硬件加速时无法启动（实测 CPU 占用 0.1s、始终 offline），当时也无可用真机。
   已用以下替代手段验证：编译 exit 0、`aapt2 dump badging` 确认双 launcher、
   `apksigner verify` 通过、dex 反查确认引擎/UI/播放器全部打进 APK、
   静态校验 60 个 `R.id` 全部能在布局中找到、反射目标类名逐字符核对、
   以及同一套 `core` 代码在桌面 JVM 上跑通完整链路含真实 4K/8K。
   **首次安装后建议看一眼「诊断」页确认引擎就绪状态。**
3. 4K/8K 需设备 HEVC 硬解 + 带宽 ≥ 50 Mbps；解码能力不足时自动回落 1080p。
4. 时移回看取决于源站保留策略（一般 7 天）。
5. 流量：真 4K 约 15 GB/小时，1080p 约 5 GB/小时。建议 WiFi。

---

## 开发过程中修掉的真实缺陷

移植不是照抄。以下是实测才暴露、且都会直接影响用户的：

1. **`BkLive.teaPkt` 链式加密错误** —— `pc` 链的是 TEA 输出 `enc`，而参考实现链的是最终密文 `enc^pp`。
   第二块起全部错位，bkliveinfo 对**所有频道**回 `iretcode=32 权限验证失败` → 1080p 备用通道 100% 死掉。
2. **`Http.close()` 是空实现** —— `HttpURLConnection` 的阻塞读**不会**被 `Thread.interrupt()` 打断，
   导致停止引擎要干等读超时。改为记录在飞连接并在 `close()` 时 `disconnect()`。
3. **`device-telemetry` 线程泄漏** —— 局部变量、从未被 `stop()` 跟踪，退出后仍存活。
4. **live_id 与 sid 混用** —— `live/v1/01` 的 `id` 必须是 `Live1767871224782105` 这类直播间编号，
   不是 `2024078201` 这类 sid。传错只回一个无错误信息的 400。
5. **`javaUuidFromHashes` 符号扩展** —— 必须 `(long) h` 符号扩展；
   写成 `h & 0xFFFFFFFFL` 会静默得到错误的 X-Uid，导致所有请求 400。
6. **release 签名条件写反** —— 导致 `assembleRelease` 产出未签名 APK，`apksigner` 报 `Missing META-INF/MANIFEST.MF`。
7. **清晰度阶梯一次走完五级** —— 单一时刻的采样就耗尽阶梯并卡在最低档。
   改为每次刷新只推进一级，并在连续成功 2 次后复位。

---

## 来源与声明

### 上游项目

协议、算法与频道数据移植自 **Gary's Club** 发布的 **ysp-live v9.5**：

- 发布页：<https://garysclub.sharewithyou.dpdns.org/ysp-live/>
- 社区：<https://t.me/garysclub> ｜ 频道：<https://t.me/garysclubchannel>

原作者声明：**首发地址为社区 Gary's Club / 频道 Gary's Club Channel，其他任何分发渠道均非作者本人。**
本项目是**第三方移植**，与原作者无关，也未获原作者授权或背书。
**若原作者要求，本项目将立即删除。**

### 未包含的内容

仓库**不包含**上游的原始 Python/JS 实现（`ysp-live-v9.5.py` 等）。
需要参考实现请自行从上述原作者渠道获取。本仓库只包含本项目的 Java 移植成果。

### 法律声明

- 本项目仅供**技术学习与个人研究**，不对内容来源、可用性、连续性、合法性作任何担保。
- 所有视频内容**版权归央视及各卫视所有**。本项目不存储、不分发任何视频内容，
  仅在本地代为请求上游公开接口并转发给本机播放器。
- 请勿用于商业用途或公开传播。使用者需自行确认所在地区的法律法规。
- 使用本项目产生的任何后果由使用者自行承担。

### 第三方组件

- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) — Apache-2.0
- 其余全部代码为手写，无其他第三方依赖

---

## 许可

本仓库中**本项目原创的代码**（`core/`、`app/`、`tools/` 中的 Java/Kotlin/Gradle/脚本）以 MIT 许可发布。
从上游移植的协议常量与频道数据不在此许可范围内，其权利归原作者。
