package com.mrmoeini.smsforwarder

import android.content.Context
import java.security.SecureRandom

/** Settings for the Mobile Agent (port, auth token, on/off flags). */
object AgentStore {
    private const val KEY_ENABLED = "agent_enabled"
    private const val KEY_PORT = "agent_port"
    private const val KEY_WS_PORT = "agent_ws_port"
    private const val KEY_TOKEN = "agent_token"
    private const val KEY_OCR = "agent_ocr_enabled"
    private const val KEY_SERVICE_RUNNING = "agent_service_running"

    fun isEnabled(c: Context): Boolean = RuleStore.prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = RuleStore.prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()

    fun port(c: Context): Int = RuleStore.prefs(c).getInt(KEY_PORT, 8081)
    fun setPort(c: Context, p: Int) = RuleStore.prefs(c).edit().putInt(KEY_PORT, p).apply()

    fun wsPort(c: Context): Int = RuleStore.prefs(c).getInt(KEY_WS_PORT, 8082)
    fun setWsPort(c: Context, p: Int) = RuleStore.prefs(c).edit().putInt(KEY_WS_PORT, p).apply()

    fun ocrEnabled(c: Context): Boolean = RuleStore.prefs(c).getBoolean(KEY_OCR, true)
    fun setOcrEnabled(c: Context, on: Boolean) = RuleStore.prefs(c).edit().putBoolean(KEY_OCR, on).apply()

    fun token(c: Context): String {
        val existing = RuleStore.prefs(c).getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = randomToken()
        RuleStore.prefs(c).edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    fun regenerateToken(c: Context): String {
        val fresh = randomToken()
        RuleStore.prefs(c).edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    /** Whether the Accessibility Service is currently connected (set by the service itself). */
    fun isServiceRunning(c: Context): Boolean =
        AgentAccessibilityService.isRunning() || RuleStore.prefs(c).getBoolean(KEY_SERVICE_RUNNING, false)

    fun setServiceRunning(c: Context, on: Boolean) =
        RuleStore.prefs(c).edit().putBoolean(KEY_SERVICE_RUNNING, on).apply()
}
