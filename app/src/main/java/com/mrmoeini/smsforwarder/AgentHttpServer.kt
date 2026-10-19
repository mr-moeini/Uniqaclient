package com.mrmoeini.smsforwarder

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * 1. Command API (HTTP part). A small, dependency-free HTTP/1.1 server: one JSON endpoint per
 * agent capability. Every request (except GET /status) needs header "X-Agent-Token: <token>".
 */
class AgentHttpServer(private val context: Context, private val port: Int) {

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val controller = AgentController(context)
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        pool.execute {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (running) RuleStore.addLog(context, "AGENT HTTP accept error: ${e.message}")
                        break
                    }
                    pool.execute { handle(client) }
                }
            } catch (e: Exception) {
                RuleStore.addLog(context, "AGENT HTTP server failed to start on port $port: ${e.message}")
                running = false
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 20000
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val fullPath = parts[1]
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                var body = ""
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len > 0) {
                    val buf = CharArray(len)
                    var readTotal = 0
                    while (readTotal < len) {
                        val n = input.read(buf, readTotal, len - readTotal)
                        if (n < 0) break
                        readTotal += n
                    }
                    body = String(buf, 0, readTotal)
                }

                val qIdx = fullPath.indexOf('?')
                val path = if (qIdx >= 0) fullPath.substring(0, qIdx) else fullPath
                val query = if (qIdx >= 0) parseQuery(fullPath.substring(qIdx + 1)) else emptyMap()

                val out = s.getOutputStream()
                val token = AgentStore.token(context)
                val provided = headers["x-agent-token"] ?: query["token"]
                if (path != "/status" && provided != token) {
                    respond(out, 401, JSONObject().put("ok", false).put("error", "missing or wrong X-Agent-Token"))
                    return
                }
                val json = if (body.isNotBlank()) try {
                    JSONObject(body)
                } catch (e: Exception) {
                    null
                } else null

                val result = try {
                    route(method, path, json, query)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.javaClass.simpleName + ": " + e.message)
                }
                respond(out, if (result.optBoolean("ok", true)) 200 else 400, result)
            } catch (_: Exception) {
                // connection reset / malformed request: nothing to do
            }
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (pair in q.split("&")) {
            if (pair.isBlank()) continue
            val i = pair.indexOf('=')
            val k = if (i >= 0) pair.substring(0, i) else pair
            val v = if (i >= 0) pair.substring(i + 1) else ""
            map[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return map
    }

    private fun sel(json: JSONObject?): Selector? {
        val o = json?.optJSONObject("selector") ?: return null
        return Selector.fromJson(o)
    }

    private fun route(method: String, path: String, j: JSONObject?, q: Map<String, String>): JSONObject {
        fun intArg(name: String, default: Int = 0): Int =
            j?.optInt(name) ?: q[name]?.toIntOrNull() ?: default
        fun longArg(name: String, default: Long = 0L): Long =
            j?.optLong(name) ?: q[name]?.toLongOrNull() ?: default
        fun strArg(name: String): String = j?.optString(name) ?: q[name] ?: ""

        return when ("$method $path") {
            "GET /status" -> AgentController(context).status()
            "GET /ui/dump" -> AgentController(context).uiDump()
            "POST /action/tap" -> AgentController(context).tap(intArg("x"), intArg("y"))
            "POST /action/long_click" -> AgentController(context).longClick(intArg("x"), intArg("y"))
            "POST /action/swipe" -> AgentController(context).swipe(
                intArg("x1"), intArg("y1"), intArg("x2"), intArg("y2"), longArg("durationMs", 300)
            )
            "POST /action/click" -> {
                val s = sel(j) ?: return JSONObject().put("ok", false).put("error", "selector required")
                AgentController(context).clickBySelector(s)
            }
            "POST /action/long_click_selector" -> {
                val s = sel(j) ?: return JSONObject().put("ok", false).put("error", "selector required")
                AgentController(context).longClickBySelector(s)
            }
            "POST /action/type" -> AgentController(context).type(sel(j), j?.optString("text") ?: strArg("text"))
            "POST /action/scroll" -> AgentController(context).scroll(
                sel(j), j?.optBoolean("forward", true) ?: true, j?.optBoolean("screenFallback", true) ?: true
            )
            "POST /action/key" -> AgentController(context).key(strArg("name"))
            "POST /vision/screenshot" -> AgentController(context).screenshot(true, strArg("label"))
            "POST /vision/ocr" -> AgentController(context).ocr()
            "POST /vision/find_text" -> AgentController(context).findText(strArg("query"))
            "POST /app/launch" -> AgentController(context).launchApp(strArg("package"), j?.optString("activity"))
            "POST /app/stop" -> AgentController(context).stopApp(strArg("package"))
            "POST /app/clear_data" -> AgentController(context).clearAppData(strArg("package"))
            "POST /app/install" -> AgentController(context).installApk(strArg("path"))
            "GET /app/list" -> AgentController(context).listApps()
            "POST /test/assert" -> {
                val s = sel(j) ?: return JSONObject().put("ok", false).put("error", "selector required")
                AgentController(context).assertCondition(s, strArg("kind").ifBlank { "exists" }, j?.optString("expected"))
            }
            "POST /test/wait_for" -> {
                val s = sel(j) ?: return JSONObject().put("ok", false).put("error", "selector required")
                AgentController(context).waitForSelector(s, longArg("timeoutMs", 10000), longArg("intervalMs", 500))
            }
            "POST /test/wait_idle" -> AgentController(context).waitIdleMs(longArg("ms", 1000))
            "GET /evidence/list" -> AgentController(context).evidenceList()
            "GET /evidence/log" -> AgentController(context).evidenceLog()
            "POST /evidence/clear" -> AgentController(context).evidenceClear()
            "GET /device/metrics" -> AgentController(context).deviceMetrics()
            "GET /mcp/tools" -> JSONObject().put("ok", true).put("tools", AgentController(context).mcpTools())
            "POST /mcp/call" -> McpBridge.call(context, j ?: JSONObject())
            else -> JSONObject().put("ok", false).put("error", "no such endpoint: $method $path")
        }
    }

    private fun respond(out: OutputStream, code: Int, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val statusText = if (code == 200) "OK" else if (code == 401) "Unauthorized" else "Bad Request"
        val header = "HTTP/1.1 $code $statusText\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    /** Serves a screenshot/evidence file by name over a tiny raw GET, used by /evidence/file?name=... */
    fun servesFile(name: String): java.io.File? = Evidence.file(context, name)
}
