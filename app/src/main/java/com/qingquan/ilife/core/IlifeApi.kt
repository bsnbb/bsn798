package com.qingquan.ilife.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * 任务信息
 */
data class MissionInfo(
    val refId: String,
    val name: String,
    val score: Int,
    val limit: Int,          // 每日上限（来自 missions[].limit）
    val isVideo: Boolean
)

/**
 * 账号状态快照
 */
data class StateInfo(
    val score: Int,
    val totalScore: Int,
    val week: Int,
    val reword: Int,                        // 本周已签次数
    val doneMap: MutableMap<String, Int>,   // refId -> 已做次数（缺失=0）
    val missions: MutableList<MissionInfo>,
    val dailyAdId: String,
    val dailyScore: Int
) {
    /** 签到是否已完成 */
    fun signedToday(): Boolean = doneMap[dailyAdId] == -1

    /** 计算今日可达积分 */
    fun targetScore(): Int {
        var t = 0
        if (!signedToday()) t += dailyScore
        for (m in missions) {
            if (m.score <= 0 || m.limit <= 0) continue
            val done = doneMap[m.refId] ?: 0
            if (done == -1) continue
            val left = m.limit - done
            if (left > 0) t += m.score * left
        }
        return t
    }

    /** 剩余待做任务数 */
    fun pendingCount(): Int {
        var n = 0
        for (m in missions) {
            if (m.score <= 0 || m.limit <= 0) continue
            val done = doneMap[m.refId] ?: 0
            if (done == -1 || done >= m.limit) continue
            n += (m.limit - done)
        }
        return n
    }
}

/**
 * 请求结果
 */
sealed class ApiResult {
    data class Ok(val state: StateInfo) : ApiResult()
    data class SendOk(val gained: Int) : ApiResult()
    data class RateLimited(val msg: String) : ApiResult()
    data class TokenInvalid(val msg: String) : ApiResult()
    data class Failed(val msg: String) : ApiResult()
}

/**
 * 慧生活798 接口封装
 *
 * 核心要点（全部来自官方源码 + 实测验证）：
 *  - 鉴权头 Authorization 是裸 appToken，不加 Bearer
 *  - ApplicationType = "1,1"，VersionCode = "3.1.9"
 *  - 签名每次必须用实时时间 + 服务器偏移
 *  - 遇 -98 限流立即返回，绝不连发
 */
object IlifeApi {

    private const val BASE = "https://i.ilife798.com/api/v1/"
    private const val UA = "Android_ilife798_3.1.9"
    private const val APP_TYPE = "1,1"
    private const val VER_CODE = "3.1.9"
    private const val VIDEO_AD = "1705776998"

    /** 服务器时间 - 本地时间（毫秒），每次请求后更新 */
    @Volatile
    private var serverOffset: Long = 0L

    fun nowServerMs(): Long = System.currentTimeMillis() + serverOffset

    /**
     * 底层 HTTP 请求
     */
    private fun rawRequest(
        path: String,
        method: String = "GET",
        body: String? = null,
        token: String? = null
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(BASE + path)
            conn = url.openConnection() as HttpURLConnection
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = SSLHelper.socketFactory()
                conn.hostnameVerifier = SSLHelper.verifier()
            }
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/json")
            if (token != null) {
                conn.setRequestProperty("Authorization", token)
                conn.setRequestProperty("ApplicationType", APP_TYPE)
                conn.setRequestProperty("VersionCode", VER_CODE)
            }

            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode

