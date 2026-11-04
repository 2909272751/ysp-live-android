# 央视频全频道直播 · Windows 桌面版

**自包含：双击即用。不需要 Python、不需要 Docker、不需要装 Java、不需要浏览器。**

引擎与 Android 版**共用同一份 `core`**（纯 Java 17、零 Android 依赖）；
播放器是内置的精简版 **libVLC**，随包分发，不依赖系统里有什么。

---

## 一、下载哪个

| 文件 | 大小 | 说明 |
|---|---|---|
| **`YspLive-Windows-v9.5.3-selfcontained.zip`** | 92.7 MB | **推荐，自包含**。解压双击 `YspLive.exe` |
| `YspLive-Desktop.jar` | 12.9 MB | 单文件 jar，**需要**本机另有 `vlc\` 目录（见下） |

**系统要求**：Windows 10 / 11（64 位）。**无其它依赖。**

---

## 二、包内有什么（为什么 92.7 MB）

| 部分 | 解压后 |
|---|---|
| `runtime\`（精简 JDK，jlink 裁剪） | 150.5 MB |
| `vlc\`（**精简版 libVLC，含解码器**） | 62.5 MB |
| `app\YspLive-Desktop.jar`（程序 + core 引擎 + hls.js 兜底） | 12.9 MB |
| **合计** | **226.3 MB**（zip 压到 92.7 MB） |

`vlc\` 里是真的解码器，不是空壳：

```
libvlc.dll / libvlccore.dll
plugins\demux          ← HLS / TS 解复用
plugins\codec          ← H.264 / HEVC / AAC 解码（含 D3D11VA 硬解）
plugins\access         ← HTTP / HTTPS 拉流
plugins\video_output / video_chroma / audio_output
```

**实测能播**（不是推测，是跑出来的）：

```
libVlcDir = <程序目录>\vlc
available = true
playlistOk = true   segments = 14
state = PLAYING   tracks = 2        ← 视频+音频轨都在
maxProgressMs = 35946               ← 播放推进到 35.9 秒
RESULT: PASS
```

---

## 三、为什么用 libVLC（技术选型过程）

三条路都**实测过**才定的：

| 方案 | 结果 |
|---|---|
| JavaFX `MediaPlayer` | ❌ **不支持 HLS**，12 秒内不出帧，`status=null` |
| JavaFX `WebView` + hls.js | ❌ **没有 MSE**，`MediaSource=false`，hls.js 报 `levelLoadError` |
| Edge/Chrome + hls.js | ⚠️ 能播，但**要求用户机器装浏览器**，不算自带 |
| **内置精简 libVLC** | ✅ **实测 PASS**，真正自带解码器 |

libVLC 的原生目录从 191 MB 精简到 62.5 MB：保留 demux(ts/hls)、
codec(h264/hevc/aac + d3d11va)、access(http)、video_output、video_chroma、
audio_output、packetizer，砍掉 gui(19MB)、lua、skins、大部分不用的 codec。

---

## 四、界面

```
┌──────────────────────────────────────────────────────────┐
│ 央视频直播  Windows          [状态]   重载 日志 设置 诊断  │
│ [全部] [央视] [卫视] [4K] [收藏]        [继续观看 · CCTV-1]│
├──────────────────────────────────────────────────────────┤
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐               │
│  │ CC │ │ CC │ │ CC │ │ CC │ │ CC │ │ CC │   ← 64 个频道 │
│  │CCTV-1│CCTV-2│...                                     │
│  │1080p│1080p│                                           │
│  └────┘ └────┘                                           │
└──────────────────────────────────────────────────────────┘
```

- **单击频道** → 打开播放窗口（内置 libVLC）
- **右键频道** → 收藏 / 取消收藏
- 顶部 chip 切换筛选，**筛选会记住**
- 「继续观看」直达上次看的频道

### 播放窗口快捷键

| 键 | 作用 |
|---|---|
| 空格 | 播放 / 暂停 |
| F | 全屏切换 |
| 双击画面 | 全屏切换 |
| Esc | 退出全屏；已非全屏则关闭窗口 |

---

## 五、局域网共享

设置里打开「允许局域网访问」，重启引擎后：

- 本机：`http://127.0.0.1:8767/all.m3u`
- 局域网：`http://<本机IP>:8767/all.m3u`

这个地址可以直接给 **TiviMate / PotPlayer / VLC / Kodi** 用——
此时这台电脑就是直播网关，和原版 `ysp-live` 的用法一致。

诊断页有「打开订阅地址」按钮。

---

## 六、构建

```powershell
$env:JAVA_HOME='C:\re-toolkit\jdk'
$env:GRADLE_USER_HOME='C:\1\ysp-android\.gradle-home'

# 1) 单文件 jar（含 core + JavaFX + vlcj + web 资源）
& .\tools\gradle-8.9\bin\gradle.bat --project-dir . :windows:fatJar

# 2) 精简 libVLC（从完整 VLC 目录裁剪，只需跑一次）
powershell -File windows\make-minimal-vlc.ps1

# 3) 打包成自带运行时的 exe 目录
powershell -File windows\pack2.ps1

# 4) 出 zip
powershell -File windows\make-release2.ps1
```

