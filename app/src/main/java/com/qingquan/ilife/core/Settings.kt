package com.qingquan.ilife.core

import android.content.Context
import org.json.JSONObject

/**
 * 应用设置 —— SharedPreferences 存储
 */
object Settings {

    private const val PREF = "qingquan_settings"

    // 运行参数
    var workers: Int = 32
    var delaySec: Double = 30.0
    var gateSec: Double = 1.2
    var timeoutSec: Int = 900

    // 定时任务
    var autoRunEnabled: Boolean = false
    var autoRunHour: Int = 8
    var autoRunMinute: Int = 0

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        workers = sp.getInt("workers", 32)
        delaySec = sp.getFloat("delay", 30.0f).toDouble()
        gateSec = sp.getFloat("gate", 1.2f).toDouble()
        timeoutSec = sp.getInt("timeout", 900)
        autoRunEnabled = sp.getBoolean("auto_enabled", false)
        autoRunHour = sp.getInt("auto_hour", 8)
        autoRunMinute = sp.getInt("auto_minute", 0)

        // 同步到闸门
        RateLimiter.gateSec = gateSec
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            putInt("workers", workers)
            putFloat("delay", delaySec.toFloat())
            putFloat("gate", gateSec.toFloat())
            putInt("timeout", timeoutSec)
            putBoolean("auto_enabled", autoRunEnabled)
            putInt("auto_hour", autoRunHour)
            putInt("auto_minute", autoRunMinute)
        }.apply()

        RateLimiter.gateSec = gateSec
    }
}
