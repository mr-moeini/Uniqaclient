package com.mrmoeini.smsforwarder

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central dispatcher used by both the HTTP Command API and the MCP bridge. One method per
 * capability so both transports stay thin.
 */
class AgentController(private val context: Context) {

    private fun svc(): AgentAccessibilityService? = AgentAccessibilityService.instance

    private fun noService(): JSONObject = JSONObject()
        .put("ok", false)
        .put("error", "Accessibility service is not enabled. Turn it on from the Mobile Agent screen.")

    private fun logEvent(type: String, detail: JSONObject) {
        Evidence.log(context, JSONObject().put("type", type).put("detail", detail))
    }

    // ------------------------------------------------------------------ 1. status

    fun status(): JSONObject = JSONObject()
        .put("ok", true)
        .put("appVersion", versionName())
        .put("accessibilityServiceRunning", AgentAccessibilityService.isRunning())
        .put("deviceName", DeviceInfo.name(context))
        .put("udid", DeviceInfo.udid(context))
        .put("foregroundPackage", AppController.currentForegroundPackage() ?: "")
        .put("sdk", Build.VERSION.SDK_INT)
        .put("screenshotSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

    private fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    // ------------------------------------------------------------------ 3. UI discovery

    fun uiDump(): JSONObject {
        val s = svc() ?: return noService()
        val root = s.dumpUi() ?: return JSONObject().put("ok", false).put("error", "No UI content available")
        return JSONObject().put("ok", true).put("tree", root.toJson())
    }

    // ------------------------------------------------------------------ 2. action engine

    fun tap(x: Int, y: Int): JSONObject {
        val s = svc() ?: return noService()
        val ok = s.tap(x, y)
        logEvent("tap", JSONObject().put("x", x).put("y", y).put("ok", ok))
        return JSONObject().put("ok", ok)
    }

    fun longClick(x: Int, y: Int): JSONObject {
        val s = svc() ?: return noService()
        val ok = s.longClick(x, y)
        logEvent("longClick", JSONObject().put("x", x).put("y", y).put("ok", ok))
        return JSONObject().put("ok", ok)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): JSONObject {
        val s = svc() ?: return noService()
        val ok = s.swipe(x1, y1, x2, y2, durationMs)
        logEvent("swipe", JSONObject().put("from", "$x1,$y1").put("to", "$x2,$y2").put("ok", ok))
        return JSONObject().put("ok", ok)
    }

    fun scroll(selector: Selector?, forward: Boolean, screenFallback: Boolean): JSONObject {
        val s = svc() ?: return noService()
        if (selector != null) {
            val ok = s.scrollNode(selector, forward)
            logEvent("scroll", JSONObject().put("ok", ok))
            return JSONObject().put("ok", ok)
        }
        if (screenFallback) {
            val dm = context.resources.displayMetrics
            val cx = dm.widthPixels / 2
            val y1 = if (forward) (dm.heightPixels * 0.8).toInt() else (dm.heightPixels * 0.2).toInt()
            val y2 = if (forward) (dm.heightPixels * 0.2).toInt() else (dm.heightPixels * 0.8).toInt()
            val ok = s.swipe(cx, y1, cx, y2, 300)
            return JSONObject().put("ok", ok)
        }
        return JSONObject().put("ok", false).put("error", "No selector and no screen fallback")
    }

    fun clickBySelector(selector: Selector): JSONObject {
        val s = svc() ?: return noService()
        val ok = s.clickNode(selector)
        logEvent("click", JSONObject().put("selector", true).put("ok", ok))
        return JSONObject().put("ok", ok)
    }

    fun longClickBySelector(selector: Selector): JSONObject {
        val s = svc() ?: return noService()
        val ok = s.longClickNode(selector)
        return JSONObject().put("ok", ok)
    }

    fun type(selector: Selector?, text: String): JSONObject {
        val s = svc() ?: return noService()
        val ok = if (selector != null) s.setTextNode(selector, text) else false
        logEvent("type", JSONObject().put("ok", ok).put("len", text.length))
        if (!ok) return JSONObject().put("ok", false)
            .put("error", "Could not set text (selector not found, or the field does not support ACTION_SET_TEXT)")
        return JSONObject().put("ok", true)
    }

    fun key(name: String): JSONObject {
        val s = svc() ?: return noService()
        val ok = when (name.uppercase()) {
            "BACK" -> s.pressBack()
            "HOME" -> s.pressHome()
            "RECENTS" -> s.pressRecents()
            "NOTIFICATIONS" -> s.pressNotifications()
            "ENTER" -> s.pressEnter()
            else -> return JSONObject().put("ok", false)
                .put("error", "Unsupported key '$name'. Supported: BACK, HOME, RECENTS, NOTIFICATIONS, ENTER.")
        }
        logEvent("key", JSONObject().put("name", name).put("ok", ok))
        return JSONObject().put("ok", ok)
    }

    // ------------------------------------------------------------------ 4. vision engine

    fun screenshot(forEvidence: Boolean, label: String): JSONObject {
        val s = svc() ?: return noService()
        val bmp = s.takeScreenshotSync() ?: return JSONObject().put("ok", false)
            .put("error", "Screenshot failed (needs Android 11+ and the service to be active).")
        val file = Evidence.saveScreenshot(context, bmp, label.ifBlank { "shot" })
        if (forEvidence) logEvent("screenshot", JSONObject().put("file", file.name))
        return JSONObject().put("ok", true).put("file", file.name)
            .put("width", bmp.width).put("height", bmp.height)
    }

    fun ocr(): JSONObject {
        val s = svc() ?: return noService()
        val bmp = s.takeScreenshotSync() ?: return JSONObject().put("ok", false).put("error", "Screenshot failed")
        val lines = OcrEngine.recognize(bmp)
        return JSONObject().put("ok", true).put("lines", lines)
    }

    fun findText(query: String): JSONObject {
        val s = svc() ?: return noService()
        val bmp = s.takeScreenshotSync() ?: return JSONObject().put("ok", false).put("error", "Screenshot failed")
        val p = OcrEngine.findTextCenter(bmp, query)
            ?: return JSONObject().put("ok", false).put("found", false)
        return JSONObject().put("ok", true).put("found", true).put("x", p.first).put("y", p.second)
    }

    // ------------------------------------------------------------------ 5. app controller

    fun launchApp(packageName: String, activity: String?): JSONObject {
        val r = AppController.launch(context, packageName, activity)
        logEvent("launch", JSONObject().put("package", packageName).put("ok", r.optBoolean("ok")))
        return r
    }

    fun stopApp(packageName: String): JSONObject = AppController.stopBestEffort(context, packageName)
    fun clearAppData(packageName: String): JSONObject = AppController.clearDataUnsupported(packageName)
    fun listApps(): JSONObject = JSONObject().put("ok", true).put("apps", AppController.listInstalledPackages(context))
    fun installApk(filePath: String): JSONObject = AppController.installFromFile(context, filePath)

    // ------------------------------------------------------------------ 6. test engine

    fun assertCondition(selector: Selector, kind: String, expected: String?): JSONObject =
        TestEngine.assertCondition(selector, kind, expected)

    fun waitForSelector(selector: Selector, timeoutMs: Long, intervalMs: Long): JSONObject =
        TestEngine.waitForSelector(selector, timeoutMs, intervalMs)

    fun waitIdleMs(ms: Long): JSONObject {
        try {
            Thread.sleep(ms.coerceIn(0, 60000))
        } catch (_: InterruptedException) {
        }
        return JSONObject().put("ok", true)
    }

    // ------------------------------------------------------------------ 7. evidence

    fun evidenceList(): JSONObject = JSONObject().put("ok", true).put("files", Evidence.list(context))

    fun evidenceLog(): JSONObject = JSONObject().put("ok", true).put("log", Evidence.recentLog(context))

    fun evidenceClear(): JSONObject {
        Evidence.clear(context)
        return JSONObject().put("ok", true)
    }

    // ------------------------------------------------------------------ 8. device monitor

    fun deviceMetrics(): JSONObject {
        val rule = MeasureRule(
            metrics = MeasureRule.METRIC_CPU or MeasureRule.METRIC_RAM or MeasureRule.METRIC_DISK or
                MeasureRule.METRIC_IP or MeasureRule.METRIC_INTERNET
        )
        val text = Measurements.collect(context, rule, SystemClock.elapsedRealtime() + 5000L)
        val out = JSONObject()
        for (line in text.lines()) {
            val i = line.indexOf(':')
            if (i > 0) out.put(line.substring(0, i).trim(), line.substring(i + 1).trim())
        }
        return JSONObject().put("ok", true).put("metrics", out)
    }

    // ------------------------------------------------------------------ MCP-style tool list

    fun mcpTools(): JSONArray {
        val a = JSONArray()
        fun tool(name: String, desc: String) = a.put(JSONObject().put("name", name).put("description", desc))
        tool("status", "Agent and device status")
        tool("ui_dump", "Dump the current UI hierarchy")
        tool("tap", "Tap at x,y")
        tool("long_click", "Long-press at x,y")
        tool("swipe", "Swipe from x1,y1 to x2,y2")
        tool("click", "Click the element matching a selector")
        tool("type", "Set text on the element matching a selector")
        tool("scroll", "Scroll forward/backward on a selector or the whole screen")
        tool("key", "Press BACK, HOME, RECENTS, NOTIFICATIONS or ENTER")
        tool("screenshot", "Capture a screenshot")
        tool("ocr", "Recognize text and coordinates on screen")
        tool("find_text", "Find the on-screen coordinates of text via OCR")
        tool("launch_app", "Launch an app by package name")
        tool("stop_app", "Best-effort stop (kill background processes)")
        tool("assert", "Assert a condition about a UI element")
        tool("wait_for", "Wait until a selector appears")
        tool("device_metrics", "cpu / ram / disk / ip / internet")
        return a
    }
}
