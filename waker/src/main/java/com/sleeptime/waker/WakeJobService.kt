package com.sleeptime.waker

import android.app.job.JobParameters
import android.app.job.JobService

/** 15 分钟一次：叫 SleepTime + 续自己的看门狗 */
class WakeJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Waker.wake(this, "job")
        Waker.armWatchdog(this)
        
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}