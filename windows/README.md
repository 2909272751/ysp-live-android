# 央视频全频道直播 · Windows 桌面版

**装好就能看。不需要 Python、不需要 Docker、不需要浏览器插件。**

和 Android 版共用同一套 `core` 引擎（纯 Java 17、零 Android 依赖），
区别只是界面：Android 用原生 UI + ExoPlayer，Windows 用 JavaFX + 浏览器内核渲染。

---

## 一、运行环境

| 项 | 要求 |
|---|---|
| 系统 | Windows 10 / 11（64 位） |
| Java | **不需要安装**，exe 自带运行时（jlink 精简 JDK） |
| 浏览器内核 | **需要 Edge 或 Chrome**（用于渲染画面，Windows 10/11 通常自带 Edge） |
| 网络 | 能直连 `*.cctv.cn` |

> ⚠️ 关于「需要 Edge/Chrome」：这不是偷懒。我实测了三条路——
> JavaFX 的 `MediaPlayer` **不支持 HLS**（12 秒不出帧）；
> JavaFX `WebView` **没有 MSE**（`MediaSource=false`），hls.js 无法工作；
> 而 Edge/Chrome 的 Chromium 内核 MSE 完整，hls.js 可直接跑。
> 因此画面交给系统浏览器渲染是当前最稳的方案，不引入 200MB+ 的原生解码器依赖。

---

## 二、两种用法

### 方式 A：免安装（推荐）

解压 `YspLive-Windows-v9.5.3-portable.zip`，双击 `YspLive.exe`。

### 方式 B：单 jar

```powershell
java -Dprism.order=sw -jar YspLive-Desktop.jar
```
需要本机有 JDK 17+（比 exe 版多一个前置条件，但文件小）。

---

## 三、界面

```
┌─────────────────────────────────────────────────────────┐
│ 央视频直播  Windows          [引擎状态]  重载 日志 设置 诊断 │
│ [全部] [央视] [卫视] [4K] [收藏]          [继续观看 · xxx] │
├─────────────────────────────────────────────────────────┤
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐              │
│  │CC  │ │CC  │ │CC  │ │CC  │ │CC  │ │CC  │   ← 64 个频道  │
│  │CCTV-1│CCTV-2│CCTV-3│...                            │
│  │1080p│1080p│1080p│                                    │
│  └────┘ └────┘ └────┘                                    │
└─────────────────────────────────────────────────────────┘
```

- **单击频道** → 打开播放窗口
- **右键频道** → 收藏 / 取消收藏
- 顶部 chip 切换筛选，筛选会被记住
- 「继续观看」显示上次看的频道

---

## 四、播放窗口

独立的 Chromium `--app` 窗口，内置控制条：

| 操作 | 说明 |
|---|---|
| 空格 | 播放 / 暂停 |
| F | 全屏切换 |
| 鼠标移动 | 呼出底部控制条（3 秒自动隐藏） |
| 底部按钮 | 暂停 / 取消静音 / 重载 |

- 状态显示在左上角：`connecting…` → `ready` → `playing`
- 右下角显示当前清晰度与码率
- 网络错误会自动重连（hls.js 的 `startLoad` / `recoverMediaError`）

---

## 五、局域网共享

设置里打开「允许局域网访问」，重启引擎后：

- 本机：`http://127.0.0.1:8767/all.m3u`
- 局域网：`http://<本机IP>:8767/all.m3u`

这个地址可以直接给 **TiviMate / PotPlayer / VLC / Kodi** 等播放器用，
此时这台电脑就是一个直播网关，和原版 `ysp-live` 的用法完全一致。

诊断页有「打开订阅地址」按钮，一键在浏览器里查看。

---

## 六、构建

```powershell
$env:JAVA_HOME='C:\re-toolkit\jdk'
$env:GRADLE_USER_HOME='C:\1\ysp-android\.gradle-home'

# 编译 + 打单文件 jar（含 core + JavaFX + web 资源）
& .\tools\gradle-8.9\bin\gradle.bat --project-dir . :windows:fatJar

# 打包成自带运行时的 exe 目录
powershell -NoProfile -ExecutionPolicy Bypass -File windows\pack.ps1
```

