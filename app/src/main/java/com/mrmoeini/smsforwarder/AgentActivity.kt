package com.mrmoeini.smsforwarder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import java.net.NetworkInterface

/** Mobile Agent control screen: enable/disable, connection info, quick tests, evidence. */
class AgentActivity : AppCompatActivity() {

    private lateinit var swAgent: SwitchMaterial
    private lateinit var swOcr: SwitchMaterial
    private lateinit var tvServerStatus: TextView
    private lateinit var tvA11yStatus: TextView
    private lateinit var tvHttpUrl: TextView
    private lateinit var tvWsUrl: TextView
    private lateinit var tvToken: TextView
    private lateinit var tvEvidenceCount: TextView
    private lateinit var etPort: EditText
    private lateinit var etWsPort: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        swAgent = findViewById(R.id.swAgent)
        swOcr = findViewById(R.id.swOcr)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvA11yStatus = findViewById(R.id.tvA11yStatus)
        tvHttpUrl = findViewById(R.id.tvHttpUrl)
        tvWsUrl = findViewById(R.id.tvWsUrl)
        tvToken = findViewById(R.id.tvToken)
        tvEvidenceCount = findViewById(R.id.tvEvidenceCount)
        etPort = findViewById(R.id.etPort)
        etWsPort = findViewById(R.id.etWsPort)

        etPort.setText(AgentStore.port(this).toString())
        etWsPort.setText(AgentStore.wsPort(this).toString())
        swOcr.isChecked = AgentStore.ocrEnabled(this)

        swAgent.isChecked = AgentStore.isEnabled(this)
        swAgent.setOnCheckedChangeListener { _, on ->
            AgentStore.setEnabled(this, on)
            if (on) {
                AgentService.start(this)
            } else {
                AgentService.stop(this)
            }
            refresh()
        }
        swOcr.setOnCheckedChangeListener { _, on -> AgentStore.setOcrEnabled(this, on) }

        findViewById<Button>(R.id.btnOpenA11y).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
            }
        }
        findViewById<Button>(R.id.btnCopyToken).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("token", AgentStore.token(this)))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnNewToken).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.agent_new_token)
                .setMessage(R.string.agent_new_token_confirm)
                .setPositiveButton(R.string.agent_new_token) { _, _ ->
                    AgentStore.regenerateToken(this)
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        findViewById<Button>(R.id.btnSavePorts).setOnClickListener {
            val p = etPort.text.toString().toIntOrNull()
            val wp = etWsPort.text.toString().toIntOrNull()
            if (p == null || p !in 1024..65535 || wp == null || wp !in 1024..65535 || p == wp) {
                Toast.makeText(this, R.string.err_port, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AgentStore.setPort(this, p)
            AgentStore.setWsPort(this, wp)
            if (AgentStore.isEnabled(this)) {
                AgentService.stop(this)
                handler.postDelayed({ AgentService.start(this) }, 400)
            }
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            refresh()
        }
        findViewById<Button>(R.id.btnDumpUi).setOnClickListener { dumpUiPreview() }
        findViewById<Button>(R.id.btnShotTest).setOnClickListener { screenshotTest() }
        findViewById<Button>(R.id.btnViewLog).setOnClickListener {
            val text = Evidence.recentLog(this)
            AlertDialog.Builder(this)
                .setTitle(R.string.log)
                .setMessage(if (text.isBlank()) getString(R.string.log_empty) else text)
                .setPositiveButton(R.string.close, null)
                .show()
        }
        findViewById<Button>(R.id.btnClearEvidence).setOnClickListener {
            Evidence.clear(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun localIp(): String {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs != null && ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses.toList()) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress ?: ""
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    private fun refresh() {
        val running = AgentStore.isEnabled(this)
        tvServerStatus.text = getString(
            if (running) R.string.agent_running_on else R.string.agent_stopped, AgentStore.port(this)
        )
        val a11yOn = AgentAccessibilityService.isRunning()
        tvA11yStatus.text = getString(if (a11yOn) R.string.agent_a11y_on else R.string.agent_a11y_off)
        val ip = localIp()
        tvHttpUrl.text = "HTTP:  http://$ip:${AgentStore.port(this)}"
        tvWsUrl.text = "WS:    ws://$ip:${AgentStore.wsPort(this)}?token=<token>"
        tvToken.text = "Token: " + AgentStore.token(this)
        tvEvidenceCount.text = getString(R.string.agent_evidence_count, Evidence.list(this).length())
    }

    private fun dumpUiPreview() {
        if (!AgentAccessibilityService.isRunning()) {
            Toast.makeText(this, R.string.agent_a11y_off, Toast.LENGTH_SHORT).show()
            return
        }
        val json = AgentController(this).uiDump()
        AlertDialog.Builder(this)
            .setTitle(R.string.agent_dump_ui)
            .setMessage(json.toString(2).take(4000))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun screenshotTest() {
        if (!AgentAccessibilityService.isRunning()) {
            Toast.makeText(this, R.string.agent_a11y_off, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.test_running, Toast.LENGTH_SHORT).show()
        Thread {
            val r = AgentController(this).screenshot(false, "manual_test")
            runOnUiThread {
                refresh()
                if (r.optBoolean("ok")) {
                    Toast.makeText(this, getString(R.string.agent_saved_as, r.optString("file")), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, r.optString("error"), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
