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

        val r0 = IlifeApi.fetchState(acc.appToken)
        if (r0 is ApiResult.Ok) {
            val st = r0.state
            val s0 = st.score
            acc.score = st.score
            acc.totalScore = st.totalScore
            acc.weekSigned = st.reword
            acc.missionTotal = st.missions.count { it.score > 0 && it.limit > 0 }

            var signOk = false

            if (mode == "sign" || mode == "both") {
                if (st.signedToday() == false) {
                    cb.onLog(tag + "  签到中...")
                    val code = IlifeApi.signIn(acc.appToken, acc.uid, st)
                    if (code == 0) {
                        signOk = true
                        cb.onLog(tag + "  签到成功")
                    } else if (code == -98) {
                        cb.onLog(tag + "  签到限流，等60s重试")
                        if (sleep(60000L, cb)) {
                            val c2 = IlifeApi.signIn(acc.appToken, acc.uid, st)
                            if (c2 == 0) {
                                signOk = true
                                cb.onLog(tag + "  签到成功(重试)")
                            }
                        }
                    } else if (code == -99) {
                        cb.onLog(tag + "  token失效")
                        acc.status = "token失效"
                        return 0
                    }
                }
            }

            if (mode == "both" && signOk && Settings.delaySec > 0) {
                cb.onLog(tag + "  等待开始任务")
                if (sleep((Settings.delaySec * 1000).toLong(), cb) == false) return 0
            }

            if (mode == "mission" || mode == "both") {
                var cur = st
                var doneCount = 0
                var round = 0

                while (round < 10) {
                    if (cb.isStopped()) break
                    if (System.currentTimeMillis() > deadline) break
                    round++

                    val todo = mutableListOf<MissionInfo>()
                    for (m in cur.missions) {
                        if (m.score <= 0) continue
                        if (m.limit <= 0) continue
                        var d = 0
                        val dv = cur.doneMap[m.refId]
                        if (dv != null) d = dv
                        if (d == -1) continue
                        if (d >= m.limit) continue
                        todo.add(m)
                    }
                    if (todo.isEmpty()) break

                    cb.onLog(tag + "  本轮" + todo.size + "个任务")

                    var idx = 0
                    while (idx < todo.size) {
                        if (cb.isStopped()) break
                        val m = todo[idx]
                        cb.onLog(tag + "  " + (idx + 1) + "/" + todo.size + " " + m.name)

                        var code = IlifeApi.runMission(acc.appToken, acc.uid, m)
                        if (code == -98) {
                            cb.onLog(tag + "  限流，等60s重试")
                            if (sleep(60000L, cb)) {
                                code = IlifeApi.runMission(acc.appToken, acc.uid, m)
                            }
                        }

                        if (code == 0) {
                            doneCount++
                            acc.missionDone = doneCount
                            cb.onLog(tag + "  成功")
                        } else if (code == -99) {
                            cb.onLog(tag + "  token失效")
                            acc.status = "token失效"
                            break
                        }

                        idx++
                        if (Settings.delaySec > 0 && idx < todo.size) {
                            if (sleep((Settings.delaySec * 1000).toLong(), cb) == false) break
                        }
                    }

                    val r2 = IlifeApi.fetchState(acc.appToken)
                    if (r2 is ApiResult.Ok) {
                        if (r2.state.score == s0 && doneCount == 0) break
                        cur = r2.state
                    } else {
                        break
                    }
                }
            }

            val r3 = IlifeApi.fetchState(acc.appToken)
            var s1 = s0
            if (r3 is ApiResult.Ok) s1 = r3.state.score

            val gain = s1 - s0
            acc.score = s1
            acc.todayGain = gain
            if (acc.status == "未运行") {
                if (gain > 0) {
                    acc.status = "完成"
                } else {
                    acc.status = "已完成"
                }
            }

            cb.onAccountDone(acc, gain)
            return gain
        } else {
            if (r0 is ApiResult.RateLimited) {
                cb.onLog(tag + "  查询限流")
                acc.status = "限流"
            } else if (r0 is ApiResult.TokenInvalid) {
                cb.onLog(tag + "  token失效")
                acc.status = "token失效"
            } else {
                cb.onLog(tag + "  查询失败")
                acc.status = "查询失败"
            }
            return 0
        }
    }

    private fun sleep(ms: Long, cb: Callback): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cb.isStopped()) return false
            try {
                Thread.sleep(200L)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return true
    }
}
