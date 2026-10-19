package com.mrmoeini.smsforwarder

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores rules and a small forward log in SharedPreferences. */
object RuleStore {
    private const val PREFS = "sms_forwarder"
    private const val KEY_RULES = "rules"
    private const val KEY_LOG = "log"
    private const val MAX_LOG = 50

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(c: Context): MutableList<Rule> {
        val raw = prefs(c).getString(KEY_RULES, "[]") ?: "[]"
        val list = mutableListOf<Rule>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                list.add(Rule.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
            // corrupted data: start with an empty list
        }
        return list
    }

    @Synchronized
    fun save(c: Context, rules: List<Rule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString(KEY_RULES, arr.toString()).apply()
    }

    @Synchronized
    fun addLog(c: Context, text: String) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val lines = readLog(c).toMutableList()
        lines.add(0, "$time  $text")
        while (lines.size > MAX_LOG) lines.removeAt(lines.size - 1)
        val arr = JSONArray()
        lines.forEach { arr.put(it) }
        prefs(c).edit().putString(KEY_LOG, arr.toString()).apply()
    }

    @Synchronized
    fun clearLog(c: Context) {
        prefs(c).edit().remove(KEY_LOG).apply()
    }

    @Synchronized
    fun readLog(c: Context): List<String> {
        val raw = prefs(c).getString(KEY_LOG, "[]") ?: "[]"
        val out = mutableListOf<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (_: Exception) {
        }
        return out
    }
}
