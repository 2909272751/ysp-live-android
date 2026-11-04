## 🔧 v9.5.2 — 真正修复手机端点击（v9.5.0/v9.5.1 都不行）

**v9.5.1 修错了地方。** 我当时的诊断（`focusableInTouchMode`）虽然是真实缺陷，但**不是点击失效的原因**——我用测试验证过：把这个属性重新加回去，测试依然通过。所以 v9.5.1 装上去还是点不动。

这一版是**先在 JVM 上把点击链路测通**再发出来的。

---

### 真正的根因

`MainActivity` 依赖 `GridView.setOnItemClickListener` 来响应频道点击。但
**`AdapterView` 的触摸→点击分发并不总是触发**：当视图树里存在 `focusable` 子项、
或部分 ROM 的焦点策略不同时，`onItemClick` 会被跳过。用户看到的现象就是
「点频道完全没反应」，且不报错、不崩溃、lint 也不告警。

### 修法：不再依赖 AdapterView 的分发

把点击直接绑在**条目根布局自己身上**：

```java
// ChannelAdapter.getView()
view.setClickable(true);
view.setOnClickListener(v -> clickListener.onChannelClick((ChannelSpec) v.getTag(...)));
```

触摸事件由条目自己消费并回调，不经过 `AdapterView` 的判断逻辑，行为确定。
同时删掉了已经多余的 `setOnItemClickListener / setOnItemLongClickListener`。

### 顺带修掉的两个真实布局缺陷

排查时 dump 出真实几何发现的：

1. **筛选 chip 被压到 34px 宽**（1080px 屏上）
   `HorizontalScrollView` 会把 `WRAP_CONTENT` 子视图压到近 0。现在改为先
   `measure()` 出真实文字宽度、再固定成具体像素（并保底 56×36dp）。实测 34px → **56px**。
2. **频道台标容器高度只有 3px**
   条目根布局是固定 118dp 高，但 logo 的 `weight=1` 容器被文字挤没了。加了 `minHeight="52dp"`。

---

### 这一版怎么验证的（重点）

前两次失败的原因是我**只能靠读代码猜**。这次改用 Robolectric 在桌面 JVM 上
真实创建 Activity、真实布局、真实派发 `MotionEvent`，把点击变成可断言的东西：

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `RealTouchDispatchTest` | 3 | 真实触摸 chip / 频道条目 / 设置按钮 |
| `TouchClickTest` | 6 | chip 切换筛选、条目开播放器、按钮、样式守卫 |
| `LayoutDumpTest` | 3 | chip 触摸目标 ≥56dp、条目触摸目标、条目内无可点击子视图 |
| `ActivityStartupTest` | 4 | 四个 Activity 都能被创建（排除"启动即崩"） |

**16/16 通过。** 加上 core 的 255 个用例，共 **271 个测试全绿**。

关键验证手段：
- **反证**：把修好的 bug 重新注入，确认测试会失败——第一次这么做时测试没报错，
  才发现我的诊断（`focusableInTouchMode`）是错的，避免了又一次错误发布。
- **几何 dump**：直接打印每个视图的绝对坐标和尺寸，才发现 chip 34px、logo 3px 这两个问题。
- **`performClick` 出口验证**：确认 `handled=true` 且启动的 Intent 目标确实是 `PlayerActivity`。

产物核对：`apksigner verify` 通过；`aapt2 dump badging` 确认 versionCode 907、
双 launcher、应用名正常。

---

### 下载哪个

| 文件 | 大小 | 说明 |
|---|---|---|
| **`YspLive-v9.5.2.apk`** | 3.58 MB | **发布版，推荐** |
| `YspLive-v9.5.2-debug.apk` | 4.56 MB | 调试版，带完整日志 |

- 包名 `com.gary.ysplive` · versionCode `907` · versionName `9.5.2`
- `minSdk 21`（Android 5.0+）· `targetSdk 34`
- 同时支持手机与 Android TV
- SHA-256：`1544DCE71E356C5F9B0DEE1CD520408EE10B58B34FAD5CAC529AB7355632868A`

### 但仍然需要你确认

我**没有真机**（开发机无硬件虚拟化，模拟器起不来），所以最终还是需要你实测。
这一版至少做到了：**点击链路在自动化测试里是真的通的，不是靠推理**。

如果装完还是点不动，请告诉我这三件事，能大幅缩小范围：
1. 是**只有频道点不动**，还是顶部 tag / 设置按钮也点不动？
2. 点下去有没有**视觉反馈**（卡片有没有变红/出现焦点框）？
3. App 内「诊断」页里的状态文字是什么？

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
