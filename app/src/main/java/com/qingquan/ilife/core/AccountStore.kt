package com.qingquan.ilife.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 账号存储 —— SharedPreferences，不加密（按要求）
 */
object AccountStore {

    private const val PREF = "qingquan_accounts"
    private const val KEY_LIST = "accounts"

    fun load(ctx: Context): MutableList<Account> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_LIST, "[]") ?: "[]"
        val list = mutableListOf<Account>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                list.add(Account.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun save(ctx: Context, list: List<Account>) {
        val arr = JSONArray()
        for (a in list) arr.put(a.toJson())
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, arr.toString())
            .apply()
    }

    fun add(ctx: Context, acc: Account): Boolean {
        val list = load(ctx)
        if (list.any { it.appToken == acc.appToken }) return false
        list.add(acc)
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, appToken: String) {
        val list = load(ctx)
        list.removeAll { it.appToken == appToken }
        save(ctx, list)
    }

    fun update(ctx: Context, acc: Account) {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.appToken == acc.appToken }
        if (idx >= 0) {
            list[idx] = acc
            save(ctx, list)
        }
    }
}
