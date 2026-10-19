package com.mrmoeini.smsforwarder

import android.content.Context
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 1. Command API (WebSocket part). Clients that connect with ?token=<token> get every agent event
 * (tap, swipe, type, screenshot, ...) pushed live - useful for a test runner that wants a log
 * stream instead of polling /evidence/log. Commands are still sent over the HTTP API.
 */
class AgentWebSocketServer(private val context: Context, port: Int) : WebSocketServer(InetSocketAddress(port)) {

    private val authorized = CopyOnWriteArraySet<WebSocket>()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val token = handshake.resourceDescriptor.substringAfter("token=", "")
            .substringBefore("&")
        if (token == AgentStore.token(context)) {
            authorized.add(conn)
            conn.send(JSONObject().put("type", "hello").put("ok", true).toString())
        } else {
            conn.send(JSONObject().put("type", "hello").put("ok", false).put("error", "bad token").toString())
            conn.close()
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        authorized.remove(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // Command execution stays on the HTTP API; this channel is event broadcast only.
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn != null) authorized.remove(conn)
    }

    override fun onStart() {}

    fun broadcastEvent(json: JSONObject) {
        val text = json.toString()
        for (c in authorized) {
            try {
                c.send(text)
            } catch (_: Exception) {
            }
        }
    }
}
