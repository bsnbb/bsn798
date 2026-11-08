package com.qingquan.ilife.core

import android.content.Context

object Runner {

    interface Callback {
        fun onLog(msg: String)
        fun onAccountDone(acc: Account, gain: Int)
        fun isStopped(): Boolean
    }

    fun runAccount(ctx: Context, acc: Account, mode: String, cb: Callback): Int {
        val tag = acc.name
        val deadline = System.currentTimeMillis() + Settings.timeoutSec * 1000L

        fun overtime(): Boolean = System.currentTimeMillis() > deadline

        // ---------- 1. 拉状态 ----------
        val r0 = IlifeApi.fetchState(acc.appToken)
        val st: StateInfo
        when (r0) {
            is ApiResult.Ok -> st = r0.state
            is ApiResult.RateLimited -> {
                cb.onLog("$tag  查询限流，跳过")
                acc.status = "限流"
                acc.lastError = "查询限流"
                return 0
            }
            is ApiResult.TokenInvalid -> {
                cb.onLog("$tag  token 失效")
                acc.status = "token失效"
                acc.lastError = "token失效"
                return 0
            }
            is ApiResult.Failed -> {
                cb.onLog("$tag  查询失败：${r0.msg}")
                acc.status = "查询失败"
                acc.lastError = r0.msg
                return 0
            }
            else -> return 0
        }

        val s0 = st.score
        acc.score = st.score
        acc.totalScore = st.totalScore
        acc.weekSigned = st.reword
        acc.missionTotal = st.missions.count { it.score > 0 && it.limit > 0 }

        var signOk = false

        // ---------- 2. 签到 ----------
        if (mode == "sign" || mode == "both") {
            if (!st.signedToday()) {
                cb.onLog("$tag  签到中...")
                when (val code = IlifeApi.signIn(acc.appToken, acc.uid, st)) {
                    0 -> {
                        signOk = true
                        cb.onLog("$tag  签到成功 +${st.dailyScore}")
                    }
                    -98 -> {
                        cb.onLog("$tag  签到限流，等 60s 重试...")
                        if (!sleep(60_000, cb)) return 0
                        if (IlifeApi.signIn(acc.appToken, acc.uid, st) == 0) {
                            signOk = true
                            cb.onLog("$tag  签到成功(重试) +${st.dailyScore}")
                        } else {
                            cb.onLog("$tag  签到限流跳过")
                        }
                    }
                    -99 -> {
                        cb.onLog("$tag  token 失效")
                        acc.status = "token失效"
                        return 0
                    }
                    else -> cb.onLog("$tag  签到失败")
                }
            } else {
                cb.onLog("$tag  今日已签到")
            }
        }

        // ---------- 3. 签到后等满一个间隔 ----------
        if (mode == "both" && signOk && Settings.delaySec > 0) {
            cb.onLog("$tag  签到完成，等待 ${Settings.delaySec.toInt()}s 后开始任务...")
            if (!sleep((Settings.delaySec * 1000).toLong(), cb)) return 0
        }

        // ---------- 4. 任务循环 ----------
        if (mode == "mission" || mode == "both") {
            var cur = st
            var doneCount = 0
            var round = 0
            val maxRound = 10

            while (round < maxRound) {
                if (cb.isStopped() || overtime()) break
                round++

                val todo = mutableListOf<MissionInfo>()
                for (m in cur.missions) {
                    if (m.score <= 0 || m.limit <= 0) continue
                    val done = cur.doneMap[m.refId] ?: 0
                    if (done == -1 || done >= m.limit) continue
                    todo.add(m)
                }
                if (todo.isEmpty()) break

                cb.onLog("$tag  本轮 ${todo.size} 个任务待做")

                for ((i, m) in todo.withIndex()) {
                    if (cb.isStopped() || overtime()) break

                    cb.onLog("$tag  [${i + 1}/${todo.size}] ${m.name} 执行中...")
                    var code = IlifeApi.runMission(acc.appToken, acc.uid, m)

                    if (code == -98) {
                        cb.onLog("$tag  [${i + 1}/${todo.size}] 限流，等 60s 重试...")
                        if (!sleep(60_000, cb)) break
                        code = IlifeApi.runMission(acc.appToken, acc.uid, m)
                        if (code == -98) {
                            cb.onLog("$tag  [${i + 1}/${todo.size}] 仍限流，跳过")
                            continue
                        }
                    }

                    when (code) {
                        0 -> {
                            doneCount++
                            acc.missionDone = doneCount
                            cb.onLog("$tag  [${i + 1}/${todo
