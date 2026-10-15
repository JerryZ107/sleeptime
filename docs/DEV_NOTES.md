# SleepTime · 到点把自己锁死的安卓 App

每天 23:30（可改）整屏压黑，黑底 + 赛博朋克艺术字 `SleepTime`，
解锁唯一方式：**手指按住黑屏不放满 3 分钟**（可改），
期间 `SleepTime` 底下只显示一行 `睡眠时间...`，**没有任何进度提示**，抬手即归零重新计时。

自用项目，无紧急通道（来电会让路），带一个控制面板可以改时间 / 停用 / 卸载。

---

## 一、怎么编译

1. 装 **Android Studio**（Ladybug 或更新）+ **JDK 17**。
2. `File → Open`，选中本目录（就是含 `settings.gradle.kts` 的这一层）。
   首次同步会自己下载 Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24。
   > 如果提示 `gradle-wrapper.jar` 缺失：`File → Sync Project with Gradle Files` 一般会自动补；
   > 或者本机装了 Gradle 的话在根目录跑一次 `gradle wrapper --gradle-version 8.7`。
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`，产物在
   `app/build/outputs/apk/debug/app-debug.apk`。
4. 手机开 USB 调试，`adb install -r app-debug.apk`，或直接把 apk 拷进手机装。

- `minSdk 26` / `targetSdk 34` / `compileSdk 34`
- 包名 `com.sleeptime.lock`（想改就改 `app/build.gradle.kts` 里的 `namespace` 和 `applicationId`，同时改 `AndroidManifest.xml` 里的 `.MainActivity` 等相对名不用动）

---

## 二、字体（想要正宗赛博朋克味就做这一步）

App 按这个优先级取字体：

1. **你在 App 里导入的 .ttf** → 存到 `filesDir/sleeptime_font.ttf`
2. **`app/src/main/assets/fonts/orbitron.ttf`**
3. 系统 `sans-serif-black` 超粗体兜底（配合霓虹辉光 + 品红色差 + CRT 扫描线，也不算难看）

推荐免费商用字体（OFL）：

- **Orbitron** <https://fonts.google.com/specimen/Orbitron> ← 最贴合赛博朋克
- Audiowide <https://fonts.google.com/specimen/Audiowide>
- Share Tech Mono <https://fonts.google.com/specimen/Share+Tech+Mono>

下载 → 解压 → 把 `.ttf` 改名成 `orbitron.ttf` → 丢进 `app/src/main/assets/fonts/`。
**或者** 不碰代码：装好 App 后进控制面板点「导入 .ttf」，用系统文件选择器挑一个字体文件即可。

> ⚠️ 注意用户说的是 `SeepTime`，我按 `SleepTime` 实现的（应该是笔误）。
> 真要 `SeepTime` 就全局搜 `SleepTime` 替换，或改 `SleepLockView.kt` 里两处 `text = "SleepTime"`。

---

## 三、装好之后必须做的三件事（不做就不会锁）

进 App 控制面板，把「权限自检」里的几行全部点亮（悬浮窗 / 精确闹钟 / 通知 / 自启动 / 后台运行 / 电池优化）：

| 权限 | 为什么必须 |
|---|---|
| **悬浮窗** | 黑幕本体；同时它是「后台直接拉起全屏 Activity」的官方豁免条件，没它到点只会弹个通知 |
| **精确闹钟** | 到点准时触发，没它可能晚十几分钟 |
| 通知 / 全屏提醒 | 前台服务存活 + 锁屏被系统压住时用全屏 Intent 强行冒头 |
| 电池优化白名单 | 防止半夜被系统清理 |

然后**国产 ROM 还得再来一道**（MIUI / EMUI / ColorOS / OriginOS / Flyme 都必须）。
ColorOS / realme UI 上就是控制面板里带「自检 / 直达」两个按钮的那两行：

| 行 | 要开的东西 | 路径 |
|---|---|---|
| **自启动** | 允许自启动 | 设置 → 应用 → 自启动 → SleepTime |
| **后台运行** | 耗电管理 → **允许完全后台行为** | 设置 → 应用 → SleepTime → 耗电管理（就是「后台管理」那一页） |

外加：省电策略选「无限制」，最近任务卡片上加锁。

### 3.1 这两行的「自检 / 直达」分别是什么意思

- **自检** —— 系统什么查询接口都没给（ColorOS 的 `startup.provider` 要 signature 级权限，
  第三方 App 连读都读不到），所以只能**实测反推**：
  - **自启动**：只看这一次开机有没有收到 `BOOT_COMPLETED`。收到了就说明开关真开着（这条骗不了人）；
    没收到就是被系统拦了。**装完或更新完要重启一次手机这行才有结论**，
    在那之前会显示「本次开机之后才装/更新过，还测不出来」。
  - **后台运行**：守护服务心跳断档（真被冻结过一定留记录）+ 系统待机分桶（EXEMPTED / ACTIVE / RARE…）。
    如果这行红着、而你已经确信系统里开好了：点「自检」→「清掉记录，重新实测」，把旧证据清掉重新数。
- **直达** —— 先硬跳 ColorOS 那两页原生设置；**跳不进去**（见下）就退到「应用详情」页，
  那一页里正好摆着「耗电管理」入口，再点一下就到位。

> 实测（realme RMX5010 / ColorOS 15 / Android 15）：
> ```bash
> adb shell am start -n com.oplus.battery/com.oplus.startupapp.view.StartupAppListActivity
> # java.lang.SecurityException: ... requires oplus.permission.OPLUS_COMPONENT_SAFE
> adb shell am start -n com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerControlActivity
> # 同上
> ```
> 「自启动管理」「耗电管理」连同它们的数据 provider 都被 OPPO 的
> `oplus.permission.OPLUS_COMPONENT_SAFE`（signature 级）锁着 ——
> **第三方 App 跳不进去，`adb shell`（uid 2000）也跳不进去**，只有系统设置和签名应用可以。
> 所以「直达」的最后一步必然落在应用详情页上：不是偷懒，是权限墙。

最后点一次「**立即测试锁定**」，会弹出系统的「要固定此屏幕吗？」，
勾上「**不再询问**」再确认。以后每次都是静默钉住。

---

## 四、锁定期间到底发生了什么

触发瞬间，App 同时做四件事：

1. `AlarmManager.setAlarmClock()` 精确到点 → 广播 → 前台服务 + 全屏 Activity 双管齐下；
2. **全屏 Activity**：`setShowWhenLocked` + 沉浸式，盖在锁屏之上，独立 task、不进最近任务；
3. **悬浮窗**：`TYPE_APPLICATION_OVERLAY` + `FLAG_LAYOUT_NO_LIMITS`，把状态栏和导航栏区域一起盖住；
4. **屏幕固定**：`startLockTask()`，Home 键 / 最近任务 / 下拉通知栏全部失效。

外加一个**看门狗**每秒跑一次：只要锁屏 Activity 不在最前面，立刻把它拽回来。
就算你从通知栏溜进设置把服务强停，`START_STICKY` + 看门狗也会把它拉起来。

### 4.1 到点为什么能锁上：三道保险 + 一次预热

国产 ROM 会「按住」后台 App（这台 ColorOS 实测：熄屏后每 10 分钟才被闹钟唤醒一次，
进程在中间是被冻住的），所以「到点锁不上」几乎都是**叫不醒**，不是逻辑错。现在是四条腿走路：

| 通道 | 谁在管 | 作用 |
|---|---|---|
| **每日主闹钟** `setAlarmClock` | AlarmManager | 到点唤醒 + 允许后台启动 Activity（系统会给 10 秒临时白名单） |
| **预热闹钟** 提前 2 分钟 | AlarmManager | 先把服务叫醒，到点那一刻不用冷启动 |
| **备份闹钟** 到点 +90 秒 | AlarmManager | 主闹钟哑了就在 90 秒后补一刀 |
| **持久化任务** 每 15 分钟 | JobScheduler（`setPersisted`） | 重启后由**系统**自己重排，开机广播没收到、App 被杀都能把服务拉回来 |
| 看门狗 | 自己排的循环闹钟 | 锁定时段内 2 分钟一次，平时 10 分钟一次 |

另外，**到点那一刻是广播接收器直接抬锁**（悬浮窗 + 全屏 Activity），不再只押注
「服务能起来并 tick 到」；被系统拦下启动 Activity 时，`SYSTEM_ALERT_WINDOW` 的悬浮窗照样铺满屏幕。

> 早期版本为了「不被冻结」挂了一个 24 小时不放的 partial wake lock ——
> 实测没用（ColorOS 照样冻），还把设备续航吃光、被电池 App 通报「异常耗电」，
> 现在改成只有上锁后的前 3 分钟短暂持有，超时自动释放。

### 4.2 到点不锁的第一嫌疑人：App 被「强行停止」过

Android 有一条硬规则：**被「强行停止」的 App 收不到任何广播，闹钟也全部被撤销**，
一直到用户**手动点开它**为止。所以如果之前点过：

- 应用详情 → 强行停止
- 设置 → 应用 → SleepTime → **禁止后台运行**（ColorOS 会直接 force-stop）
- 第三方「一键清理 / 省电管家」把 SleepTime 停掉

那么「自启动」开着也白开 —— App 已经死了，开机广播根本不会投递给它。
症状就是那句「**得自己打开才能被锁**」。补救：打开一次 SleepTime 就行，之后它会把闹钟全部重排。

> 顺带一个 Android 的常识：`BOOT_COMPLETED` 是在**开机后**投递的（ColorOS 上实测晚了 1~5 分钟），
> 所以重启之后别急，等几分钟；期间不需要做任何操作。

### 4.3 唤醒契约：一条广播就能把它从「强行停止」里救回来

被 force-stop 的 App 是 stopped 状态：进程被杀、闹钟和 Job 全撤销、隐式广播收不到。
**但显式 intent（指名到组件）照样投递，而且投递时系统还会把 stopped 清回 false** —— 实测（ColorOS 15）：

```
am force-stop com.sleeptime.lock
  → stopped=true，待触发闹钟 4 条 → 0，Job #2001 消失
