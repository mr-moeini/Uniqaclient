package com.mrmoeini.smsforwarder

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Device name and UDID sent with every API request. The user can override both; otherwise the
 * name is the phone's device name and the UDID is the Android ID (apps cannot read the adb serial
 * that Appium uses as udid on Android 10+, so enter it manually if it must match).
 */
object DeviceInfo {
    private const val KEY_NAME = "device_name_override"
    private const val KEY_UDID = "udid_override"

    fun defaultName(c: Context): String {
        try {
            val n = Settings.Global.getString(c.contentResolver, "device_name")
            if (!n.isNullOrBlank()) return n
        } catch (_: Exception) {
        }
        try {
            val n = Settings.Secure.getString(c.contentResolver, "bluetooth_name")
            if (!n.isNullOrBlank()) return n
        } catch (_: Exception) {
        }
        return (Build.MANUFACTURER + " " + Build.MODEL).trim()
    }

    @SuppressLint("HardwareIds")
    fun defaultUdid(c: Context): String =
        try {
            Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

    fun overrideName(c: Context): String = RuleStore.prefs(c).getString(KEY_NAME, "") ?: ""
    fun overrideUdid(c: Context): String = RuleStore.prefs(c).getString(KEY_UDID, "") ?: ""

    fun name(c: Context): String = overrideName(c).ifBlank { defaultName(c) }
    fun udid(c: Context): String = overrideUdid(c).ifBlank { defaultUdid(c) }

    fun setOverrides(c: Context, name: String, udid: String) {
        RuleStore.prefs(c).edit()
            .putString(KEY_NAME, name.trim())
            .putString(KEY_UDID, udid.trim())
            .apply()
    }
}
