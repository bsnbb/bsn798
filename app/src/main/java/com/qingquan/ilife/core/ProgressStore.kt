package com.qingquan.ilife.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 断点续跑 —— 记录今日已完成的账号
 *
 * 结构：{ "2026-11-08": { "both": { "026": {"gain":140,"s1":1315} } } }
 */
object ProgressStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "qingquan_state.json")

    fun load(ctx: Context): JSONObject {
        return try {
            val f = file(ctx)
            if (!f.exists()) JSONObject() else JSONObject(f.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun save(ctx: Context, obj: JSONObject) {
        try {
            file(ctx).writeText(obj.toString())
        } catch (_: Exception) {
        }
    }

    private fun todayKey(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }

    /** 取今天、当前模式下已完成的账号名集合 */
    fun doneNames(ctx: Context, mode: String): MutableSet<String> {
        val all = load(ctx)
        val day = all.optJSONObject(todayKey()) ?: return mutableSetOf()
        val bucket = day.optJSONObject(mode) ?: return mutableSetOf()
        val set = mutableSetOf<String>()
        val it = bucket.keys()
        while (it.hasNext()) set.add(it.next())
        return set
    }

    /** 标记一个账号完成 */
    fun markDone(ctx: Context, mode: String, name: String, gain: Int, s1: Int) {
        val all = load(ctx)
        val day = all.optJSONObject(todayKey()) ?: JSONObject().also { all.put(todayKey(), it) }
        val bucket = day.optJSONObject(mode) ?: JSONObject().also { day.put(mode, it) }
        bucket.put(name, JSONObject().apply {
            put("gain", gain)
            put("s1", s1)
        })
        prune(all)
        save(ctx, all)
    }

    /** 只保留最近 7 天 */
    private fun prune(all: JSONObject) {
        try {
            val keys = mutableListOf<String>()
            val it = all.keys()
            while (it.hasNext()) keys.add(it.next())
            keys.sort()
            if (keys.size > 7) {
                for (i in 0 until keys.size - 7) all.remove(keys[i])
            }
        } catch (_: Exception) {
        }
    }

    /** 清空今日记录 */
    fun clearToday(ctx: Context) {
        val all = load(ctx)
        all.remove(todayKey())
        save(ctx, all)
    }
}