am broadcast -a com.sleeptime.lock.WAKE -n com.sleeptime.lock/.WakeReceiver
  → stopped=false，闹钟 4 条全回来，Job 也回来，服务被拉起
```

所以任何**还活着的** App / 自动化工具 / adb 都能一句话把它救活：

```bash
# adb
adb shell am broadcast -a com.sleeptime.lock.WAKE -n com.sleeptime.lock/.WakeReceiver
```

```kotlin
// Tasker / MacroDroid / 伴侣 App
sendBroadcast(Intent("com.sleeptime.lock.WAKE")
    .setComponent(ComponentName("com.sleeptime.lock", "com.sleeptime.lock.WakeReceiver")))
```

幂等：没被停的时候这条广播只是重排一遍闹钟，没有任何副作用。
醒来后它会自己：重排每日闹钟 + 重建持久化 Job + 拉起守护服务，**如果当时已经过了锁定时刻，立刻补锁**。

> 想更省心：在自动化工具里加一条「每天锁定前 10 分钟发一次 WAKE」，等于给 App 上了外挂保险。

### 4.4 防患：别让它被系统「停」掉（ColorOS / realme UI）

| 会把它打成 stopped 的操作 | 怎么办 |
|---|---|
| 应用详情 → **强行停止** | 别点。点了就靠上面的唤醒契约救 |
| 耗电管理 → **禁止后台运行** | 别选，选「允许完全后台行为」 |
| 应用详情 → **管理闲置应用**（Android 的 App 休眠，长期不用会自动停） | **关掉自动休眠** —— 这是唯一会自动把 App 打成 stopped 的系统行为 |
| 一键清理 / 省电管家类工具 | 把 SleepTime 加进白名单，别让它停 |

### 4.5 「到点到底锁没锁上」现在能自检了

排闹钟时会把「下一次该锁的时刻」记下来，真锁上时再记一笔「实际锁定时刻」。
两者一对：

- 该锁的时刻过去了、`lastLockAt` 却还在它之前 → **「预定 xx:xx 那次锁定没锁上」**，
  说明系统把 App 按住了 —— 这是「到点不锁」最硬的证据，直接显示在「后台运行」那行。

**来电（含微信语音）会自动让路**：`AudioManager.getMode()` 检测到
`MODE_RINGTONE` / `MODE_IN_CALL` / `MODE_IN_COMMUNICATION` 时，
撤掉悬浮窗 + 退出屏幕固定 + 关掉锁屏 Activity，把舞台让给通话界面；挂断后 1 秒内自动恢复锁定。
不需要任何运行时权限。不想让路就在面板里关掉「来电时让出屏幕」。

---

## 五、唯一的解锁动作

`SleepLockView.kt`：

```
ACTION_DOWN            → 开始计时，"睡眠时间..." 显示出来
ACTION_UP / CANCEL     → 归零，文字消失，重新开始
ACTION_POINTER_DOWN/UP → 第二根手指？作废
pointerCount > 1       → 作废
按住满 holdMillis      → 回调解锁
```

- 全程零进度反馈（想看进度点就把 `SleepLockView.kt` 里 `SHOW_PROGRESS` 改成 `true`，
  会每 30 秒多一个点）。
- 按住期间 `keepScreenOn = true`，防止屏幕超时把自己的计时打断。
- 解锁后写 `lockedTonight = true`，今晚不再锁；**明天 23:30 照常**。

---

## 六、控制面板能干什么

| 位置 | 功能 |
|---|---|
| 状态卡 | 下一次锁定时间 + 每秒刷新的倒计时 |
| 每日锁定时间 | 点「修改」弹 24 小时制 TimePicker |
| 锁定行为 | 总开关 / 屏幕固定 / 来电让路 / 屏幕常亮 / 锁定时点亮屏幕 |
| 解锁难度 | 30 秒（调试）· 1 · 2 · 3（默认）· 5 · 10 分钟 |
| 锁屏字体 | 导入任意 .ttf、恢复内置、实时预览 |
| 权限自检 | 四行状态 + 一键跳系统设置 |
| 操作 | 立即测试锁定 / 停用今晚 / 完全卸载 |

- **停用今晚**：立刻解锁 + 今晚不再自动锁，明天的定时不受影响。
- **完全卸载**：停服务 → 取消全部闹钟 → 清配置 → 删字体 → 弹系统卸载框。

---

## 七、设计上的取舍（想清楚了再用）

- **没有紧急通道**：按需求来的。但系统级的物理出口依然存在，我故意没拦也没打算拦：
  - 屏幕固定的「返回 + 最近任务」组合键（长按可解除固定）
  - `adb shell am force-stop com.sleeptime.lock`
  - 长按电源键关机 / 重启（重启后 `BootReceiver` 会检查：如果还在锁定窗口内且今晚没解锁过，会**立刻重新锁上**）
- **不反卸载、不隐藏、不联网、不申请任何多余权限**。就是一个自律工具，不是病毒。
- 别上架应用商店：这种「全屏覆盖 + 阻止退出」的行为会被 Google Play 按 Device Abuse 直接下架。自签自用没问题。

---

## 八、代码地图

```
app/src/main/java/com/sleeptime/lock/
├── MainActivity.kt      控制面板（唯一的 UI 入口）
├── LockActivity.kt      全屏黑幕 Activity + 屏幕固定
├── LockService.kt       前台服务：悬浮窗 + 看门狗 + 来电让路
├── SleepLockView.kt     黑幕本体：霓虹字 / 色差 / 扫描线 / 长按计时
├── Prefs.kt             所有配置
├── Scheduler.kt         精确闹钟排程
├── AlarmReceiver.kt     到点触发
├── BootReceiver.kt      开机 / 改时间 / 重装后重新排程 + 锁定窗口补偿
├── CallGuard.kt         来电检测（零权限）
└── FontLoader.kt        三级字体回退
```

调参最常去的地方：

- 默认锁定时间 / 长按时长 → `Prefs.kt` 的默认值，或直接用面板改
- 锁定窗口长度（重启后补锁的时间范围）→ `Scheduler.WINDOW_MILLIS`
- 字体大小 / 霓虹颜色 → `SleepLockView.kt` 的 `CYAN` / `MAGENTA` / `textSize = 52f`
- 看门狗频率 → `LockService.kt` 的 `h.postDelayed(this, 1000L)`
---

## 九、真机实测记录（realme RMX5010 / Android 16 / API 36）

已在这台机器上跑通，并修掉了**两个会「变砖」的 bug**。

### bug 1：解锁后黑屏不消失（致命）
`LockActivity.close()` 直接调 `finishAndRemoveTask()`。但设备处于「屏幕固定」时，
系统**拒绝 finish 一个被 pin 的 task** —— 结果是服务停了、3 分钟也按完了，
黑屏却永远留在屏幕上，只能靠 `adb shell am force-stop` 或重启救回来。

修复：
- `close()` 改成 **先 `stopLockTask()` 再 `finishAndRemoveTask()`**，并在 250ms / 900ms 后各补一刀；
- `LockActivity` 加自我兜底：每 1.2 秒看一次 `LockService.running`，服务没了就自己关掉。

实测（hold 临时改成 25 秒）：
```
[21 s] 服务=在跑 | Activity=sleeptime | 固定=PINNED
[27 s] 服务=已停 | Activity=已退出   | 固定=NONE      ← 修复后干净退出
```

### bug 2：闹钟根本没排上（致命）
`dumpsys alarm` 里压根没有我们的记录。原因是这台 realme **不投递 `MY_PACKAGE_REPLACED` /
`BOOT_COMPLETED`** 给这个应用（ColorOS 自己的后台管控接管了），于是除了「手动拨一次开关」
之外没有任何路径去排闹钟。

修复：
1. `Scheduler.arm()` 改成三级降级：`setAlarmClock` → `setExactAndAllowWhileIdle` → `setAndAllowWhileIdle`，
   每级 `runCatching`，全失败会打 `SleepTime` tag 的日志；
2. `MainActivity.onResume()` **每次打开面板都重排一次**（自愈）。

实测确认：
```
I SleepTime: arm() at=1790436600000 canExact=true
I SleepTime: arm: L1 setAlarmClock OK
```
```
dumpsys alarm →
RTC_WAKEUP #86: Alarm{b89e1c6 ... com.sleeptime.lock} uid 10336
  component=com.sleeptime.lock/com.sleeptime.lock.AlarmReceiver
  tag=*walarm*:com.sleeptime.lock/.AlarmReceiver
