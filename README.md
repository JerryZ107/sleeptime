# SleepTime

一个自用的 Android 睡前锁机 App：到点全屏压黑，Home / 最近任务 / 通知栏全部失效，唯一解锁方式是长按黑屏满设定时长。

- 当前默认：`23:30` 锁定，`06:00` 自动解除
- 当前默认分支：`good2-limitchange`
- 不联网、不上架、无广告、无统计

## 功能

- 每日定时锁定 / 自动解除
- 全屏黑幕 + 系统屏幕固定
- 长按解锁：3 / 5 / 10 分钟
- 来电、微信通话时自动让出屏幕，并放开麦克风
- 锁屏下拉快捷开关：手电筒、系统静音、勿扰模式
- 锁屏时间修改限制：
  - 逻辑日按每天 `04:00` 分界
  - 每个逻辑日最多修改 2 次
  - 每次最多向后延 10 分钟
  - `23:30 -> 00:01` 仍算延后（+31 分钟），会被拦住
  - 提前修改不限幅度，但会消耗一次修改机会
- 通知详情开关：默认开启；关闭后只保留 Android 要求的最小前台服务通知
- 自愈保活：前台服务 + 精确闹钟 + JobScheduler + 看门狗 + 显式广播唤醒

## 工程结构

- `app/`：主 App，包名 `com.sleeptime.lock`
- `waker/`：可选外部唤醒器，包名 `com.sleeptime.waker`
- `docs/DEV_NOTES.md`：旧版开发笔记与踩坑记录
- 其他分支 / tag：历史版本存档

## 分支说明

- `good2-limitchange`：当前最新版本。默认 23:30 / 06:00，逻辑日 04:00 分界，每天 2 次修改机会，每次最多延后 10 分钟
- `good1`：通知详情开关版本
- `me`：纯黑幕、无下拉面板的基线版本
- 其他分支和 tag 为历史版本

## 编译

要求：

- Android Studio Ladybug 或更新版本
- JDK 17
- Android SDK 34

```powershell
gradle assembleDebug
```

或者直接使用 Android Studio 打开根目录。

APK 路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 安装后必须做的设置

- 悬浮窗权限
- 精确闹钟权限
- 通知权限
- 自启动 / 后台运行 / 电池不优化
- ColorOS / realme UI 额外设置：
  - 允许完全后台行为
  - 最近任务卡片加锁
- 电话权限：来电解麦用
- 勿扰权限：免打扰开关用，可选

## 已知限制

- ColorOS 从最近任务上划时可能直接 `o-kill` 并 `prevent start`。这种情况下普通 App 无法自己复活，只能靠外部显式广播（adb / Tasker / waker）或重新打开 App。
- 静音和勿扰是两个独立模式。ColorOS 的 DND 会联动 ringer 模式，因此静音按钮使用流静音，不调用 `setRingerMode`。
- 时间判断使用手机系统时间。改系统时间或时区可以绕过每日修改次数限制。
- 锁定期间会全局静音麦克风。来电时通过 `PhoneStateReceiver` 解麦，需要电话权限。
- 自用项目，不上架应用商店。全屏覆盖和阻止退出的行为不符合 Google Play 政策，请自签自用。

## 仓库

https://github.com/JerryZ107/sleeptime
