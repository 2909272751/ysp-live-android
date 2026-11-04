## 🖥️ Windows 桌面版 v9.5.3 · 自带播放器

**自包含：双击即用。不需要 Python、不需要 Docker、不需要装 Java、不需要浏览器。**

> 上一版被问「这么小？带播放器了吗」——是的，**上一版确实没带**，
> 它靠系统 Edge/Chrome 渲染。这一版改成了**内置精简 libVLC**，92.7 MB 里是真解码器。

---

### 下载

| 文件 | 大小 | 说明 |
|---|---|---|
| **`YspLive-Windows-v9.5.3-selfcontained.zip`** | **92.7 MB** | **推荐**。解压双击 `YspLive.exe` |
| `YspLive-Desktop.jar` | 12.9 MB | 单文件 jar，需另行提供 `vlc\` 目录 |

**系统要求**：Windows 10/11 64 位。**无其它依赖。**

SHA-256
- `selfcontained.zip`：`250A2CCB2001AFFC8E35790E3F932036C305E03EB7A2E31EC6C399FE052A6510`
- `YspLive-Desktop.jar`：`64431BCDBE21CE9512C3568E490AFB48EAEA2E025436728FC1A33FFD977EB3FB`

---

### 体积构成（为什么是 92.7 MB）

| 部分 | 解压后 |
|---|---|
| `runtime\` 精简 JDK | 150.5 MB |
| **`vlc\` 精简 libVLC（含解码器）** | **62.5 MB** |
| `app\YspLive-Desktop.jar` | 12.9 MB |
| 合计 | 226.3 MB → zip 92.7 MB |

`vlc\` 里是真的解码器：

```
libvlc.dll / libvlccore.dll
plugins\demux       ← HLS / TS 解复用
plugins\codec       ← H.264 / HEVC / AAC（含 D3D11VA 硬解）
plugins\access      ← HTTP / HTTPS 拉流
plugins\video_output / video_chroma / audio_output
```

从完整 VLC（191 MB）裁剪到 62.5 MB：砍掉 gui(19MB)、lua、skins、不用的 codec，
保留播放 HLS 直播的全部必需项。**裁剪后实测仍能正常播放。**

---

### 播放器实测（关键证据）

跑 `E2EPlayCheck`：起引擎 → 拉流 → libVLC 播放 → 判定

```
libVlcDir = <程序目录>\vlc
available = true
playlistOk = true    segments = 14
state = PLAYING      tracks = 2          ← 视频 + 音频轨
maxProgressMs = 35946                    ← 播放推进到 35.9 秒
RESULT: PASS
```

---

### 技术选型：三条路都实测过

| 方案 | 结果 |
|---|---|
| JavaFX `MediaPlayer` | ❌ 不支持 HLS，12 秒不出帧，`status=null` |
| JavaFX `WebView` + hls.js | ❌ 无 MSE（`MediaSource=false`），报 `levelLoadError` |
| Edge/Chrome + hls.js | ⚠️ 能播，但要求用户装浏览器，不算自带 |
| **内置精简 libVLC** | ✅ **实测 PASS** |

---

### 功能

- **64 路频道**：央视 30 路（含 CCTV-4K/8K/16-4K）+ 卫视 34 路
- 筛选 chip：全部/央视/卫视/4K/收藏，**筛选会记住**
- 单击播放，右键收藏，收藏星标显示
- 「继续观看」直达上次频道
- 播放窗口：`空格` 播放暂停 · `F` 全屏 · 双击全屏 · `Esc` 退出
- **局域网共享**：`http://<本机IP>:8767/all.m3u` 可给 TiviMate / PotPlayer / VLC / Kodi
- 诊断页 / 日志页，可复制

---

### 其它验证

| 项 | 结果 |
|---|---|
| exe 启动 | ✅ `/health` 200，进程存活 |
| `/all.m3u` | ✅ 132 行 |
| 界面 | ✅ `channelCardCount=64` `chipCount=5` `gridSize=1104x1590` |
| `/cctv1.m3u8` | ✅ 200，14 分片 |

> 界面截图需要你自己跑才有：开发会话没有可交互桌面，
> 系统级截图只能拿到黑屏（`Robot.createScreenCapture` 返回全黑）。
> 所以我内置了 `SelfCheck` 直接从 live 场景 dump 控件结构来证明界面真的构建了。

---

### 已知限制

1. **必须能直连 `*.cctv.cn`**，不走代理（央视频对代理 IP 会 403）
2. 4K/8K 需 GPU 支持 HEVC 硬解 + 带宽（单分片 12–18 MB，约 36 Mbps 峰值）
3. 单播放窗口：点新频道会替换当前窗口
4. 时移回看引擎支持，桌面播放页暂未做 UI 入口，可直访
   `http://127.0.0.1:8767/cctv1.m3u8?playseek=20260101080000-20260101090000`
5. 首次启动慢 1–2 秒（libVLC 初始化）

---

### 来源与声明

协议与算法移植自 **Gary's Club** 发布的 [ysp-live v9.5](https://garysclub.sharewithyou.dpdns.org/ysp-live/)。

原作者声明：首发地址为社区 Gary's Club / 频道 Gary's Club Channel，
**其他任何分发渠道均非作者本人**。本项目为第三方移植，与原作者无关、未获授权或背书。
**若原作者要求，将立即删除。**

本仓库不含上游原始 Python/JS 实现。libVLC 来自 [VideoLAN](https://www.videolan.org/)，
遵循 LGPL-2.1+ 许可。所有视频内容版权归央视及各卫视所有。仅供学习研究。