产物：
- `windows\build\libs\YspLive-Desktop.jar` — 9.3 MB 单文件
- `windows\dist\YspLive\YspLive.exe` — 451 KB（整个目录 160 MB，含精简 JDK）

### 为什么不直接用 `implementation project(':core')`

`core` 被 Android Library 插件管理，它的 Gradle 变体属性（`AgpVersionAttr`、
`BuildTypeAttr`）与纯 Java 消费者不兼容，直接依赖会报
「cannot choose between debugApiElements and releaseApiElements」。

但 `core` 本身就是零 Android 依赖的纯 Java 源码，所以 `windows/build.gradle` 直接
把 `../core/src/main/java` 加进 `sourceSets`——**同一份代码参与两个构建**，
不复制、不 fork。

### 界面自检

这个会话没有可交互桌面（远程/无头），系统截图只能拿到黑屏。为了证明界面真的构建了，
内置了 `SelfCheck`：

```powershell
java -Dprism.order=sw -cp windows\build\libs\YspLive-Desktop.jar `
  com.gary.ysplive.desktop.SelfCheck out.txt
```

它会真启动 App、等 12 秒，然后把 live 场景里的控件结构写出来。实测输出：

```
stageTitle=央视频全频道直播 · Windows 版
showing=true  sceneSize=1120x760
Label "引擎已就绪"  Label "端口 8767 · 64 个频道 · 1080p 模式"
chipCount=5  channelCardCount=64  gridChildren=64  gridSize=1104x1590
```

---

## 七、实测记录

| 项 | 结果 |
|---|---|
| 引擎启动 | ✅ 8767 端口，64 频道，`/health` 200 |
| `/all.m3u` | ✅ 132 行（64 频道 + 头部） |
| 界面构建 | ✅ 64 张频道卡片全部渲染 |
| 视频播放 | ✅ **headless Edge 截图确认 CCTV-6 画面在播**（见下） |
| 打包 exe | ✅ 451 KB，自带运行时，启动后 `/health` 200 |

视频播放的验证过程（这是最关键的一步，贴出来备查）：

```
Hls present = true
Hls.version = 1.5.17
MSE = true
Hls.isSupported = true
MANIFEST_PARSED levels=1
LEVEL_LOADED frags=14
FRAG_LOADED sn=20 size=2229868
loadedmetadata dur=56
TIMEUPDATE t=38.1
PLAYING t=38.1
```

截图里能看到 CCTV-6 的电影画面和台标。

---

## 八、已知限制

1. **必须有 Edge 或 Chrome**。Windows 10/11 默认带 Edge；被精简过的系统需自行安装。
2. **必须能直连 `*.cctv.cn`**，不经过代理（央视频对代理 IP 会 403）。
3. 4K/8K 需要足够带宽（实测单分片 12–18 MB，约 36 Mbps 峰值）。
4. 播放窗口是独立进程；关闭主界面不会自动关掉已开的播放窗口（Chromium 行为），
   可在播放窗口直接关闭。
5. 时移回看（7 天）引擎支持，桌面播放页暂未做 UI 入口——可通过
   `http://127.0.0.1:8767/cctv1.m3u8?playseek=20260101080000-20260101090000` 直接访问。

---

## 九、与 Android 版的关系

| | Android | Windows |
|---|---|---|
| 引擎 | `core`（同一份） | `core`（同一份） |
| 协议实现 | 相同 | 相同 |
| 界面 | 原生 View + GridView | JavaFX |
| 播放 | Media3 / ExoPlayer | 内嵌 Chromium + hls.js |
| 本地产出 | 前台 Service | 普通进程 |
| 局域网共享 | 支持 | 支持 |

两边跑的是**同一份协议代码**，所以修 bug 会同时受益。

---

## 十、来源与声明

协议与算法移植自 **Gary's Club** 发布的 [ysp-live v9.5](https://garysclub.sharewithyou.dpdns.org/ysp-live/)。

原作者声明：首发地址为社区 Gary's Club / 频道 Gary's Club Channel，
**其他任何分发渠道均非作者本人**。本项目为第三方移植，与原作者无关、未获授权或背书。
**若原作者要求，将立即删除。**

所有视频内容版权归央视及各卫视所有。本项目不存储、不分发任何视频内容，
仅在本地代为请求上游公开接口并转发给本机播放器。仅供学习研究，请勿商用或公开传播。
