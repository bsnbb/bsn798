package com.qingquan.ilife

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qingquan.ilife.core.Settings
import java.util.Calendar

/**
 * 定时任务广播 —— 每天定点自动运行
 */
class AutoRunReceiver : BroadcastReceiver() {

    companion object {
        private const val REQ_CODE = 2001
        const val ACTION_AUTO_RUN = "com.qingquan.ilife.AUTO_RUN"

        /** 安排每日定时（在 MainActivity/SettingsActivity 里调用） */
        fun schedule(ctx: Context) {
            Settings.load(ctx)
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return

            val pi = PendingIntent.getBroadcast(
                ctx, REQ_CODE,
                Intent(ctx, AutoRunReceiver::class.java).setAction(ACTION_AUTO_RUN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // 先取消旧的
            am.cancel(pi)

            if (!Settings.autoRunEnabled) return

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, Settings.autoRunHour)
                set(Calendar.MINUTE, Settings.autoRunMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            try {
                am.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    cal.timeInMillis,
                    AlarmManager.INTERVAL_DAY,
                    pi
                )
            } catch (e: Exception) {
                // 精确闹钟无权限时降级为不精确
                try {
                    am.set(
                        AlarmManager.RTC_WAKEUP,
                        cal.timeInMillis,
                        pi
                    )
                } catch (_: Exception) {}
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_AUTO_RUN -> {
                // 触发自动运行
                TaskService.start(context, "both")
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                // 开机后重新安排定时
                schedule(context)
            }
        }
    }
}
