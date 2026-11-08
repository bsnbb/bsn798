package com.qingquan.ilife.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

data class MissionInfo(
    val refId: String,
    val name: String,
    val score: Int,
    val limit: Int,
    val isVideo: Boolean
)

data class StateInfo(
    val score: Int,
    val totalScore: Int,
    val week: Int,
    val reword: Int,
    val doneMap: MutableMap<String, Int>,
    val missions: MutableList<MissionInfo>,
    val dailyAdId: String,
    val dailyScore: Int
) {
    fun signedToday(): Boolean = doneMap[dailyAdId] == -1
}

sealed class ApiResult {
    data class Ok(val state: StateInfo) : ApiResult()
    data class SendOk(val gained: Int) : ApiResult()
    data class RateLimited(val msg: String) : ApiResult()
    data class TokenInvalid(val msg: String) : ApiResult()
    data class Failed(val msg: String) : ApiResult()
}

object IlifeApi {

    private const val BASE = "https://i.ilife798.com/api/v1/"
    private const val UA = "Android_ilife798_3.1.9"
    private const val APP_TYPE = "1,1"
    private const val VER_CODE = "3.1.9"
    private const val VIDEO_AD = "1705776998"

    @Volatile
    private var serverOffset: Long = 0L

    fun nowServerMs(): Long = System.currentTimeMillis() + serverOffset

    private fun rawRequest(
        path: String,
        method: String = "GET",
        body: String? = null,
        token: String? = null
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(BASE + path)
            val c = url.openConnection() as HttpURLConnection
            conn = c

            if (c is HttpsURLConnection) {
                c.sslSocketFactory = SSLHelper.socketFactory()
                c.hostnameVerifier = SSLHelper.verifier()
            }

            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Content-Type", "application/json")

            if (token != null) {
                c.setRequestProperty("Authorization", token)
                c.setRequestProperty("ApplicationType", APP_TYPE)
                c.setRequestProperty("VersionCode", VER_CODE)
            }

            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = c.responseCode

            try {
                val dateStr = c.getHeaderField("Date")
                if (!dateStr.isNullOrEmpty()) {
                    val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                    val serverMs = fmt.parse(dateStr)?.time ?: 0L
                    if (serverMs > 0) {
                        serverOffset = serverMs - System.currentTimeMillis()
                    }
                }
            } catch (_: Exception) {
            }

            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            Pair(code, text)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "网络异常")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun fetchState(appToken: String): ApiResult {
        val (code, text) = rawRequest("acc/score/mission-lst", token = appToken)
        if (code != 200) return ApiResult.Failed("HTTP $code")
        return try {
            val j = JSONObject(text)
            val c = j.optInt("code", -999)
            when (c) {
                0 -> ApiResult.Ok(parseState(j.getJSONObject("data")))
                -98 -> ApiResult.RateLimited(j.optString("msg", "请求过于频繁"))
                -99 -> ApiResult.TokenInvalid(j.optString("msg", "登录状态已过期"))
                else -> ApiResult.Failed(j.optString("msg", "code=$c"))
            }
        } catch (e: Exception) {
            ApiResult.Failed("解析失败: ${e.message}")
        }
    }

    private fun parseState(data: JSONObject): StateInfo {
        val acc = data.optJSONObject("accScoreRsp") ?: JSONObject()
        val daily = acc.optJSONObject("daily") ?: JSONObject()
        val dRsp = data.optJSONObject("dailyRSP") ?: JSONObject()

        val doneMap = mutableMapOf<String, Int>()
        val limitsArr = acc.optJSONArray("limits") ?: JSONArray()
        for (i in 0 until limitsArr.length()) {
            val o = limitsArr.optJSONObject(i) ?: continue
            val ref = o.optString("refId", "")
            if (ref.isEmpty()) continue
            doneMap[ref] = o.optInt("limit", 0)
        }

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

    fun sendScore(
        adId: String,
        appToken: String,
        uid: String,
        addScore: Int,
        addScoreType: Int
    ): Int {
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
            when (j.optInt("code", -999)) {
                0 -> 0
                -98 -> -98
                -99 -> -99
                else -> -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    fun signIn(appToken: String, uid: String, state: StateInfo): Int {
        return sendScore(state.dailyAdId, appToken, uid, state.dailyScore, 1)
    }

    fun runMission(appToken: String, uid: String, mission: MissionInfo): Int {
        return sendScore(
            mission.refId, appToken, uid, mission.score,
            if (mission.isVideo) 2 else 4
        )
    }
}
