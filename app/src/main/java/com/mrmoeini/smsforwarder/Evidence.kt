package com.mrmoeini.smsforwarder

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Evidence": screenshots and a structured action log, written to app-private storage. */
object Evidence {

    fun dir(context: Context): File {
        val d = File(context.filesDir, "agent_evidence")
        d.mkdirs()
        return d
    }

    fun logFile(context: Context): File = File(dir(context), "actions.log")

    fun saveScreenshot(context: Context, bitmap: Bitmap, label: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val safe = label.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40).ifEmpty { "shot" }
        val file = File(dir(context), "${stamp}_$safe.png")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 92, out) }
        return file
    }

    @Synchronized
    fun log(context: Context, event: JSONObject) {
        event.put("ts", System.currentTimeMillis())
        try {
            logFile(context).appendText(event.toString() + "\n")
        } catch (_: Exception) {
        }
    }

    fun list(context: Context): JSONArray {
        val arr = JSONArray()
        dir(context).listFiles()?.sortedByDescending { it.lastModified() }?.take(200)?.forEach {
            arr.put(JSONObject().put("name", it.name).put("size", it.length()).put("modified", it.lastModified()))
        }
        return arr
    }

    fun file(context: Context, name: String): File? {
        val safe = File(name).name
        val f = File(dir(context), safe)
        return if (f.exists() && f.parentFile == dir(context)) f else null
    }

    fun recentLog(context: Context, maxLines: Int = 200): String {
        val f = logFile(context)
        if (!f.exists()) return ""
        return try {
            f.readLines().takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
