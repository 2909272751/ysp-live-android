## ✨ v9.5.3 — 体验优化：播放页自动横屏 + 手势 + 记忆筛选

点击问题在 v9.5.2 修好后，这一版做体验打磨。**核心是修掉「手机打开播放页不会自动横屏」。**

---

### 1. 播放页自动横屏（你说的那个）

**根因**：Manifest 里播放页用的是 `screenOrientation="fullUser"`。

这个值的含义是「跟随系统方向」，**但它会尊重系统的「竖屏锁定」开关**。而绝大多数手机用户
都开着竖屏锁定——于是播放页永远停在竖屏，看着极难受。

**修法**：按设置强制横屏。

```java
// 默认开启，可在设置里关闭
setRequestedOrientation(SCREEN_ORIENTATION_SENSOR_LANDSCAPE);  // 手机：允许左右横屏
setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);         // TV：固定横屏
```

- 退出播放页时**恢复进入前的方向**，不会把主界面也锁成横屏
- 设置里新增「播放页强制横屏」开关，想跟随系统可以关掉
- TV 端自动识别（`UI_MODE_TYPE_TELEVISION`），固定横屏不做传感器判断

### 2. 播放页手势

- **单击**：呼出/收起控制条
- **双击**：播放 / 暂停（有 Toast 反馈）
- **左右滑**：换台（要求横向位移明显大于纵向，避免误触）
- 触摸时自动重开控制条隐藏计时

设置里可关闭。

### 3. 记住筛选

上次看「卫视」，重开 App 还是「卫视」——不再每次都回到「全部」。已加自动化测试保证。

### 4. 错误提示改成用户看得懂的话

以前直接抛 `ERROR_CODE_IO_BAD_HTTP_STATUS` 这种字符串。现在按错误码翻译：

| 情况 | 提示 |
|---|---|
| 网络断开/超时 | 网络连接失败，请检查网络后重试 |
| 源站 4xx/5xx | 频道源暂时不可用，正在自动切换线路 |
| 解码失败 / 格式不支持 | 当前设备不支持该频道编码（4K/8K 需要 HEVC 硬解），可切换到 1080p 频道 |
| 数据异常 | 频道数据异常，正在自动重试 |

技术细节仍写入日志（App 内「诊断」页可看），只是不再糊到用户脸上。

---

### 验证

**app 层 22 个测试全绿**，其中新增 5 个专门测横屏：

| 测试 | 断言 |
|---|---|
| `playerForcesLandscapeByDefaultEvenWithPortraitLock` | 默认请求横屏，且**不再是** `FULL_USER` |
| `landscapeRequestedWhileDeviceIsPortrait` | 设备竖屏时播放页仍请求横屏 |
| `turningOffForceLandscapeFallsBackToSystem` | 关掉开关后跟随系统 |
| `orientationRestoredOnDestroy` | 退出时恢复方向 |
| `playerActivityCreatesWithoutCrash` | 播放页能正常创建 |

加上 core 的 255 个，共 **277 个测试全绿**。

产物：`apksigner verify` 通过，versionCode 908，双 launcher 正常。

---

### 下载哪个

| 文件 | 大小 | 说明 |
|---|---|---|
| **`YspLive-v9.5.3.apk`** | 3.58 MB | **发布版，推荐** |
| `YspLive-v9.5.3-debug.apk` | 4.56 MB | 调试版，带完整日志 |

- 包名 `com.gary.ysplive` · versionCode `908` · versionName `9.5.3`
- SHA-256：`D5B4C02EBC3B41AF29D9A00BB0D3FCD9561D94E63543B1DA7982C383BB17A4E5`

覆盖安装即可，不用先卸载。

---

### 如果横屏还是不对

请告诉我：
1. 手机系统里「方向锁定」是开还是关？
2. 播放页是**完全没转**，还是**转了又被转回去**？
3. 设置里「播放页强制横屏」显示的是「已开启」吗？

如果是转了又弹回去，那是 Activity 重建导致的，我需要看 `configChanges` 的实际行为；
如果是完全没转，可能是 ROM 层面的 `screenOrientation` 强制忽略（部分国产 ROM 会这样）。

---

### 已知限制（未变）

1. **必须能直连 `*.cctv.cn`**。刻意不走系统代理（央视频对代理 IP 会 403）。
2. 4K/8K 需设备 HEVC 硬解 + 带宽 ≥ 50 Mbps。
3. 时移回看取决于源站保留策略（一般 7 天）。

---

### 来源与声明

协议与算法移植自 **Gary's Club** 发布的 [ysp-live v9.5](https://garysclub.sharewithyou.dpdns.org/ysp-live/)。

原作者声明：首发地址为社区 Gary's Club / 频道 Gary's Club Channel，
**其他任何分发渠道均非作者本人**。本项目为第三方移植，与原作者无关、未获授权或背书。
**若原作者要求，将立即删除。**

本仓库不含上游原始 Python/JS 实现。所有视频内容版权归央视及各卫视所有，
本项目不存储、不分发任何视频内容。仅供学习研究，请勿商用或公开传播。
