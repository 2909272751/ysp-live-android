## 🖥️ Windows 桌面版 v9.5.3 · 带安装程序

**自包含：安装包 62.4 MB，双击安装，从「设置 → 应用」正常卸载。**
不需要 Python、不需要 Docker、不需要装 Java、不需要浏览器。

---

### 下载哪个

| 文件 | 大小 | 适用 |
|---|---|---|
| **`YspLive-Setup-9.5.3.exe`** | **62.4 MB** | **推荐**。标准 Windows 安装程序，有开始菜单/桌面快捷方式/卸载项 |
| `YspLive-Windows-v9.5.3-selfcontained.zip` | 92.7 MB | 免安装绿色版，解压即用 |
| `YspLive-Desktop.jar` | 12.9 MB | 单文件 jar，需自备 `vlc\` 目录 |

**系统要求**：Windows 10 / 11 64 位。**无其它依赖。**

SHA-256
- `Setup.exe`：`3DC169AC6DA280925BCD9F724EC337727FCB0FB86B1A93B04F41B43D3975FF1C`
- `selfcontained.zip`：`5C435828128E2D3214F2CE779E75EC0D2A57DF3BEE1E31B02F3EA0EDB82E195D`
- `jar`：`64431BCDBE21CE9512C3568E490AFB48EAEA2E025436728FC1A33FFD977EB3FB`

---

### 安装程序做了什么

**安装时**
- 安装向导（简体中文 / English 可选，带图标与许可页）
- **默认「仅为我安装」，不需要管理员权限**；也可选「为所有用户安装」（需 UAC）
- 开始菜单：`央视频直播` / `使用说明` / `卸载央视频直播`
- 可选桌面快捷方式
- 安装完可直接启动

**卸载时**
- 从「设置 → 应用」或开始菜单卸载
- 先 `taskkill` 掉正在运行的进程，避免文件占用导致残留
- 删除程序目录、注册表项、开始菜单、桌面快捷方式
- **单独询问是否删除用户配置**（`%APPDATA%\YspLive`），
  默认保留，方便重装后沿用设置；也可选择一并清掉

**无人值守**
```bat
:: 静默安装（当前用户，无需管理员）
YspLive-Setup-9.5.3.exe /VERYSILENT /SUPPRESSMSGBOXES /CURRENTUSER

:: 静默卸载，保留配置
"%LOCALAPPDATA%\Programs\YspLive\unins000.exe" /VERYSILENT

:: 静默卸载，连配置一起删
"%LOCALAPPDATA%\Programs\YspLive\unins000.exe" /VERYSILENT /REMOVEDATA
```

---

### 安装/卸载实测

完整跑了一遍生命周期测试，**22 项检查全过**：

```
########## 1. INSTALL ##########
installer exit=0 in 10.3s
  PASS  exe / runtime / jar / libVLC / README / uninstaller 全部就位
  installed size = 230.6 MB

########## 2. RUN ##########
  PASS  process alive
  PASS  health 200          all.m3u lines = 132
  PASS  userdata created

########## 3. UNINSTALL (keep data) ##########
uninstaller exited=True in 1.5s
  PASS  不卡死         PASS  程序目录删除
  PASS  用户数据保留    PASS  marker 存活
  PASS  注册表清掉      PASS  开始菜单清掉

########## 4. REINSTALL + /REMOVEDATA ##########
  PASS  重装成功        PASS  旧配置仍在
  PASS  不卡死         PASS  程序目录删除
  PASS  用户数据一并删除
```

> 过程中发现并修掉一个真 bug：卸载时询问「是否删除配置」的对话框，
> 在 `/VERYSILENT` 静默模式下会**永久挂起等点击**。
> Inno 的 `/SUPPRESSMSGBOXES` 只自动应答安装阶段的内建消息，
> 不会应答自定义 `MsgBox`。已改为检测命令行后跳过询问，
> 并新增 `/REMOVEDATA` 供无人值守场景使用。

---

### 包内有什么（为什么 62.4 MB）

| 部分 | 解压后 |
|---|---|
| `runtime\` 精简 JDK | 150.5 MB |
| **`vlc\` 精简 libVLC（含解码器）** | **62.5 MB** |
| `app\YspLive-Desktop.jar` | 12.9 MB |
| 合计 | 230.6 MB → 安装包压到 62.4 MB（LZMA2） |

`vlc\` 里是真解码器，不是空壳：

```
libvlc.dll / libvlccore.dll
plugins\demux      ← HLS / TS 解复用（46 个插件）
plugins\codec      ← FFmpeg 静态链入，H.264 / HEVC / AAC 全支持
plugins\access     ← HTTP / HTTPS 拉流（36 个插件）
plugins\video_output / video_chroma / audio_output
```

---

### 功能

- **64 路频道**：央视 30 路（含 CCTV-4K / 8K / 16-4K）+ 卫视 34 路
- 筛选 chip：全部 / 央视 / 卫视 / 4K / 收藏，**筛选会被记住**
- 单击播放，右键收藏
- 「继续观看」直达上次频道
- 播放窗口：`空格` 播放暂停 · `F` 全屏 · 双击全屏 · `Esc` 退出
- **局域网共享**：`http://<本机IP>:8767/all.m3u` 可给 TiviMate / PotPlayer / VLC / Kodi
- 诊断页 / 日志页

---

### 其它验证

| 项 | 结果 |
|---|---|
| 内置 libVLC 定位 | ✅ `<程序目录>\vlc\libvlc.dll` |
| 端到端播放 | ✅ `state=PLAYING tracks=2 maxProgressMs=35946` |
| CCTV-4K (HEVC) | ✅ `state=PLAYING tracks=2  RESULT: PASS` |
| 界面构建 | ✅ `channelCardCount=64 chipCount=5 gridSize=1104x1590` |

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

本仓库不含上游原始 Python/JS 实现。第三方组件：libVLC (LGPL-2.1+)、
vlcj (GPL-3.0)、JavaFX (GPL-2.0 + Classpath Exception)、JNA (Apache-2.0)。
所有视频内容版权归央视及各卫视所有。本项目不存储、不分发任何视频内容。
仅供学习研究，请勿商用或公开传播。
