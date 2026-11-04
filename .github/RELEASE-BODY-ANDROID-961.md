## 安卓版拆分为「手机版」和「电视版」

以前一个 APK 同时装手机和电视，交互模型互相妥协——**这正是「遥控器左右键和确认键选不中」的根源**：

- 触摸需要 `clickable` + 大点击区，首次点击要立刻响应
- 遥控器需要 `focusable` + 明确的焦点态，否则用户不知道光标停在哪

一个界面同时满足两者，必然两边都不好用。所以这版拆成两个独立 APK，各有针对性设计。

---

## 📱 手机版 `YspLive-phone-v9.6.1.apk`

包名 `com.gary.ysplive.phone`，**触摸优先**：

- 竖屏为主，顶部搜索框 + 分类 chips
- 频道卡片网格，点击立即播放
- 「继续观看」快捷入口
- 长按/点击进频道操作

## 📺 电视版 `YspLive-tv-v9.6.1.apk`

包名 `com.gary.ysplive.tv`，**遥控器（10 英尺）优先**，完全重新设计：

| 设计点 | 说明 |
|---|---|
| 全屏焦点高亮 | 卡片聚焦时**放大 1.07 倍 + 亮黄描边**，远距离一眼看清 |
| 确定性焦点路径 | `◀ ▶` 切分类 → `▼` 进网格 → 网格内四向移动 → 确认键播放 |
| 去掉输入框 | 电视上打字是灾难，用分类 + 收藏替代 |
| 底部常驻键位提示 | `◀ ▶ 切换分类　▲ ▼ 选择频道　确认键 播放　长按确认 收藏　返回 退出` |
| 长按确认 = 收藏 | 电视没有右键菜单，这是最顺手的替代 |
| 返回键分层 | 在分类栏按返回先回到「全部」，再按才退出 |
| 大字号 / 高对比 | 字号 17–30sp，深色底 + 高对比文字，适合 3 米观看 |

同时注册了 `LEANBACK_LAUNCHER`，会出现在电视的「应用」行。

---

## 两个包可以同时安装

包名不同：
- `com.gary.ysplive.phone`
- `com.gary.ysplive.tv`

互不覆盖，同一个手机里可以都装上对比。

## 硬件声明也分开了

- 手机版：`android.hardware.touchscreen` **required=true**
- 电视版：`android.software.leanback` **required=true**

这样应用商店/系统能正确识别该装到哪类设备上。

---

## 验证

新增 TV 焦点回归测试 `TvFocusTest`，专门锁住「遥控器能不能用」：

```
testTvDebugUnitTest   TvFocusTest   tests=2 fail=0
  ✔ tvGridItemsAreFocusable   频道卡片可聚焦、网格非空
  ✔ tvKeyHandlingDoesNotCrash D-pad 上下左右+确认不崩溃
```

完整测试结果（两个 flavor 分别跑各自的测试集）：

```
testPhoneDebugUnitTest  5 个类  22 项  0 失败
testTvDebugUnitTest     2 个类   7 项  0 失败
TOTAL tests=29  failures=0
```

测试也按 flavor 分开了：手机 UI 测试放 `src/testPhone`，TV 焦点测试放 `src/testTv`，
公共播放器测试放 `src/test`。

---

## 已知

- **4K / 8K 仍为 1080p**：上游 `app/start` 恒返回 HTTP 400（原作者 Python 版同样如此），界面如实标注为琥珀色 `1080p`。
- 手机版本轮沿用原有触摸界面（已验证可用），下一轮会按触摸习惯重新设计视觉。

## 来源与声明

协议与算法移植自 **Gary's Club** 发布的 [ysp-live v9.5](https://garysclub.sharewithyou.dpdns.org/ysp-live/)。
本项目为第三方移植，与原作者无关、未获授权或背书。**若原作者要求，将立即删除。**

所有视频内容版权归央视及各卫视所有。仅供学习研究，请勿商用或公开传播。
