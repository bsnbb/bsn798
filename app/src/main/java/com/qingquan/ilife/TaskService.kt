package com.qingquan.ilife

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.qingquan.ilife.core.Account
import com.qingquan.ilife.core.AccountStore
import com.qingquan.ilife.core.ProgressStore
import com.qingquan.ilife.core.Runner
import com.qingquan.ilife.core.Settings
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 前台服务 —— 保证后台不被系统杀掉
 *
 * 这是解决 signal 9 的核心：前台服务会在通知栏常驻，
 * Android 不会轻易回收它。
 */
class TaskService : Service() {

    companion object {
        const val CHANNEL_ID = "qingquan_task"
        const val NOTIFY_ID = 1001

        const val ACTION_START = "com.qingquan.ilife.START"
        const val ACTION_STOP = "com.qingquan.ilife.STOP"
        const val EXTRA_MODE = "mode"   // sign / mission / both

        // 供界面观察的全局状态
        @Volatile var running = false
        @Volatile var totalAccounts = 0
        val doneCount = AtomicInteger(0)
        @Volatile var totalGain = 0
        @Volatile var lastLog = ""

        // 界面回调（MainActivity 注册）
        @Volatile var logListener: ((String) -> Unit)? = null
        @Volatile var progressListener: (() -> Unit)? = null

        fun start(ctx: Context, mode: String) {
            val i = Intent(ctx, TaskService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TaskService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private val stopFlag = AtomicBoolean(false)
    private val pool = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFlag.set(true)
                pushLog("⏹ 收到停止指令")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "both"
                startForeground(NOTIFY_ID, buildNotification("准备中...", 0, 0))
                if (!running) {
                    running = true
                    stopFlag.set(false)
                    doneCount.set(0)
                    totalGain = 0
                    pool.execute { runAll(mode) }
                }
            }
        }
        return START_STICKY
    }

    private fun runAll(mode: String) {
        Settings.load(this)
        val all = AccountStore.load(this)
        totalAccounts = all.size
        pushLog("开始执行 · ${all.size} 个账号 · 模式 ${modeLabel(mode)}")

        // 断点续跑：跳过今日已完成的
        val doneNames = ProgressStore.doneNames(this, mode)
        val todo = all.filter { it.name !in doneNames }
        val skipped = all.size - todo.size
        if (skipped > 0) pushLog("⟳ 断点续跑：跳过已完成 $skipped 个，剩余 ${todo.size} 个")

        if (todo.isEmpty()) {
            pushLog("✓ 今日所有账号都已完成")
            finishRun()
            return
        }

        // 并发执行
        val workers = Settings.workers.coerceAtLeast(1)
        val chunks = todo.chunked(workers.coerceAtMost(todo.size))
        for (chunk in chunks) {
            if (stopFlag.get()) break
            val threads = chunk.map { acc ->
                Thread {
                    runOne(acc, mode)
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }

        finishRun()
    }

    private fun runOne(acc: Account, mode: String) {
        if (stopFlag.get()) return
        try {
            val gain = Runner.runAccount(this, acc, mode, object : Runner.Callback {
                override fun onLog(msg: String) {
                    pushLog(msg)
                }

                override fun onAccountDone(a: Account, g: Int) {
                    totalGain += g
                    doneCount.incrementAndGet()
                    // 只有真正完成的才存断点（gain>=0 且没错误）
                    if (a.status == "完成" || a.status == "已完成") {
                        ProgressStore.markDone(this@TaskService, mode, a.name, g, a.score)
                    }
                    // 回写账号状态
                    val list = AccountStore.load(this@TaskService)
                    val idx = list.indexOfFirst { it.appToken == a.appToken }
                    if (idx >= 0) {
                        list[idx].score = a.score
                        list[idx].todayGain = a.todayGain
                        list[idx].status = a.status
                        list[idx].missionDone = a.missionDone
                        AccountStore.save(this@TaskService, list)
                    }
                    updateNotification()
                    progressListener?.invoke()
                }

                override fun isStopped(): Boolean = stopFlag.get()
            })
        } catch (e: Exception) {
            pushLog("${acc.name} 异常：${e.message}")
        }
    }

    private fun finishRun() {
        running = false
        pushLog("===== 完成 · 总积分 +$totalGain =====")
        updateNotification()
        progressListener?.invoke()

        // 结束后停止前台
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun pushLog(msg: String) {
        lastLog = msg
        logListener?.invoke(msg)
    }

    private fun modeLabel(mode: String) = when (mode) {
        "sign" -> "签到"
        "mission" -> "任务"
        else -> "签到+任务"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "清泉任务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "签到与任务运行状态"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("清泉 · 运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (total > 0) {
            builder.setProgress(total, done, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification() {
        try {
            val text = "$doneCount/$totalAccounts · 积分 +$totalGain"
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFY_ID, buildNotification(text, doneCount.get(), totalAccounts))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        running = false
        try { pool.shutdownNow() } catch (_: Exception) {}
        super.onDestroy()
    }
}
