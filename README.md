# Imminence

**简体中文** · [English](README.en.md)

在悬浮窗里常驻显示**当前前台应用**今天的累计使用时长。

一个可以随手拖动的胶囊，显示 `45min`；超过一小时自动变成 `1h12min`。

## 特性

- **只看前台**：切到哪个应用，就显示那个应用今天累计用了多久
- **尽量省电**：没有唤醒锁、没有秒级轮询、没有网络权限，息屏即停
- **自愈**：进程被杀、手机重启、手动停止后再打开，都能自动算回当天正确的数字
- **纯本地**：不联网、不上传，连 `INTERNET` 权限都不申请

## 工作原理

显示精度是分钟，所以完全不需要每秒刷新。

1. `UsageStatsManager.queryEvents()` 提供 `ACTIVITY_RESUMED`、`SCREEN_INTERACTIVE` / `SCREEN_NON_INTERACTIVE`、锁屏等事件的精确时间戳。
2. `TimelineEngine` 把事件回放成区间：新事件到达时，把 `[上一个事件, 本次事件)` 记给当时位于前台的应用。
3. 正在进行的那一小段在读取时实时补上，数字会自己增长，不需要额外唤醒。
4. `MinuteTicker` 只在「累计时长跨过整分钟」的那一刻安排一次 `postDelayed`。
5. 息屏时定时器直接停掉；而 Doze 本来也只在息屏生效——需求与系统调度边界天然重合，因此全程不需要任何唤醒机制。

## 需要的权限

| 权限 | 用途 | 必要性 |
| --- | --- | --- |
| 使用情况访问 | 读取前台应用切换事件，唯一数据源 | 必须 |
| 显示在其他应用上层 | 悬浮窗 | 必须 |
| 通知 | 前台服务的常驻通知（Android 13+ 需授权） | 建议 |
| 忽略电池优化 | 息屏后不被冻结，并允许看门狗把服务拉回来 | 建议 |
| 自启动 | 重启后自动恢复统计 | 视 ROM 而定 |

## 环境要求

Android 8.0（API 26）及以上。

## 构建

仓库不附带 Gradle wrapper 的二进制文件。二选一：

```bash
gradle wrapper --gradle-version 8.9   # 本机装了 gradle 时执行一次
./gradlew :app:assembleDebug
```

或者直接用 Android Studio 打开，首次同步会自动补齐。

```bash
./gradlew :app:testDebugUnitTest      # TimelineEngine / DaySplit 单元测试
```

## 项目结构

```
core/     TrackerService、TimelineEngine、ForegroundSampler、MinuteTicker、BootReceiver、WatchdogReceiver
data/     UsageStore（内存 + SQLite）、Days、DaySplit、Prefs
overlay/  OverlayController（单胶囊窗口、拖拽、长按隐藏）
perm/     Permissions（使用情况、悬浮窗、通知、电池、自启动）
ui/       MainActivity（权限引导 + 启停）
```

## 已知限制

- **分屏 / 画中画**：两个窗口都会收到 `ACTIVITY_RESUMED`，「最后 resumed」的启发式可能归错边，属于平台 API 的固有限制。
- **跨零点**：零点前开始、且进程在零点后被重启的那段会话不会重放，只重建当天。
- **厂商 ROM**：部分系统会无视规则杀掉前台服务，此时靠开机广播、`START_STICKY` 和 15 分钟看门狗兜底。

## 许可证

MIT，见 [LICENSE](LICENSE)。
