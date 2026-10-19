package com.mrmoeini.smsforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Foreground service that keeps the Command API (HTTP + WebSocket) alive while the agent is on. */
class AgentService : Service() {

    private var http: AgentHttpServer? = null
    private var ws: AgentWebSocketServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.agent_title), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, AgentActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val port = AgentStore.port(this)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(getString(R.string.agent_title))
            .setContentText(getString(R.string.agent_running_on, port))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n)

        http = AgentHttpServer(this, AgentStore.port(this)).also { it.start() }
        try {
            ws = AgentWebSocketServer(this, AgentStore.wsPort(this)).also { it.start() }
        } catch (e: Exception) {
            RuleStore.addLog(this, "AGENT WebSocket failed to start: ${e.message}")
        }
        RuleStore.addLog(this, "AGENT: service started (http:${AgentStore.port(this)}, ws:${AgentStore.wsPort(this)})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        http?.stop()
        try {
            ws?.stop()
        } catch (_: Exception) {
        }
        RuleStore.addLog(this, "AGENT: service stopped")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "agent_service"
        private const val NOTIF_ID = 7002

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
