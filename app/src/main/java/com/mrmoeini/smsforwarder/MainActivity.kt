package com.mrmoeini.smsforwarder

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** iOS Settings-style home screen. */
class MainActivity : AppCompatActivity() {

    private lateinit var smsValue: TextView
    private lateinit var measureValue: TextView

    private val backupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) runBackup(uri)
        }

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmRestore(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val g1 = findViewById<LinearLayout>(R.id.group1)
        val g2 = findViewById<LinearLayout>(R.id.group2)
        val g3 = findViewById<LinearLayout>(R.id.group3)

        smsValue = addRow(g1, R.drawable.ic_sms, 0xFF34C759.toInt(), R.string.sms_forwarder) {
            startActivity(Intent(this, SmsActivity::class.java))
        }
        measureValue = addRow(g1, R.drawable.ic_measure, 0xFFFF9500.toInt(), R.string.measure_forwarder) {
            startActivity(Intent(this, MeasureActivity::class.java))
        }
        addRow(g1, R.drawable.ic_device, 0xFF007AFF.toInt(), R.string.device_identity) {
            showDeviceDialog()
        }

        addRow(g2, R.drawable.ic_backup, 0xFF5856D6.toInt(), R.string.backup_settings) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            backupLauncher.launch("sms_forwarder_backup_$stamp.json")
        }
        addRow(g2, R.drawable.ic_restore, 0xFF30B0C7.toInt(), R.string.restore_settings) {
            restoreLauncher.launch(arrayOf("*/*"))
        }

        addRow(g3, R.drawable.ic_info, 0xFF8E8E93.toInt(), R.string.about) {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateValues()
        MeasureScheduler.schedule(this)
    }

    private fun updateValues() {
        smsValue.text = getString(R.string.count_rules, RuleStore.load(this).size)
        measureValue.text = getString(R.string.count_rules, MeasureStore.load(this).size)
    }

    private fun showDeviceDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_device, null)
        val etName = view.findViewById<EditText>(R.id.etDeviceName)
        val etUdid = view.findViewById<EditText>(R.id.etUdid)
        etName.setText(DeviceInfo.overrideName(this))
        etName.hint = DeviceInfo.defaultName(this)
        etUdid.setText(DeviceInfo.overrideUdid(this))
        etUdid.hint = DeviceInfo.defaultUdid(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.device_identity)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                DeviceInfo.setOverrides(this, etName.text.toString(), etUdid.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addRow(
        container: LinearLayout, icon: Int, color: Int, title: Int, onClick: () -> Unit
    ): TextView {
        val density = resources.displayMetrics.density
        if (container.childCount > 0) {
            val sep = View(this)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            lp.marginStart = (60 * density).toInt()
            sep.layoutParams = lp
            sep.setBackgroundColor(ContextCompat.getColor(this, R.color.ios_separator))
            container.addView(sep)
        }
        val row = layoutInflater.inflate(R.layout.row_home, container, false)
        val iv = row.findViewById<ImageView>(R.id.ivIcon)
        iv.setImageResource(icon)
        val bg = GradientDrawable()
        bg.cornerRadius = 7 * density
        bg.setColor(color)
        iv.background = bg
        row.findViewById<TextView>(R.id.tvRowTitle).setText(title)
        row.setOnClickListener { onClick() }
        container.addView(row)
        return row.findViewById(R.id.tvRowValue)
    }

    private fun runBackup(uri: Uri) {
        Thread {
            val err: String? = try {
                BackupManager.export(this, uri)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                val msg = if (err == null) getString(R.string.backup_done)
                else getString(R.string.backup_failed, err)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun confirmRestore(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_title)
            .setMessage(R.string.restore_msg)
            .setPositiveButton(R.string.restore_btn) { _, _ -> runRestore(uri) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runRestore(uri: Uri) {
        Thread {
            val err: String? = try {
                BackupManager.import(this, uri)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                if (err == null) {
                    updateValues()
                    Toast.makeText(this, R.string.restore_done, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.restore_failed, err), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
