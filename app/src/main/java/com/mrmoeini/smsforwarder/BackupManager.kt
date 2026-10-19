package com.mrmoeini.smsforwarder

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Backup = one JSON file with every SMS rule and measurement rule (including API settings) and the device identity. */
object BackupManager {

    private const val APP_ID = "sms_forwarder"

    fun export(context: Context, uri: Uri) {
        val json = JSONObject()
        json.put("app", APP_ID)
        json.put("version", 3)
        json.put("rules", JSONArray().also { a -> RuleStore.load(context).forEach { a.put(it.toJson()) } })
        json.put("measures", JSONArray().also { a -> MeasureStore.load(context).forEach { a.put(it.toJson()) } })
        json.put("deviceNameOverride", DeviceInfo.overrideName(context))
        json.put("udidOverride", DeviceInfo.overrideUdid(context))

        val out = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Cannot write to the chosen file")
        out.use { it.write(json.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    /** Replaces all settings with the backup content. Throws if the file is not a valid backup. */
    fun import(context: Context, uri: Uri) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot read the chosen file")
        val text = input.use { String(it.readBytes(), Charsets.UTF_8) }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid backup file")
        }
        if (json.optString("app") != APP_ID) throw IllegalArgumentException("Not a backup of this app")

        val rules = mutableListOf<Rule>()
        json.optJSONArray("rules")?.let { a ->
            for (i in 0 until a.length()) rules.add(Rule.fromJson(a.getJSONObject(i)))
        }
        val measures = mutableListOf<MeasureRule>()
        json.optJSONArray("measures")?.let { a ->
            for (i in 0 until a.length()) measures.add(MeasureRule.fromJson(a.getJSONObject(i)))
        }
        RuleStore.save(context, rules)
        MeasureStore.save(context, measures)
        MeasureScheduler.schedule(context)
        DeviceInfo.setOverrides(
            context,
            json.optString("deviceNameOverride"),
            json.optString("udidOverride")
        )
    }
}
