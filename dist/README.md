# 央视频全频道直播 · Android 版 v9.5

**一个 APK，装到手机或电视上就能看。不需要电脑、不需要服务器、不需要订阅地址。**

原版 `ysp-live` 是一个跑在电脑/Docker 上的 Python 网关，播放器要填 `http://localhost:8767/all.m3u`。
这个版本把整套网关**移植进了应用进程内部**——启动 App 时本地引擎自动在 `127.0.0.1:8767` 起服务，
播放器直接连它。所以：**没有外部依赖，打开就是直播。**

---

## 一、安装

| 版本 | 文件 | 大小 | 说明 |
|---|---|---|---|
| 发布版 | `YspLive-v9.5.apk` | 3.5 MB | 正式签名，推荐 |
| 调试版 | `YspLive-v9.5-debug.apk` | 4.7 MB | 带日志，排障用 |

- **手机**（Android 5.0 / API 21 及以上）：直接安装 APK，桌面图标「央视频直播」。
- **电视 / 盒子**（Android TV）：用 U 盘或当贝市场类工具安装，会出现在电视的**应用列表**里
  （已注册 `LEANBACK_LAUNCHER`，遥控器可直接选中）。

> 首次安装需允许「安装未知来源应用」。应用只申请 `INTERNET` / `网络状态` / `唤醒锁` /
> `前台服务` / `开机自启` 五项权限，不读取通讯录、存储、位置。

---

## 二、怎么用

打开 App 即自动启动引擎，几秒后频道列表出现。

1. **选台**：顶部过滤 `全部 / 央视 / 卫视 / 4K / 收藏`，点频道即播。
   - 电视上：遥控器方向键移动焦点，`OK` 播放。
2. **播放页操作**（按 `OK`/点击唤出控制条，4 秒后自动隐藏）
   - `上下键` 或 `CH+ / CH-`：换台
   - `回看`：7 天时移，选日期+时间即可回看
   - `收藏`：加入收藏
   - `诊断`：查看实时状态
3. **长按频道**（手机）/ 菜单键（电视）：加入或取消收藏。
4. **设置**：端口、局域网访问、缓存大小、并发路数、换台防抖、高码率开关、开机自启。

---

## 三、画质与"双引擎四层兜底"

App 内置了与原版一致的多级回退，**任何一层挂了都会自动降级，不会黑屏**：

```
① 设备协议高码率（4K / 8K / 10M 高码率）
      ↓ 失败（400 / 会话失效 / 配额满）
② JCE 时移通道 1080p（jsmpeg 网关，稳定）
      ↓ 失败（域名受限）
③ bkliveinfo 备用通道 1080p
      ↓ 失败
④ 上游引擎代理兜底
```

实测数据（本机，真实央视频服务器）：

| 频道 | 实测码率 | 单分片大小 |
|---|---|---|
| CCTV-4K 超高清 | **36M 超高码率** | ~17.7 MB |
| CCTV-8K 超高清 | **36M 超高码率** | ~12.3 MB |
| CCTV-16 4K | 36M 超高码率 | ~18 MB |
| CCTV-1 / 6 / 13 / 卫视等 | 1080p | ~2.2 MB |

- **换台防抖**：切台后先出 1080p（秒开），停留超过 10 秒才在后台升级到 4K，避免频繁切台触发风控。
- **单设备并发上限**：默认 6 路高码率，空闲 15 秒自动释放配额。
- **分片内存缓存**：默认 200MB LRU，重复拉取命中内存不落盘。

> 流量提示：真 4K 约 15GB/小时，1080p 约 5GB/小时。建议 WiFi 环境。

---

## 四、局域网共享（可选）

设置里打开 **「允许局域网访问」**，重启引擎后：

- 本机：`http://127.0.0.1:8767/all.m3u`
- 局域网其它设备：`http://<手机/电视的IP>:8767/all.m3u`

设置页会直接显示当前设备 IP 和完整订阅地址，可复制。
这样可以把手机当"直播网关"，给同网的其它播放器（TiviMate / APTV / PotPlayer 等）供源，
**不再需要 Python 或 Docker**。

---

## 五、诊断

