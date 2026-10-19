package com.mrmoeini.smsforwarder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Periodically collects device measurements and forwards them as one text, either as an SMS
 * to [destNumber] or to an HTTP API ([api]). [metrics] is a bit mask of METRIC_* values.
 */
data class MeasureRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val metrics: Int = METRIC_CPU or METRIC_RAM,
    val pingHosts: String = "",
    val ussdCode: String = "",
    val ussdRegex: String = "",
    val regex: String = "",
    val action: Int = Rule.ACTION_API,
    val destNumber: String = "",
    val destName: String = "",
    val api: ApiConfig = ApiConfig(),
    val intervalMin: Int = 15,
    val dateFrom: Long = 0L,
    val dateTo: Long = 0L
) {

    fun has(metric: Int): Boolean = (metrics and metric) != 0

    fun isConfigured(): Boolean {
        if (metrics == 0) return false
        return if (action == Rule.ACTION_API) api.url.isNotBlank() else destNumber.isNotBlank()
    }

    fun activeAt(nowMs: Long): Boolean {
        if (!enabled) return false
        if (dateFrom > 0L && nowMs < dateFrom) return false
        if (dateTo > 0L && nowMs > dateTo) return false
        return true
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("metrics", metrics)
        put("pingHosts", pingHosts)
        put("ussdCode", ussdCode)
        put("ussdRegex", ussdRegex)
        put("regex", regex)
        put("action", action)
        put("destNumber", destNumber)
        put("destName", destName)
        put("api", api.toJson())
        put("intervalMin", intervalMin)
        put("dateFrom", dateFrom)
        put("dateTo", dateTo)
    }

    companion object {
        const val METRIC_CPU = 1
        const val METRIC_RAM = 2
        const val METRIC_DISK = 4
        const val METRIC_IP = 8
        const val METRIC_INTERNET = 16
        const val METRIC_PING = 32
        const val METRIC_USSD = 64

        fun fromJson(o: JSONObject): MeasureRule = MeasureRule(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            metrics = o.optInt("metrics", METRIC_CPU or METRIC_RAM),
            pingHosts = o.optString("pingHosts"),
            ussdCode = o.optString("ussdCode"),
            ussdRegex = o.optString("ussdRegex"),
            regex = o.optString("regex"),
            action = o.optInt("action", Rule.ACTION_API),
            destNumber = o.optString("destNumber"),
            destName = o.optString("destName"),
            api = o.optJSONObject("api")?.let { ApiConfig.fromJson(it) } ?: ApiConfig(),
            intervalMin = o.optInt("intervalMin", 15).coerceAtLeast(1),
            dateFrom = o.optLong("dateFrom", 0L),
            dateTo = o.optLong("dateTo", 0L)
        )
    }
}

object MeasureStore {
    private const val KEY = "measure_rules"

    @Synchronized
    fun load(c: Context): MutableList<MeasureRule> {
        val list = mutableListOf<MeasureRule>()
        try {
            val arr = JSONArray(RuleStore.prefs(c).getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) list.add(MeasureRule.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {
        }
        return list
    }

    @Synchronized
    fun save(c: Context, rules: List<MeasureRule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        RuleStore.prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    fun lastRun(c: Context, id: String): Long = RuleStore.prefs(c).getLong("measure_last_$id", 0L)

    fun setLastRun(c: Context, id: String, ms: Long) {
        RuleStore.prefs(c).edit().putLong("measure_last_$id", ms).apply()
    }
}
