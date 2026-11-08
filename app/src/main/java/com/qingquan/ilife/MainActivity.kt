package com.qingquan.ilife

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.qingquan.ilife.core.Account
import com.qingquan.ilife.core.AccountStore
import com.qingquan.ilife.core.Settings
import com.qingquan.ilife.databinding.ActivityMainBinding

/**
 * 首页 —— 账号列表 + 一键运行
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: AccountAdapter
    private var accounts: MutableList<Account> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        Settings.load(this)

        // 列表
        adapter = AccountAdapter(accounts) { acc ->
            showAccountMenu(acc)
        }
        b.rvAccounts.layoutManager = LinearLayoutManager(this)
        b.rvAccounts.adapter = adapter

        // 按钮
        b.btnRun.setOnClickListener { startRun("both") }
        b.btnSign.setOnClickListener { startRun("sign") }
        b.btnMission.setOnClickListener { startRun("mission") }
        b.btnAdd.setOnClickListener {
            startActivity(Intent(this, AddAccountActivity::class.java))
        }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 服务回调
        TaskService.logListener = { msg -> runOnUiThread { appendLog(msg) } }
        TaskService.progressListener = { runOnUiThread { refreshList() } }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onDestroy() {
        TaskService.logListener = null
        TaskService.progressListener = null
        super.onDestroy()
    }

    private fun refreshList() {
        accounts.clear()
        accounts.addAll(AccountStore.load(this))
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    private fun updateSummary() {
        val total = accounts.size
        var gain = 0
        var ok = 0
        var warn = 0
        for (a in accounts) {
            gain += a.todayGain
            when {
                a.status == "完成" || a.status == "已完成" -> ok++
                a.status.contains("限流") || a.status.contains("失败") -> warn++
            }
        }
        b.tvTotal.text = total.toString()
        b.tvGain.text = "+$gain"
        b.tvOk.text = ok.toString()
        b.tvWarn.text = warn.toString()

        if (total == 0) {
            b.tvEmpty.visibility = android.view.View.VISIBLE
            b.rvAccounts.visibility = android.view.View.GONE
        } else {
            b.tvEmpty.visibility = android.view.View.GONE
            b.rvAccounts.visibility = android.view.View.VISIBLE
        }
    }

    private fun startRun(mode: String) {
        if (accounts.isEmpty()) {
            Toast.makeText(this, "请先添加账号", Toast.LENGTH_SHORT).show()
            return
        }
        if (TaskService.running) {
            Toast.makeText(this, "正在运行中", Toast.LENGTH_SHORT).show()
            return
        }
        b.tvLog.text = ""
        TaskService.start(this, mode)
        Toast.makeText(this, "已启动", Toast.LENGTH_SHORT).show()
    }

    private fun appendLog(msg: String) {
        val old = b.tvLog.text.toString()
        val lines = old.split("\n").filter { it.isNotBlank() }.toMutableList()
        lines.add(msg)
        while (lines.size > 60) lines.removeAt(0)
        b.tvLog.text = lines.joinToString("\n")
        b.tvLog.post {
            try {
                b.svLog.fullScroll(android.view.View.FOCUS_DOWN)
            } catch (_: Exception) {
            }
        }
    }

    private fun showAccountMenu(acc: Account) {
        val items = arrayOf("刷新状态", "删除账号")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(acc.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> refreshOne(acc)
                    1 -> confirmDelete(acc)
                }
            }
            .show()
    }

    private fun refreshOne(acc: Account) {
        Thread {
            val r = com.qingquan.ilife.core.IlifeApi.fetchState(acc.appToken)
            runOnUiThread {
                when (r) {
                    is com.qingquan.ilife.core.ApiResult.Ok -> {
                        acc.score = r.state.score
                        acc.weekSigned = r.state.reword
                        acc.missionTotal = r.state.missions.count { it.score > 0 && it.limit > 0 }
                        acc.missionDone = 0
                        for (m in r.state.missions) {
                            val d = r.state.doneMap[m.refId] ?: 0
                            if (d > 0) acc.missionDone += d
                        }
                        acc.status = "已查询"
                        AccountStore.update(this, acc)
                        refreshList()
                        Toast.makeText(this, "${acc.name} 积分 ${acc.score}", Toast.LENGTH_SHORT).show()
                    }
                    else -> Toast.makeText(this, "查询失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun confirmDelete(acc: Account) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除账号")
            .setMessage("确定删除 ${acc.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                AccountStore.remove(this, acc.appToken)
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