origWhen = 2026-09-26 23:30:00 CST   ✓
```

### ColorOS 额外的「锁屏显示」授权
第一次锁定时会弹系统弹窗 **「'SleepTime' 需要在锁屏上显示」**，必须点**允许**
（对应 `setShowWhenLocked`，ColorOS 单独要授权）。点过一次就记住了。

### 屏幕固定确认框
`startLockTask()` 第一次可能弹「要固定此屏幕吗」。realme 上实测会直接生效（"不再询问"被记住）。

### 这台 ROM 拦掉 / 放行的 adb 操作（留个记录）
| 操作 | 结果 |
|---|---|
| `appops set <pkg> SYSTEM_ALERT_WINDOW allow` | ❌ SecurityException（shell 无 `MANAGE_APP_OPS_MODES`）→ **悬浮窗必须手动开** |
| `pm grant <pkg> POST_NOTIFICATIONS` | ❌ SecurityException（shell 无 `GRANT_RUNTIME_PERMISSIONS`）→ **通知权限要在 App 里点「去授权」** |
| `dumpsys deviceidle whitelist +<pkg>` | ✅ 电池白名单已加 |
| `install` / `am force-stop` / `input` / `screencap` | ✅ 正常 |
| `adb start-server` | ⚠️ 会卡死（守护进程模式有问题）→ 请用 `adb.exe nodaemon server` 另开一个进程常驻 |

### 自动化钩子
```bash
# 立刻上锁（等价于点「立即测试锁定」）
adb shell am start -n com.sleeptime.lock/.MainActivity --ez lock_now true

