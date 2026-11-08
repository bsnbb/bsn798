package com.qingquan.ilife

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.qingquan.ilife.core.ProgressStore
import com.qingquan.ilife.core.Settings
import com.qingquan.ilife.databinding.ActivitySettingsBinding

/**
 * 设置页 —— 运行参数 + 定时任务
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        Settings.load(this)
        bindValues()

        b.btnBack.setOnClickListener { finish() }

        b.btnSave.setOnClickListener {
            saveValues()
            Settings.save(this)
            AutoRunReceiver.schedule(this)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        b.btnClearToday.setOnClickListener {
            ProgressStore.clearToday(this)
            Toast.makeText(this, "今日记录已清空", Toast.LENGTH_SHORT).show()
        }

        b.swAutoRun.setOnCheckedChangeListener { _, checked ->
            b.llTime.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun bindValues() {
        b.etWorkers.setText(Settings.workers.toString())
        b.etDelay.setText(Settings.delaySec.toString())
        b.etGate.setText(Settings.gateSec.toString())
        b.etTimeout.setText(Settings.timeoutSec.toString())

        b.swAutoRun.isChecked = Settings.autoRunEnabled
        b.etHour.setText(Settings.autoRunHour.toString())
        b.etMinute.setText(Settings.autoRunMinute.toString())
        b.llTime.visibility = if (Settings.autoRunEnabled) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun saveValues() {
        Settings.workers = b.etWorkers.text.toString().toIntOrNull()?.coerceIn(1, 64) ?: 32
        Settings.delaySec = b.etDelay.text.toString().toDoubleOrNull()?.coerceIn(0.0, 300.0) ?: 30.0
        Settings.gateSec = b.etGate.text.toString().toDoubleOrNull()?.coerceIn(0.0, 60.0) ?: 1.2
        Settings.timeoutSec = b.etTimeout.text.toString().toIntOrNull()?.coerceIn(60, 3600) ?: 900

        Settings.autoRunEnabled = b.swAutoRun.isChecked
        Settings.autoRunHour = (b.etHour.text.toString().toIntOrNull() ?: 8).coerceIn(0, 23)
        Settings.autoRunMinute = (b.etMinute.text.toString().toIntOrNull() ?: 0).coerceIn(0, 59)
    }
}
