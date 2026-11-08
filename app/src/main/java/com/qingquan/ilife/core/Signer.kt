package com.qingquan.ilife.core

import java.security.MessageDigest

/**
 * 签名工具 —— 算法来自官方源码逆向验证
 *
 * 签名原文 = adId + 时间桶 + token后8位 + uid后8位 + 盐
 * 时间桶   = 30 * (nowMs / 30000)   即 30 秒粒度
 * 盐       = SIGN_SALT（App 通道 1,1）
 */
object Signer {

    const val SALT = "AbCdEfGhIjKlMnOpQrStUvWxYz12345"

    fun md5(s: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xff))
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 生成任务签名
     * @param adId    任务 ID 或 "DAILY_CHECK_IN"
     * @param token   appToken
     * @param uid     用户 ID
     * @param timeMs  服务器校准后的毫秒时间戳
     */
    fun sign(adId: String, token: String, uid: String, timeMs: Long): String {
        val bucket = 30L * (timeMs / 30000L)
        val tokenTail = if (token.length >= 8) token.substring(token.length - 8) else token
        val uidTail = if (uid.length >= 8) uid.substring(uid.length - 8) else uid
        return md5("$adId$bucket$tokenTail$uidTail$SALT")
    }
}
