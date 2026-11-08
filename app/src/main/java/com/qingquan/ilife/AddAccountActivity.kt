package com.qingquan.ilife

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.qingquan.ilife.core.Account
import com.qingquan.ilife.core.AccountStore
import com.qingquan.ilife.core.ApiResult
import com.qingquan.ilife.core.IlifeApi
import com.qingquan.ilife.databinding.ActivityAddAccountBinding

/**
 * 添加账号 —— 支持手动填 / 粘贴 JSON 自动解析
 */
class AddAccountActivity : AppCompatActivity() {

    private lateinit var b: ActivityAddAccountBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAddAccountBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }

        // 自动解析
        b.btnParse.setOnClickListener {
            val text = b.etPaste.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "请先粘贴内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val acc = Account.parseFromText(text)
            if (acc == null) {
                Toast.makeText(this, "解析失败，没找到 appToken", Toast.LENGTH_SHORT).show()
            } else {
                b.etUid.setText(acc.uid)
                b.etToken.setText(acc.appToken)
                if (b.etName.text.isNullOrEmpty()) b.etName.setText(acc.name)
                Toast.makeText(this, "解析成功", Toast.LENGTH_SHORT).show()
            }
        }

        // 验证并添加
        b.btnAdd.setOnClickListener {
            val token = b.etToken.text.toString().trim()
            var uid = b.etUid.text.toString().trim()
            var name = b.etName.text.toString().trim()

            if (token.isEmpty()) {
                Toast.makeText(this, "appToken 必填", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (uid.isEmpty()) {
                Toast.makeText(this, "UID 必填", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (name.isEmpty()) {
                name = if (uid.length >= 6) uid.takeLast(6) else token.takeLast(6)
            }

            b.btnAdd.isEnabled = false
            b.btnAdd.text = "验证中..."

            val finalName = name
            Thread {
                val r = IlifeApi.fetchState(token)
                runOnUiThread {
                    b.btnAdd.isEnabled = true
                    b.btnAdd.text = "验证并添加"

                    when (r) {
                        is ApiResult.Ok -> {
                            val acc = Account(name = finalName, uid = uid, appToken = token)
                            acc.score = r.state.score
                            acc.weekSigned = r.state.reword
                            acc.status = "已添加"

                            if (AccountStore.add(this, acc)) {
                                Toast.makeText(
                                    this,
                                    "添加成功 · ${finalName} · 积分 ${r.state.score}",
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            } else {
                                Toast.makeText(this, "该账号已存在", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is ApiResult.TokenInvalid -> Toast.makeText(
                            this, "token 无效或已过期", Toast.LENGTH_LONG
                        ).show()
                        is ApiResult.RateLimited -> Toast.makeText(
                            this, "接口限流，稍后再试", Toast.LENGTH_LONG
                        ).show()
                        is ApiResult.Failed -> Toast.makeText(
                            this, "验证失败：${r.msg}", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }
    }
}