| 地址 | 内容 |
|---|---|
| `http://127.0.0.1:8767/health` | JSON 健康状态（引擎就绪、设备会话、缓存命中率） |
| `http://127.0.0.1:8767/diag` | 纯文本逐频道诊断（模式、上次刷新、错误） |
| App 内「诊断」页 | 同上 + 最近 400 条运行日志，可一键复制 |

引擎状态条含义：
- **正在启动…**：本地端口已起，正在注册设备身份（此期间先走 1080p）
- **高码率已就绪**：4K/8K 通道可用
- **1080p 模式**：设备协议不可用，已自动降级（仍可正常观看）

---

## 六、技术实现

| 层 | 实现 |
|---|---|
| 语言 | Java 17，无 Kotlin |
| 播放器 | AndroidX Media3 / ExoPlayer 1.4.1（HLS） |
| 最低版本 | API 21（Android 5.0），targetSdk 34 |
| 依赖 | 仅 media3 + androidx.annotation，**无 AppCompat / Material / Retrofit / OkHttp** |
| 引擎 | 纯 Java 移植，跑在独立线程池，前台 Service 保活 |
| 通信 | App ↔ 引擎走本机 HTTP（`127.0.0.1`），与外部播放器走同一套接口 |

移植要点（与原版逐字节对齐，均有对照测试）：

- **AES-256-GCM**（会话密钥）、**RSA-OAEP-SHA256**（设备注册）、**TEA**（bkliveinfo 签名）
  ——全部与 Python 参考实现做了**逐字节向量对照**（密钥/明文/密文三元组完全一致）。
- **JSON 序列化**必须复现 Python `json.dumps(separators=(',',':'), sort_keys=True)` 的字节输出，
  因为请求体参与服务端签名。已用 27 个固定用例 + 3000 个随机浮点验证格式一致。
- **JCE 二进制协议**（腾讯 TLV 格式）逐字段移植，含 gzip 压缩层与 89 字节包头。
- 设备身份池 53 种 8K/4K 电视模板，形如 `Sony XR-75Z9K`，随机会话轮换以应对 400。

---

## 七、已知限制

1. **必须能直连央视频域名**。若网络环境需要代理才能访问境内视频站，本 App 不会走系统代理
   （刻意为之：央视频对代理 IP 会 403）。人在海外时请确保路由/分流规则把
   `*.cctv.cn` / `*.ysp.cctv.cn` 设为直连。
2. 4K/8K 需要**设备和带宽同时支持**：电视端要有 HEVC 硬解，带宽建议 ≥ 50Mbps。
   手机端若解码性能不足会自动回落到 1080p。
3. 时移回看取决于源站保留策略（一般 7 天），超期会回退到直播。
4. 引擎在 App 前台/前台服务时运行；系统极端省电策略下可能被清理，可在设置里开启「开机自启」。

---

## 八、构建（开发者）

```powershell
$env:JAVA_HOME='C:\re-toolkit\jdk'
$env:ANDROID_SDK_ROOT='C:\AndroidSdkCli'
$env:GRADLE_USER_HOME='C:\1\ysp-android\.gradle-home'
$G='C:\1\ysp-android\tools\gradle-8.9\bin\gradle.bat'

& $G --project-dir C:\1\ysp-android :core:test        # 单元测试
& $G --project-dir C:\1\ysp-android :app:assembleRelease

# 桌面端联调（不需要安卓设备，直接跑完整链路）
& "$env:JAVA_HOME\bin\java.exe" -Dfile.encoding=UTF-8 `
  -cp core\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes `
  com.gary.ysplive.core.engine.DesktopHarness --port 18767
```

`DesktopHarness` 会真连央视频，打印每个频道的 m3u8 与分片字节数，最后输出 `RESULT: PASS/FAIL`。

发布签名：`app/ysp-release.jks`（alias `ysplive`）。改口令用环境变量
`YSP_KEYSTORE_PASS` / `YSP_KEY_PASS`，或换证书用 `YSP_KEYSTORE`。

---

## 九、来源说明

协议与算法移植自 **Gary's Club** 发布的 [ysp-live v9.5](https://garysclub.sharewithyou.dpdns.org/ysp-live/)。
原始声明：首发地址为社区 Gary's Club / 频道 Gary's Club Channel，其他任何分发渠道均非作者本人。

本 Android 移植版仅为**技术学习与个人使用**，不对内容来源、可用性、合法性作任何担保。
所有视频内容版权归央视及各卫视所有。
