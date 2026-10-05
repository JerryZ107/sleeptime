package com.sleeptime.lock

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * 15 分钟一次的持久化任务（JobScheduler，setPersisted = true）。
 *
 * 这是「第二条命」，跟闹钟互补：
 *   · AlarmManager 的闹钟被 ROM 按住时会迟到甚至不投递；
 *   · JobScheduler 归系统管，重启后由系统自己重排 ——
 *     App 被杀、开机广播没收到、自启动被拦，它都还能把我们叫回来。
 *
 * 醒来只做三件事：重排每日闹钟、接力看门狗、把守护服务拉起来。
 * 到点该锁的话，服务自己第一个 tick 就会抬锁。
 */
class GuardJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val p = Prefs(this)
        if (p.enabled) {
            runCatching { Scheduler.arm(this) }
            runCatching { Scheduler.armWatchdog(this) }
            LockService.start(this)
        } else {
            LockService.unmuteNow(this)
        }
        return false   // 同步做完，不用再回调
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}