# 硬解除（系统级，本 App 拦不住，也不打算拦）
adb shell am force-stop com.sleeptime.lock
```

### 本机工具链位置
```
D:\AndroidDev\jdk17                    OpenJDK 17.0.2（华为云镜像下载）
D:\AndroidDev\gradle-8.7               Gradle 8.7（腾讯云镜像下载）
D:\AndroidDev\sdk                      Android SDK：platform-tools / platforms;android-34 / build-tools;34.0.0
D:\AndroidDev\sdk\platform-tools\adb.exe
D:\AndroidDev\build.cmd                一键编译脚本
D:\PythonFile\SleepTime\local.properties   sdk.dir 已指向上面
```
手工编译：
```powershell
$env:JAVA_HOME = "D:\AndroidDev\jdk17"
D:\AndroidDev\gradle-8.7\bin\gradle.bat -p D:\PythonFile\SleepTime assembleDebug
```
---

## 十、【重要】ColorOS 冻结问题（真机实测结论）

### 现象
App 只要退到后台（按 HOME / 从最近任务划走），**到点就不会锁**。

### 实测证据

**证据 1：force-stop 会连闹钟一起清掉**
| | 划掉前 | force-stop 后 |
|---|---|---|
| 系统闹钟 | 1 条 | **0 条** |
| `stopped` 标记 | false | **true** |

`stopped=true` 之后连开机广播都不再投递。这是 Android 的安全底线，**没有 root / device owner 无法绕过**。

**证据 2：只是按 HOME，进程就被完全冻住**
```
进程 pid=3309，持有前台服务 + 常驻通知，dumpsys 显示 isForeground=true
20 秒内 /proc/3309/stat 的 utime+stime：96 -> 96，差 0 jiffies
=> 进程一条指令都没执行

