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

class TaskService : Service() {

    companion object {
        const val CHANNEL_ID = "qingquan_task"
        const val NOTIFY_ID = 1001
        const val ACTION_START = "com.qingquan.ilife.START"
        const val ACTION_STOP = "com.qingquan.ilife.STOP"
        const val EXTRA_MODE = "mode"

        @Volatile var running = false
        @Volatile var totalAccounts = 0
        val doneCount = AtomicInteger(0)
        val totalGain = AtomicInteger(0)
        @Volatile var lastLog = ""

        @Volatile var logListener: ((String) -> Unit)? = null
        @Volatile var progressListener: (() -> Unit)? = null

        fun start(ctx: Context, mode: String) {
            val i = Intent(ctx, TaskService::class.java)
            i.action = ACTION_START
            i.putExtra(EXTRA_MODE, mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, TaskService::class.java)
            i.action = ACTION_STOP
            ctx.startService(i)
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
        var act = ""
        var mode = "both"
        if (intent != null) {
            act = intent.action
            var m = intent.getStringExtra(EXTRA_MODE)
            if (m != null) mode = m
        }
        if (act == ACTION_STOP) {
            stopFlag.set(true)
            pushLog("收到停止指令")
            return START_NOT_STICKY
        }
        if (act == ACTION_START) {
            startForeground(NOTIFY_ID, buildNotification("准备中", 0, 0))
            if (running == false) {
                running = true
                stopFlag.set(false)
                doneCount.set(0)
                totalGain.set(0)
                pool.execute { runAll(mode) }
            }
        }
        return START_STICKY
    }

    private fun runAll(mode: String) {
        Settings.load(this)
        val all = AccountStore.load(this)
        totalAccounts = all.size
        pushLog("开始执行 " + all.size + " 个账号")

        val doneNames = ProgressStore.doneNames(this, mode)
        val todo = mutableListOf<Account>()
        for (a in all) {
            if (doneNames.contains(a.name) == false) todo.add(a)
        }
        if (todo.isEmpty()) {
            pushLog("今日全部完成")
            finishRun()
            return
        }

        var workers = Settings.workers
        if (workers < 1) workers = 1
        var w = workers
        if (w > todo.size) w = todo.size
        val chunks = todo.chunked(w)
        for (chunk in chunks) {
            if (stopFlag.get()) break
            val threads = mutableListOf<Thread>()
            for (acc in chunk) {
                val t = Thread { runOne(acc, mode) }
                threads.add(t)
            }
            for (t in threads) t.start()
            for (t in threads) t.join()
        }

        finishRun()
    }

    private fun runOne(acc: Account, mode: String) {
        if (stopFlag.get()) return
        try {
            Runner.runAccount(this, acc, mode, object : Runner.Callback {
                override fun onLog(msg: String) {
                    pushLog(msg)
                }

                override fun onAccountDone(a: Account, g: Int) {
                    totalGain.addAndGet(g)
                    doneCount.incrementAndGet()
                    if (a.status == "完成" || a.status == "已完成") {
                        ProgressStore.markDone(this@TaskService, mode, a.name, g, a.score)
                    }
                    val list = AccountStore.load(this@TaskService)
                    var i = 0
                    while (i < list.size) {
                        if (list[i].appToken == a.appToken) {
                            list[i].score = a.score
                            list[i].todayGain = a.todayGain
                            list[i].status = a.status
                            list[i].missionDone = a.missionDone
                        }
                        i++
                    }
                    AccountStore.save(this@TaskService, list)
                    updateNotification()
                    val pl = progressListener
                    if (pl != null) pl.invoke()
                }

                override fun isStopped(): Boolean = stopFlag.get()
            })
        } catch (e: Exception) {
            pushLog(acc.name + " 异常 " + e.message)
        }
    }

    private fun finishRun() {
        running = false
        pushLog("完成 总积分 " + totalGain.get())
        updateNotification()
        val pl = progressListener
        if (pl != null) pl.invoke()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                stopForeground(false)
            }
        } catch (e: Exception) {
        }
        stopSelf()
    }

    private fun pushLog(msg: String) {
        lastLog = msg
        val ll = logListener
        if (ll != null) ll.invoke(msg)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "清泉任务", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        var builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = Notification.Builder(this, CHANNEL_ID)
        } else {
            builder = Notification.Builder(this)
        }

        builder.setContentTitle("清泉 运行中")
        builder.setContentText(text)
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
        builder.setContentIntent(open)
        builder.setOngoing(true)
        builder.setOnlyAlertOnce(true)

        if (total > 0) {
            builder.setProgress(total, done, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification() {
        try {
            val text = doneCount.get().toString() + "/" + totalAccounts + " 积分 " + totalGain.get()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFY_ID, buildNotification(text, doneCount.get(), totalAccounts))
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        running = false
        try {
            pool.shutdownNow()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
