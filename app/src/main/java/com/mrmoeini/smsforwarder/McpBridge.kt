package com.mrmoeini.smsforwarder

import android.content.Context
import org.json.JSONObject

/**
 * Simplified "MCP Bridge": a single HTTP endpoint (/mcp/call) that lists tools (/mcp/tools) and
 * invokes one by name with JSON args, so any MCP-capable client can wrap it with a thin adapter.
 * This is not a full MCP-spec transport (stdio/SSE) - it is a pragmatic HTTP JSON-RPC-style bridge
 * to the same AgentController used by the plain REST endpoints.
 */
object McpBridge {

    fun call(context: Context, req: JSONObject): JSONObject {
        val tool = req.optString("tool")
        val args = req.optJSONObject("args") ?: JSONObject()
        val c = AgentController(context)
        fun sel(): Selector? = args.optJSONObject("selector")?.let { Selector.fromJson(it) }

        return try {
            when (tool) {
                "status" -> c.status()
                "ui_dump" -> c.uiDump()
                "tap" -> c.tap(args.optInt("x"), args.optInt("y"))
                "long_click" -> c.longClick(args.optInt("x"), args.optInt("y"))
                "swipe" -> c.swipe(
                    args.optInt("x1"), args.optInt("y1"), args.optInt("x2"), args.optInt("y2"),
                    args.optLong("durationMs", 300)
                )
                "click" -> sel()?.let { c.clickBySelector(it) }
                    ?: JSONObject().put("ok", false).put("error", "selector required")
                "type" -> c.type(sel(), args.optString("text"))
                "scroll" -> c.scroll(sel(), args.optBoolean("forward", true), args.optBoolean("screenFallback", true))
                "key" -> c.key(args.optString("name"))
                "screenshot" -> c.screenshot(true, args.optString("label"))
                "ocr" -> c.ocr()
                "find_text" -> c.findText(args.optString("query"))
                "launch_app" -> c.launchApp(args.optString("package"), args.optString("activity").ifBlank { null })
                "stop_app" -> c.stopApp(args.optString("package"))
                "assert" -> sel()?.let { c.assertCondition(it, args.optString("kind", "exists"), args.optString("expected")) }
                    ?: JSONObject().put("ok", false).put("error", "selector required")
                "wait_for" -> sel()?.let { c.waitForSelector(it, args.optLong("timeoutMs", 10000), args.optLong("intervalMs", 500)) }
                    ?: JSONObject().put("ok", false).put("error", "selector required")
                "device_metrics" -> c.deviceMetrics()
                else -> JSONObject().put("ok", false).put("error", "Unknown tool: $tool")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.javaClass.simpleName + ": " + e.message)
        }
    }
}
