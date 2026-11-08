package com.qingquan.ilife.core

import org.json.JSONObject

/**
 * 账号模型
 */
data class Account(
    val name: String,        // 备注名（默认用 uid 后 6 位）
    val uid: String,
    val appToken: String,
    // 以下为运行时状态，不持久化
    var score: Int = 0,
    var totalScore: Int = 0,
    var weekSigned: Int = 0,
    var todayGain: Int = 0,
    var missionDone: Int = 0,
    var missionTotal: Int = 0,
    var status: String = "未运行",
    var lastError: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("uid", uid)
        put("appToken", appToken)
        // 持久化状态，重启 App 后仍能看到上次结果
        put("score", score)
        put("todayGain", todayGain)
        put("missionDone", missionDone)
        put("missionTotal", missionTotal)
        put("status", status)
    }

    companion object {
        fun fromJson(o: JSONObject): Account = Account(
            name = o.optString("name", ""),
            uid = o.optString("uid", ""),
            appToken = o.optString("appToken", "")
        ).apply {
            score = o.optInt("score", 0)
            todayGain = o.optInt("todayGain", 0)
            missionDone = o.optInt("missionDone", 0)
            missionTotal = o.optInt("missionTotal", 0)
            status = o.optString("status", "未运行")
        }

        /** 从一段粘贴的文本里尽量解析出 uid / appToken */
        fun parseFromText(text: String): Account? {
            var uid = ""
            var token = ""
            var name = ""
            val t = text.trim()

            // 先试标准 JSON
            try {
                val o = JSONObject(t)
                uid = o.optString("uid", "")
                token = o.optString("appToken", o.optString("app_token", ""))
                name = o.optString("name", "")
            } catch (_: Exception) {
                // 忽略，走正则
            }

            if (uid.isEmpty()) {
                val m = Regex("\"uid\"\\s*:\\s*\"([^\"]+)\"").find(t)
                if (m != null) uid = m.groupValues[1]
            }
            if (token.isEmpty()) {
                val m = Regex("\"appToken\"\\s*:\\s*\"([^\"]+)\"").find(t)
                if (m != null) token = m.groupValues[1]
            }

            if (token.isEmpty()) return null

            // 如果 uid 为空，尝试用 token 长度校验
            if (uid.isEmpty()) uid = "unknown"

            if (name.isEmpty()) {
                name = if (uid.length >= 6) uid.takeLast(6) else token.takeLast(6)
            }

            return Account(name = name, uid = uid, appToken = token)
        }
    }
}
