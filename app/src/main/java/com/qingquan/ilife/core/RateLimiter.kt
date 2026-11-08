package com.qingquan.ilife.core

/**
 * 全局请求闸门 —— 所有账号共享
 *
 * 保证任意两次 score-send 请求至少间隔 gateSec 秒。
 * 这个值来自 App 官方日志实测：19 个请求 / 9 分钟 ≈ 1 秒 1 个。
 */
object RateLimiter {

    @Volatile
    var gateSec: Double = 1.2

    private var nextAllowedAt: Long = 0L

    /**
     * 取号排队，返回需要等待的毫秒数
     */
    @Synchronized
    fun reserve(): Long {
        val now = System.currentTimeMillis()
        val allowedAt = if (nextAllowedAt > now) nextAllowedAt else now
        nextAllowedAt = allowedAt + (gateSec * 1000).toLong()
        return allowedAt - now
    }

    @Synchronized
    fun reset() {
        nextAllowedAt = 0L
    }
}