trace.log（写文件，绕过 ColorOS 对后台应用日志的屏蔽）：
  19:19:39  onCreate canDraw=true
  19:19:40  tick ...
  19:19:50  tick ...          <- 最后一条
  （19:19:44 按了 HOME，10 秒后 Handler 彻底停摆）
```

冻结调度器是 **`com.oplus.athena` + `com.oplus.battery`（RemoteGuardElfService）**。
已排除：standby bucket 已是 ACTIVE(5)、电池优化白名单已在、Doze 未启用、设备在充电、屏幕亮着。

**证据 3：闹钟投递被连带压住**
两条闹钟分别设在 19:02:00 / 19:03:30，到 19:03:52 仍挂着未发，
直到 19:06:19 我把 App 拉到前台，两条才同时开火（**晚了 4 分钟**）。

### 结论

在冻结状态下：**Handler 不跑，AlarmManager 投递也被压住。**
也就是说，在这台机器上，"App 退到后台后到点必锁" **没有任何代码层面的解法**。
冻结发生在 ROM / 内核调度层，被冻的进程执行零条指令 —— 没有 API、没有权限、
没有 trick 能改变这一点，除非 root 或 Device Owner（系统特权应用不受应用冻结影响）。

### 唯一的解法（必须手动点，adb 改不了）

设置 → 应用管理 → SleepTime → **耗电管理 → 允许完全后台行为**
另外两处也建议一起开：
- 手机管家 → 自启动管理 → 允许 SleepTime 自启动
- 最近任务 → 下拉 SleepTime 卡片 → 加锁

> 实测这台 ROM 把 `appops set` 和 `pm grant` 都用 SecurityException 挡死了，
> ColorOS 自己的省电/冻结白名单也不对第三方开放查询，所以只能手点。

### 本轮代码改动（都已编译验证）

1. **守护服务架构**：`LockService` 改为常驻前台服务，到点由服务自己按时间判断上锁，
   不再单纯依赖闹钟能不能准时送达。实测：服务启动后 300ms 内判断出「已过点」并立刻上锁。
2. **修掉备份闹钟失效的 bug**：主闹钟触发后调 `Scheduler.arm()` 会把当天还未开火的备份闹钟
   一起改到明天，导致备份**永远不会触发**。改为 `armAfterFire()` 只重排明天的主闹钟，
   备份在 +90 秒做「校验 + 补锁」。
3. **文件级追踪** `filesDir/trace.log`：因为 ColorOS 会屏蔽后台应用的 `Log.i`，
   logcat 完全不可信，追踪必须落盘。
---

## 十一、小布助手（AI）能盖住黑幕 —— 真因与修复

### 真因：不是"优先级"，是缺了一个标志位
对比两个悬浮窗的 flags：

```
我们  : fl=LAYOUT_IN_SCREEN LAYOUT_NO_LIMITS FULLSCREEN HARDWARE_ACCELERATED
小布  : fl=NOT_TOUCH_MODAL KEEP_SCREEN_ON ... SHOW_WHEN_LOCKED ...
                                              ^^^^^^^^^^^^^^^^