            // 从响应头 Date 校准服务器时间
            try {
                val dateStr = conn.getHeaderField("Date")
                if (!dateStr.isNullOrEmpty()) {
                    val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                    val serverMs = fmt.parse(dateStr)?.time ?: 0L
                    if (serverMs > 0) {
                        serverOffset = serverMs - System.currentTimeMillis()
                    }
                }
            } catch (_: Exception) {
            }

            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            Pair(code, text)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "网络异常")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * 拉取账号状态
     */
    fun fetchState(appToken: String): ApiResult {
        val (code, text) = rawRequest("acc/score/mission-lst", token = appToken)
        if (code != 200) return ApiResult.Failed("HTTP $code")
        return try {
            val j = JSONObject(text)
            val c = j.optInt("code", -999)
            when (c) {
                0 -> {
                    val data = j.getJSONObject("data")
                    ApiResult.Ok(parseState(data))
                }
                -98 -> ApiResult.RateLimited(j.optString("msg", "请求过于频繁"))
                -99 -> ApiResult.TokenInvalid(j.optString("msg", "登录状态已过期"))
                else -> ApiResult.Failed(j.optString("msg", "code=$c"))
            }
        } catch (e: Exception) {
            ApiResult.Failed("解析失败: ${e.message}")
        }
    }

    /**
     * 解析状态数据
     */
    private fun parseState(data: JSONObject): StateInfo {
        val acc = data.optJSONObject("accScoreRsp") ?: JSONObject()
        val daily = acc.optJSONObject("daily") ?: JSONObject()
        val dRsp = data.optJSONObject("dailyRSP") ?: JSONObject()

        // 已做次数：refId -> limit 值（缺失=0，-1=已满）
        val doneMap = mutableMapOf<String, Int>()
        val limitsArr = acc.optJSONArray("limits") ?: JSONArray()
        for (i in 0 until limitsArr.length()) {
            val o = limitsArr.optJSONObject(i) ?: continue
            val ref = o.optString("refId", "")
            if (ref.isEmpty()) continue
            doneMap[ref] = o.optInt("limit", 0)
        }

        // 任务列表
        val missions = mutableListOf<MissionInfo>()
        val mArr = data.optJSONArray("missions") ?: JSONArray()
        for (i in 0 until mArr.length()) {
            val o = mArr.optJSONObject(i) ?: continue
            val ref = o.optString("refId", "")
            if (ref.isEmpty()) continue
            missions.add(
                MissionInfo(
                    refId = ref,
                    name = o.optString("name", ref),
                    score = o.optInt("score", 0),
                    limit = o.optInt("limit", 0),
                    isVideo = ref == VIDEO_AD
                )
            )
        }

        return StateInfo(
            score = acc.optString("score", "0").toIntOrNull() ?: 0,
            totalScore = acc.optString("totalScore", "0").toIntOrNull() ?: 0,
            week = daily.optInt("week", 0),
            reword = daily.optInt("reword", 0),
            doneMap = doneMap,
            missions = missions,
            dailyAdId = dRsp.optString("adId", "DAILY_CHECK_IN"),
            dailyScore = dRsp.optInt("score", 5)
        )
    }

    /**
     * 发送积分请求（签到 / 任务）
     *
     * 严格对齐官方节奏：
     *  1. 先过全局闸门
     *  2. 用实时时间 + 服务器偏移算签名（绝不用旧时间）
     *  3. 只发 1 次；-98/-99 立即返回不重试
     *
     * @return 0=成功  -98=限流  -99=token失效  -1=其他失败
     */
    fun sendScore(
        adId: String,
        appToken: String,
        uid: String,
        addScore: Int,
        addScoreType: Int
    ): Int {
        // 过闸门
        val wait = RateLimiter.reserve()
        if (wait > 0) {
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return -1 }
        }

        val nowMs = nowServerMs()
        val sign = Signer.sign(adId, appToken, uid, nowMs)
        val body = JSONObject().apply {
            put("adId", adId)
            put("type", 101)
            put("addScore", addScore)
            put("addScoreType", addScoreType)
        }.toString()

        val (code, text) = rawRequest(
            "acc/score/score-send?sign=$sign&s=1",
            method = "POST",
            body = body,
            token = appToken
        )
        if (code != 200) return -1

        return try {
            val j = JSONObject(text)
            val c = j.optInt("code", -999)
            when (c) {
                0 -> 0
                -98 -> -98
                -99 -> -99
                else -> -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * 签到
     */
    fun signIn(appToken: String, uid: String, state: StateInfo): Int {
        return sendScore(
            adId = state.dailyAdId,
            appToken = appToken,
            uid = uid,
            addScore = state.dailyScore,
            addScoreType = 1
        )
    }

    /**
     * 执行任务
     */
    fun runMission(appToken: String, uid: String, mission: MissionInfo): Int {
        return sendScore(
            adId = mission.refId,
            appToken = appToken,
            uid = uid,
            addScore = mission.score,
            addScoreType = if (mission.isVideo) 2 else 4
        )
    }
}
