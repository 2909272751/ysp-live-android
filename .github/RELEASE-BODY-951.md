## 🔧 v9.5.1 — 修复手机端点击完全无反应

**这个版本修了 v9.5.0 的严重可用性 bug：手机上点频道不进播放器、顶部筛选 tag 切换没反应，只有电视能用。如果你在手机上装过 v9.5.0，请务必换这个版本。**

---

### 两个 bug 的根因

都是**触屏与焦点语义**的问题。电视上焦点始终停在控件上，所以两个问题都被完全掩盖了——这就是它看起来像「电视专用版」的原因。

**1. 点频道完全没反应**

`item_channel.xml` 的根布局带了 `android:clickable="true"`。

```java
// ViewGroup.dispatchTouchEvent 的核心逻辑（AOSP）
if (child.isClickable() || ...) {
    // 触摸事件被子视图消费，不再上报给 GridView
    intercepted = false;
}
```

子视图可点击时，`GridView.onItemClick` **永远不会触发**（父列表认为触摸已被子视图吃掉），
而条目自身又没绑点击监听 → 点击事件凭空消失。

**2. 顶部 tag 必须点两下**

`Ui.makeChip()` 和几个 item 布局用了 `focusableInTouchMode="true"`。

```java
// View.onTouchEvent 的核心逻辑（AOSP）
boolean focusTaken = false;
if (isFocusable() && isFocusableInTouchMode() && !isFocused()) {
    focusTaken = requestFocus();     // 第一次点击只用来"获取焦点"
}
...
if (!focusTaken) performClick();     // 所以第一次点击不会触发 click
```

第一次点击被吞去获取焦点，第二次才真正执行点击。

### 修复

- 移除**全部** `focusableInTouchMode`（关键布局内加了注释，防止以后被加回来）
- `item_channel` 去掉 `clickable`，让 `onItemClick` 正常分发；保留 `focusable` 供遥控器 D-pad
- `openPlayer` 加 600ms 去抖，防止触摸/焦点双路径重复启动播放页
- 版本号 `906` / `9.5.1`，产物改名 `YspLive-v9.5.1*.apk`

### 验证

| 项 | 结果 |
|---|---|
| `:core:test` | **255/255 通过** |
| `:app:lintDebug` | **无任何触屏可用性告警**（仅剩字号/RTL 等样式提示） |
| `aapt2 dump xmltree` | `item_channel` 已**无** `clickable`，仅剩 `focusable` |
| dex 反查 | **11/11** 关键类在包内（ExoPlayer / PlayerView / HlsMediaSource / 引擎 / 各 Activity） |

> 仍然**没有真机/模拟器验证**（开发机无硬件虚拟化，模拟器起不来）。这次的 bug 正是只能真机发现的。
> 装好后请确认：点频道能进播放页、点顶部 tag 能切换筛选。如果还有问题，把 App 内「诊断」页内容发出来。

---

### 下载哪个

| 文件 | 大小 | 说明 |
|---|---|---|
| **`YspLive-v9.5.1.apk`** | 3.58 MB | **发布版，推荐** |
| `YspLive-v9.5.1-debug.apk` | 4.56 MB | 调试版，带完整日志 |

- 包名 `com.gary.ysplive` · versionCode `906` · versionName `9.5.1`
- `minSdk 21`（Android 5.0+）· `targetSdk 34`
- **同一个 APK 同时支持手机和 Android TV**
- SHA-256（发布版）：`503C2361AE9BA030385B6F67812DF8F5EE9F232173A0FED3C039B7F68C6DDCD1`

### 功能（未变）

- **64 路频道**：央视 30 路（含 CCTV-4K / 8K / 16-4K）+ 卫视 34 路
- **真 4K/8K**：实测 CCTV-4K 单分片 17.7 MB（36M 超高码率）、CCTV-8K 12.3 MB
- **7 天时移回看**
- **双引擎四层兜底**：设备协议 → JCE → bkliveinfo → 上游代理
- **局域网共享**：开一个开关，手机/电视本身当网关给 TiviMate / APTV / PotPlayer 供源

### 已知限制

1. **必须能直连 `*.cctv.cn`**。刻意不走系统代理（央视频对代理 IP 会 403）。
   海外使用需在分流规则里把 `*.cctv.cn` / `*.ysp.cctv.cn` 设为直连。
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