产物：
- `windows\build\libs\YspLive-Desktop.jar` — 12.9 MB
- `windows\dist\YspLive\YspLive.exe` — 451 KB（目录 226 MB）
- `windows\release\YspLive-Windows-v9.5.3-selfcontained.zip` — 92.7 MB

### 为什么不直接用 `implementation project(':core')`

`core` 被 Android Library 插件管理，Gradle 变体属性（`AgpVersionAttr`、
`BuildTypeAttr`）与纯 Java 消费者不兼容，会报
「cannot choose between debugApiElements and releaseApiElements」。

但 `core` 本身零 Android 依赖，所以 `windows/build.gradle` 直接把
`../core/src/main/java` 加进 `sourceSets`——**同一份代码参与两个构建**，不复制不 fork。

---

## 七、自检工具

界面无法在当前会话截图（无交互桌面，系统截图只能拿到黑屏），所以内置了三个自检：

```powershell
# 1) 界面结构（真启动 App，dump live 场景）
java -Dprism.order=sw -cp app\YspLive-Desktop.jar com.gary.ysplive.desktop.SelfCheck out.txt

# 2) libVLC 是否被正确找到
java -cp app\YspLive-Desktop.jar com.gary.ysplive.desktop.VlcSelfCheck

# 3) 端到端播放（起引擎 -> 拉流 -> libVLC 播放 -> 判定）
java -cp app\YspLive-Desktop.jar com.gary.ysplive.desktop.E2EPlayCheck cctv6 e2e.txt
```

实测输出：

```
# SelfCheck
stageTitle=央视频全频道直播 · Windows 版
showing=true  sceneSize=1120x760
Label "引擎已就绪"  Label "端口 8767 · 64 个频道 · 1080p 模式"
chipCount=5  channelCardCount=64  gridChildren=64  gridSize=1104x1590

# VlcSelfCheck
libVlcDir = ...\YspLive\app\..\vlc
available = true    libvlccore.dll = true    plugins = 11 dirs
RESULT: OK

# E2EPlayCheck
playlistOk = true   segments = 14
state = PLAYING   tracks = 2   maxProgressMs = 35946
RESULT: PASS
```

---

## 八、实测记录

| 项 | 结果 |
|---|---|
| exe 启动 | ✅ 进程存活，`/health` 200 |
| `/all.m3u` | ✅ 132 行（64 频道 + 头部） |
| 界面构建 | ✅ 64 张频道卡片全部渲染 |
| `/cctv1.m3u8` | ✅ 200，14 分片 |
| **内置播放器出画** | ✅ **`state=PLAYING tracks=2` 播放推进 35.9 秒** |
| libVLC 体积 | 191 MB → **62.5 MB**（裁剪后仍能播） |

---

## 九、已知限制

1. **必须能直连 `*.cctv.cn`**，不走代理（央视频对代理 IP 会 403）。
2. 4K/8K 需要带宽，实测单分片 12–18 MB（约 36 Mbps 峰值）。
   需要 GPU 支持 HEVC 硬解，否则可能卡顿或回落到 1080p。
3. 目前是**单播放窗口**：点新频道会替换当前播放窗口（不叠开多个）。
4. 时移回看引擎支持，桌面播放页暂未做 UI 入口，可直接访问
   `http://127.0.0.1:8767/cctv1.m3u8?playseek=20260101080000-20260101090000`。
5. 首次启动会比后续慢 1–2 秒（libVLC 初始化）。

---

## 十、与 Android 版的关系

| | Android | Windows |
|---|---|---|
| 引擎 | `core`（同一份） | `core`（同一份） |
| 协议实现 | 相同 | 相同 |
| 界面 | 原生 View + GridView | JavaFX |
| 播放 | Media3 / ExoPlayer | **内置 libVLC** |
| 局域网共享 | 支持 | 支持 |

两边跑同一份协议代码，修 bug 同时受益。

---

## 十一、来源与声明

协议与算法移植自 **Gary's Club** 发布的 [ysp-live v9.5](https://garysclub.sharewithyou.dpdns.org/ysp-live/)。

原作者声明：首发地址为社区 Gary's Club / 频道 Gary's Club Channel，
**其他任何分发渠道均非作者本人**。本项目为第三方移植，与原作者无关、未获授权或背书。
**若原作者要求，将立即删除。**

本仓库不含上游原始 Python/JS 实现。libVLC 来自 [VideoLAN](https://www.videolan.org/)，
遵循 LGPL-2.1+ 许可。所有视频内容版权归央视及各卫视所有，
本项目不存储、不分发任何视频内容。仅供学习研究，请勿商用或公开传播。
