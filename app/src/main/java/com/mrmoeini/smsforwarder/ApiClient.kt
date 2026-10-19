package com.mrmoeini.smsforwarder

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ApiResult(val success: Boolean, val retryable: Boolean, val message: String)

/** Sends a forwarded SMS to an HTTP API using the options of an [ApiConfig]. */
object ApiClient {

    /**
     * @param deadlineElapsed optional SystemClock.elapsedRealtime() deadline; when little time is
     *        left the call is not attempted (retryable result) so the caller can queue it instead.
     * @param captureBody include a snippet of the response body in the result message (test button).
     */
    fun send(
        context: Context,
        cfg: ApiConfig,
        from: String,
        text: String,
        timestamp: Long,
        deadlineElapsed: Long? = null,
        captureBody: Boolean = false
    ): ApiResult {
        var budgetMs = cfg.timeoutSec.coerceIn(1, 120) * 1000L
        if (deadlineElapsed != null) {
            val remaining = deadlineElapsed - SystemClock.elapsedRealtime()
            if (remaining < 1000L) return ApiResult(false, true, "no time left, queued for retry")
            budgetMs = minOf(budgetMs, remaining)
        }
        return try {
            val client = buildClient(cfg, budgetMs)
            var result = doRequest(context, client, cfg, from, text, timestamp, captureBody, false)
            if (result.code == 401 && cfg.authType == ApiConfig.AUTH_OAUTH2) {
                // Cached token may be stale: fetch a new one and retry once.
                result = doRequest(context, client, cfg, from, text, timestamp, captureBody, true)
            }
            val code = result.code
            when {
                code in 200..299 -> ApiResult(true, false, result.text)
                code == 408 || code == 429 || code >= 500 -> ApiResult(false, true, result.text)
                else -> ApiResult(false, false, result.text)
            }
        } catch (e: IllegalArgumentException) {
            ApiResult(false, false, "invalid request: " + (e.message ?: e.javaClass.simpleName))
        } catch (e: Exception) {
            ApiResult(false, true, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    private class Raw(val code: Int, val text: String)

    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    private fun buildClient(cfg: ApiConfig, budgetMs: Long): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(budgetMs, TimeUnit.MILLISECONDS)
            .readTimeout(budgetMs, TimeUnit.MILLISECONDS)
            .writeTimeout(budgetMs, TimeUnit.MILLISECONDS)
            .callTimeout(budgetMs, TimeUnit.MILLISECONDS)
        if (cfg.insecureSsl) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, trustAll)
            b.hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }

    private fun jsonEscape(s: String): String {
        val q = JSONObject.quote(s)
        return q.substring(1, q.length - 1)
    }

    private fun applyTemplate(
        template: String, name: String, udid: String, sender: String,
        message: String, fullText: String, timestamp: Long
    ): String {
        var t = template
        val map = linkedMapOf(
            "device_name" to name, "udid" to udid, "sender" to sender,
            "message" to message, "text" to fullText, "timestamp" to timestamp.toString()
        )
        for ((k, v) in map) {
            t = t.replace("{{" + k + "_json}}", jsonEscape(v))
            t = t.replace("{{$k}}", v)
        }
        return t
    }

    private fun doRequest(
        context: Context,
        client: OkHttpClient,
        cfg: ApiConfig,
        from: String,
        message: String,
        timestamp: Long,
        captureBody: Boolean,
        forceNewToken: Boolean
    ): Raw {
        val name = DeviceInfo.name(context)
        val udid = DeviceInfo.udid(context)
        val fullText = "Devicename : $name\nUdid: $udid\n$message"

        var httpUrl = cfg.url.trim().toHttpUrl()
        if (cfg.authType == ApiConfig.AUTH_KEY_QUERY && cfg.authName.isNotBlank()) {
            httpUrl = httpUrl.newBuilder().addQueryParameter(cfg.authName.trim(), cfg.authSecret).build()
        }

        val method = cfg.method.uppercase()
        val body: RequestBody? = if (method == "GET") {
            httpUrl = httpUrl.newBuilder()
                .addQueryParameter("devicename", name)
                .addQueryParameter("udid", udid)
                .addQueryParameter("sender", from)
                .addQueryParameter("message", message)
                .addQueryParameter("text", fullText)
                .build()
            null
        } else when (cfg.format) {
            ApiConfig.FORMAT_JSON -> {
                val o = JSONObject()
                o.put("devicename", name)
                o.put("udid", udid)
                o.put("sender", from)
                o.put("message", message)
                o.put("text", fullText)
                o.put("timestamp", timestamp)
                o.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            }
            ApiConfig.FORMAT_FORM -> FormBody.Builder()
                .add("devicename", name).add("udid", udid).add("sender", from)
                .add("message", message).add("text", fullText)
                .add("timestamp", timestamp.toString())
                .build()
            ApiConfig.FORMAT_CUSTOM -> {
                val ct = cfg.contentType.ifBlank { "application/json" }
                applyTemplate(cfg.bodyTemplate, name, udid, from, message, fullText, timestamp)
                    .toRequestBody(ct.toMediaType())
            }
            else -> fullText.toRequestBody("text/plain; charset=utf-8".toMediaType())
        }

        val rb = Request.Builder().url(httpUrl)
        when (cfg.authType) {
            ApiConfig.AUTH_BASIC -> {
                val cred = okhttp3.Credentials.basic(cfg.authUser, cfg.authSecret, Charsets.UTF_8)
                rb.header("Authorization", cred)
            }
            ApiConfig.AUTH_BEARER -> rb.header("Authorization", "Bearer " + cfg.authSecret)
            ApiConfig.AUTH_KEY_HEADER ->
                if (cfg.authName.isNotBlank()) rb.header(cfg.authName.trim(), cfg.authSecret)
            ApiConfig.AUTH_OAUTH2 ->
                rb.header("Authorization", "Bearer " + oauthToken(client, cfg, forceNewToken, context))
        }
        for (line in cfg.headers.lines()) {
            val i = line.indexOf(':')
            if (i > 0) {
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim()
                if (k.isNotEmpty()) rb.header(k, v)
            }
        }
        rb.header("User-Agent", "SMS-Forwarder")
        rb.method(method, body)

        return client.newCall(rb.build()).execute().use { resp ->
            val snippet = if (captureBody) {
                try {
                    (resp.body?.string() ?: "").take(300)
                } catch (_: Exception) {
                    ""
                }
            } else ""
            val text = "HTTP " + resp.code + (if (snippet.isNotBlank()) "\n" + snippet else "")
            Raw(resp.code, text)
        }
    }

    // ------------------------------------------------------------------ OAuth 2.0 client credentials

    private fun tokenKey(cfg: ApiConfig): String =
        "oauth_" + (cfg.tokenUrl + "|" + cfg.authUser + "|" + cfg.scope).hashCode()

    private fun oauthToken(client: OkHttpClient, cfg: ApiConfig, force: Boolean, context: Context): String {
        val prefs = RuleStore.prefs(context)
        val key = tokenKey(cfg)
        if (!force) {
            val cached = prefs.getString(key, null)
            if (cached != null) {
                try {
                    val o = JSONObject(cached)
                    if (o.optLong("expiresAt") > System.currentTimeMillis() + 60_000L) {
                        return o.getString("token")
                    }
                } catch (_: Exception) {
                }
            }
        }
        val form = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", cfg.authUser)
            .add("client_secret", cfg.authSecret)
        if (cfg.scope.isNotBlank()) form.add("scope", cfg.scope.trim())
        val req = Request.Builder().url(cfg.tokenUrl.trim().toHttpUrl()).post(form.build()).build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IllegalStateException("OAuth token request failed: HTTP " + resp.code)
            val o = JSONObject(text)
            val token = o.getString("access_token")
            val expiresIn = o.optLong("expires_in", 3600L)
            prefs.edit().putString(
                key,
                JSONObject().put("token", token)
                    .put("expiresAt", System.currentTimeMillis() + expiresIn * 1000L).toString()
            ).apply()
            token
        }
    }
}
