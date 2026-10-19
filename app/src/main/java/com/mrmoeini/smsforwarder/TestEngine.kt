package com.mrmoeini.smsforwarder

import android.os.SystemClock
import org.json.JSONObject

/** "Test Engine": assertions and wait/retry against the live UI tree. */
object TestEngine {

    fun assertCondition(selector: Selector, kind: String, expected: String?): JSONObject {
        val svc = AgentAccessibilityService.instance
            ?: return JSONObject().put("ok", false).put("error", "Accessibility service is not running")
        val root = svc.dumpUi() ?: return JSONObject().put("ok", false).put("error", "No UI content available")
        val node = selector.find(root)
        val pass = when (kind) {
            "exists" -> node != null
            "not_exists" -> node == null
            "text_equals" -> node != null && node.text == expected
            "text_contains" -> node != null && node.text.contains(expected ?: "", ignoreCase = true)
            "enabled" -> node != null && node.enabled
            "checked" -> node != null && node.checked
            else -> false
        }
        return JSONObject().put("ok", pass).put("found", node != null)
            .also { if (node != null) it.put("node", node.toJson()) }
    }

    /** Polls [block] until it returns true, or [timeoutMs] elapses (checking every [intervalMs]). */
    fun waitUntil(timeoutMs: Long, intervalMs: Long, block: () -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var attempts = 0
        while (true) {
            attempts++
            if (block()) return JSONObject().put("ok", true).put("attempts", attempts)
            if (SystemClock.elapsedRealtime() >= deadline) {
                return JSONObject().put("ok", false).put("attempts", attempts).put("error", "timeout")
            }
            try {
                Thread.sleep(intervalMs.coerceAtLeast(100))
            } catch (_: InterruptedException) {
                return JSONObject().put("ok", false).put("error", "interrupted")
            }
        }
    }

    fun waitForSelector(selector: Selector, timeoutMs: Long, intervalMs: Long): JSONObject =
        waitUntil(timeoutMs, intervalMs) {
            val root = AgentAccessibilityService.instance?.dumpUi()
            root != null && selector.find(root) != null
        }
}
