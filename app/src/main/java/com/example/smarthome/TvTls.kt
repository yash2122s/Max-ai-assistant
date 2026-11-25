package com.example.smarthome

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Trust is scoped to the user-selected address and the explicitly accepted leaf certificate. */
internal class TvTls(private val host:String, private val expected:()->String?): X509TrustManager {
    @Volatile var observed:String?=null
        private set
    override fun checkServerTrusted(chain:Array<out X509Certificate>, authType:String) {
        val leaf=chain.firstOrNull() ?: throw CertificateException("Missing TV certificate")
        val digest=MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString(":"){ "%02X".format(it) }
        observed=digest
        if(expected()!=digest) throw CertificateException("Unrecognized TV certificate")
    }
    override fun checkClientTrusted(chain:Array<out X509Certificate>,authType:String){throw CertificateException("Client certificate unsupported")}
    override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
    fun client():OkHttpClient {
        val ssl=SSLContext.getInstance("TLS").apply { init(null,arrayOf(this@TvTls),null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,this)
            .hostnameVerifier { name,_ -> name==host && observed!=null && observed==expected() }
            .followRedirects(false).followSslRedirects(false).build()
    }
}
