## 🔧 v9.5.8 · 修复「退不出后台」+ 托盘菜单乱码 + 滚动超出底部

你反馈的三个问题都是真 bug，已全修。

**确认版本**：主界面标题栏显示 `央视频全频道直播 v9.5.8 · Windows 版`。

---

### ① 怎么点都退不出后台（最严重）

**两个原因叠加：**

1. `shutdown()` 只调了 JavaFX 的 `Platform.exit()`。
   但播放器窗口是 **Swing/AWT** 的，`Platform.exit()` 结束不了 AWT 线程，
   进程就一直挂在后台。
2. 主窗口的 ✕ 是「收进托盘」（不是退出），而托盘菜单又是乱码，
   导致你**根本找不到能真正退出的入口**。

**修复：**

- `shutdown()` 清理完资源后，250ms 内 `Runtime.halt(0)` **强制结束进程**
- 关掉 JavaFX 的隐式退出（`setImplicitExit(false)`），避免
  「JavaFX 死了、Swing 还活着」的僵尸状态
- **主界面右上角新增「退出」按钮**（平时暗红、悬停变亮红），
  一眼可见，点了就是真退出
- 托盘菜单拆成三项：`打开选台界面` / `停止播放并回到选台` / `退出程序`

**实测**（子进程计时，不是看代码）：
```
process exited in 12.7 s
[app] 强制退出进程
RESULT: PASS - shutdown() terminates the process
```

---

### ② 托盘右键菜单是一排方框

AWT 的 `PopupMenu`/`MenuItem` 默认字体在部分 Windows 上**不含中文字形**，
所以「打开选台界面」全变成方块。

**修复**：用 `Font.canDisplayUpTo()` 实际验证，按优先级探测
`Microsoft YaHei UI → Microsoft YaHei → SimSun → SimHei → Dialog`，
选中第一个能画出中文的字体并应用到每个菜单项。

实测：
```
[tray] 托盘菜单字体 = Microsoft YaHei UI
能显示中文 = true
```

---

### ③ 滚动可以超过最下面

虚拟化网格用**估算的卡片高度**（软件渲染按 134px）计算内容总高度，
但真实卡片比它矮，导致内容高度虚高，滚动条能拖到所有卡片下方一大片空白。

**修复**：拿到第一张卡片后**实测它的真实高度**（`prefHeight(-1)`），
据此重算内容高度并校正滚动范围。

实测：
```
contentH=1478  viewportH=654  scrollable=824
内容高度合理 = true
```

---

### 顺带修掉一个会让你「画面消失」的隐藏 bug

全屏切换（按 `F` 或双击）原来用 `dispose()` + `setUndecorated()` 实现。
但 `dispose()` 会触发 `windowClosed` → `release()` → **把播放器整个释放掉**
（停止播放、释放 libVLC）。所以按 F 之后窗口虽然还在，画面已经死了。

已改为只切窗口状态、不销毁窗口，并记住退出全屏前的尺寸。
实测最大化前后 `播放器状态 before=PLAYING after=PLAYING`。

---

### 关于「还是没看到侧边栏和换台地方」

我用诊断程序实测了面板在各状态下的可见性，**结果都正常**：

```
=== 播放器面板（默认尺寸 1280x760）===
  SidePanel    vis=true  280x656 @0,0
  ControlBar   vis=true  1268x69 @0,656
=== 放大到 1600x900 ===
  SidePanel    vis=true  280x796
  ControlBar   vis=true  1588x69 @0,796
=== 最大化 1933x1045 ===
  SidePanel    vis=true  280x941
  ControlBar   vis=true  1921x69 @0,941
```

所以如果你那边仍然看不到，极可能是**旧版本残留**。请：
1. 卸载旧版（设置 → 应用 → 央视频直播 → 卸载）
2. 装 `YspLive-Setup-9.5.8.exe`
3. 看标题栏是否显示 **v9.5.8**

如果标题栏是 v9.5.8 但仍没有侧栏，请把标题栏和整窗截图发我。

---

### 下载

| 文件 | 大小 | 适用 |
|---|---|---|
| **`YspLive-Setup-9.5.8.exe`** | **62.6 MB** | **推荐**，标准安装程序 |
| `YspLive-Windows-v9.5.3-selfcontained.zip` | 92.9 MB | 免安装绿色版 |
| `YspLive-Desktop.jar` | 13 MB | 单文件 jar，需自备 `vlc\` 目录 |

SHA-256
- `Setup-9.5.8.exe`：`CC1431CFD7A1A533C495DE385887578A22C9D690DB717760B2DC9C0E9D08F2D2`
- `selfcontained.zip`：`07C934801635A225861422DF02B5A6E0F3F1D0FBE16E847F4CE79BBFDBAC98A7`

---

### 验证

- `verify-all.ps1` 17 项全过
- `verify-exit.ps1` 进程退出实测 PASS
- `FixCheck` 托盘字体 / 面板可见性 / 全屏不杀播放器 / 滚动范围 全 PASS

---

### 上一版已加的功能（本版保留）

- 播放列表**右键菜单**：收藏 / 取消收藏 / 复制播放链接
- **进度条拖动**（直播流有约 56 秒缓冲窗口，可拖）
- **完整位置记忆**：播放器窗口位置大小、侧栏状态、音量、各频道播放进度
- 控制条常驻、矢量图标、换台真切流、断流自动重连、频道搜索

---

### ⚠️ 4K / 8K 仍是 1080p

上游 `app/start` 恒返回 HTTP 400（原作者 Python 版同样如此），
界面已如实标注为琥珀色 `1080p`。

---

### 来源与声明

协议与算法移植自 **Gary's Club** 发布的 [ysp-live v9.5](https://garysclub.sharewithyou.dpdns.org/ysp-live/)。

原作者声明：首发地址为社区 Gary's Club / 频道 Gary's Club Channel，
**其他任何分发渠道均非作者本人**。本项目为第三方移植，与原作者无关、未获授权或背书。
**若原作者要求，将立即删除。**

第三方组件：libVLC (LGPL-2.1+)、vlcj (GPL-3.0)、
JavaFX (GPL-2.0 + Classpath Exception)、JNA (Apache-2.0)。
所有视频内容版权归央视及各卫视所有。仅供学习研究，请勿商用或公开传播。
