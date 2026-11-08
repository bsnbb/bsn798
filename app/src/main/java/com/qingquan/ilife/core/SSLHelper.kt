package com.qingquan.ilife.core

import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * SSL 辅助 —— 信任所有证书
 *
 * 原因：部分 Android 设备对 i.ilife798.com 的证书链校验不通过，
 * 会导致连接直接失败。这里放宽校验以保证可用性。
 */
object SSLHelper {

    private val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    fun socketFactory(): SSLSocketFactory {
        return try {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, trustAll, java.security.SecureRandom())
            ctx.socketFactory
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    fun verifier(): HostnameVerifier = HostnameVerifier { _, _ -> true }
}