```
两者同为 `type=2038 / layer=111000`。但小布带 `FLAG_SHOW_WHEN_LOCKED`，我们没带。
锁屏（keyguard）一起来时，WMS 把我们的窗口判为 `isVisible=false`，而小布照样显示。
**修复：给悬浮窗的 LayoutParams 加上 `FLAG_SHOW_WHEN_LOCKED`。** 一行的事。

### 加了一层「检测上层 + 抢回」的看门狗
- 每 tick 检查 `overlay.hasWindowFocus()`
- 连续 2 秒没焦点 => 判定被压住 => 把悬浮窗摘掉重挂一次
  （同层窗口「谁后加谁在上面」，重挂即可回到顶层），顺带把 LockActivity 顶到前面
- 4 秒冷却，每次锁定最多抢 60 次，防止被恶意刷导致耗电
- **长按计时存在 `HoldState` 里，跨悬浮窗重建不丢**

### 实测（小布由 `monkey -p com.heytap.speechassist -c LAUNCHER 1` 拉起）
连续叫 3 次小布，trace 记录：
```
检测到上层有窗口，第 1 次抢回
检测到上层有窗口，第 2 次抢回
检测到上层有窗口，第 3 次抢回
```
稳定后 6 次采样：黑 94.2~94.7%、青 1811~1877、**白 0 像素**、焦点始终在我们手里。
「白 0」是关键 —— 小布界面全白，0 白像素说明它一个像素都没露。

### 顺带修掉的 bug
`立即测试锁定` 原来依赖「是否已到设定时间」——如果当前时间还没到设定的锁定时刻，
按那个按钮根本不会锁。已改为 `LockService.lockNow()`，无视时间。