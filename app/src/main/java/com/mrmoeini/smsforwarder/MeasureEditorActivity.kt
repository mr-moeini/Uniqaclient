package com.mrmoeini.smsforwarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject
import java.util.UUID

/** iOS-style form to create / edit one measurement rule. */
class MeasureEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RULE = "measure_rule"
    }

    private lateinit var etName: EditText
    private lateinit var swCpu: SwitchMaterial
    private lateinit var swRam: SwitchMaterial
    private lateinit var swDisk: SwitchMaterial
    private lateinit var swIp: SwitchMaterial
    private lateinit var swInternet: SwitchMaterial
    private lateinit var swPing: SwitchMaterial
    private lateinit var swUssd: SwitchMaterial
    private lateinit var pingBox: View
    private lateinit var etPingHosts: EditText
    private lateinit var ussdBox: View
    private lateinit var etUssdCode: EditText
    private lateinit var etUssdRegex: EditText
    private lateinit var etInterval: EditText
    private lateinit var swDates: SwitchMaterial
    private lateinit var dateBox: View
    private lateinit var tvDateFrom: TextView
    private lateinit var tvDateTo: TextView
    private lateinit var etRegex: EditText
    private lateinit var tvAction: TextView
    private lateinit var smsBox: View
    private lateinit var apiBox: View
    private lateinit var etDest: EditText
    private lateinit var api: ApiFormController

    private var existing: MeasureRule? = null
    private var action = Rule.ACTION_API
    private var dateFrom = 0L
    private var dateTo = 0L
    private var pickedDestName = ""
    private var pickedDestNumber = ""
    private var contactCallback: ((String, String) -> Unit)? = null

    private val contactLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri: Uri? = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) {
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                contentResolver.query(uri, projection, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        contactCallback?.invoke(
                            Rule.toAsciiNumber(c.getString(0) ?: ""), c.getString(1) ?: ""
                        )
                    }
                }
            }
        }

    private val phonePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure_editor)

        etName = findViewById(R.id.etName)
        swCpu = findViewById(R.id.swCpu)
        swRam = findViewById(R.id.swRam)
        swDisk = findViewById(R.id.swDisk)
        swIp = findViewById(R.id.swIp)
        swInternet = findViewById(R.id.swInternet)
        swPing = findViewById(R.id.swPing)
        swUssd = findViewById(R.id.swUssd)
        pingBox = findViewById(R.id.pingBox)
        etPingHosts = findViewById(R.id.etPingHosts)
        ussdBox = findViewById(R.id.ussdBox)
        etUssdCode = findViewById(R.id.etUssdCode)
        etUssdRegex = findViewById(R.id.etUssdRegex)
        etInterval = findViewById(R.id.etInterval)
        swDates = findViewById(R.id.swDates)
        dateBox = findViewById(R.id.dateBox)
        tvDateFrom = findViewById(R.id.tvDateFrom)
        tvDateTo = findViewById(R.id.tvDateTo)
        etRegex = findViewById(R.id.etRegex)
        tvAction = findViewById(R.id.tvAction)
        smsBox = findViewById(R.id.smsBox)
        apiBox = findViewById(R.id.apiBox)
        etDest = findViewById(R.id.etDest)
        api = ApiFormController(this)

        val json = intent.getStringExtra(EXTRA_RULE)
        if (json != null) {
            try {
                existing = MeasureRule.fromJson(JSONObject(json))
            } catch (_: Exception) {
            }
        }
        findViewById<TextView>(R.id.tvEditorTitle)
            .setText(if (existing == null) R.string.new_rule else R.string.edit_rule)

        val now = System.currentTimeMillis()
        dateFrom = DateUtil.startOfDay(now)
        dateTo = DateUtil.endOfDay(now + 30L * 24 * 3600 * 1000)

        val r = existing
        if (r != null) {
            etName.setText(r.name)
            swCpu.isChecked = r.has(MeasureRule.METRIC_CPU)
            swRam.isChecked = r.has(MeasureRule.METRIC_RAM)
            swDisk.isChecked = r.has(MeasureRule.METRIC_DISK)
            swIp.isChecked = r.has(MeasureRule.METRIC_IP)
            swInternet.isChecked = r.has(MeasureRule.METRIC_INTERNET)
            swPing.isChecked = r.has(MeasureRule.METRIC_PING)
            swUssd.isChecked = r.has(MeasureRule.METRIC_USSD)
            etPingHosts.setText(r.pingHosts)
            etUssdCode.setText(r.ussdCode)
            etUssdRegex.setText(r.ussdRegex)
            etInterval.setText(r.intervalMin.toString())
            etRegex.setText(r.regex)
            etDest.setText(r.destNumber)
            action = r.action
            pickedDestName = r.destName
            pickedDestNumber = r.destNumber
            if (r.dateFrom > 0L || r.dateTo > 0L) {
                swDates.isChecked = true
                if (r.dateFrom > 0L) dateFrom = r.dateFrom
                if (r.dateTo > 0L) dateTo = r.dateTo
            }
        } else {
            swCpu.isChecked = true
            swRam.isChecked = true
            etInterval.setText("15")
        }
        api.bind(r?.api)

        swPing.setOnCheckedChangeListener { _, _ -> refreshUi() }
        swUssd.setOnCheckedChangeListener { _, on ->
            refreshUi()
            if (on && ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                phonePermLauncher.launch(Manifest.permission.CALL_PHONE)
            }
        }
        swDates.setOnCheckedChangeListener { _, _ -> refreshUi() }
        tvDateFrom.setOnClickListener {
            DateUtil.pick(this, dateFrom) { dateFrom = DateUtil.startOfDay(it); refreshUi() }
        }
        tvDateTo.setOnClickListener {
            DateUtil.pick(this, dateTo) { dateTo = DateUtil.endOfDay(it); refreshUi() }
        }

        findViewById<View>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener {
            val rule = buildRule()
            if (rule != null) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RULE, rule.toJson().toString()))
                finish()
            }
        }
        findViewById<Button>(R.id.btnPickDest).setOnClickListener {
            pickContact { number, name ->
                pickedDestNumber = number
                pickedDestName = name
                etDest.setText(number)
            }
        }
        findViewById<Button>(R.id.btnTestRegex).setOnClickListener {
            val p = etRegex.text.toString().trim()
            if (p.isEmpty()) etRegex.error = getString(R.string.regex_hint) else RegexTester.show(this, p)
        }
        findViewById<Button>(R.id.btnPreview).setOnClickListener { preview() }
        findViewById<Button>(R.id.btnSendNow).setOnClickListener { sendNow() }
        tvAction.setOnClickListener {
            val labels = arrayOf(getString(R.string.action_sms), getString(R.string.action_api))
            AlertDialog.Builder(this)
                .setTitle(R.string.sec_action)
                .setSingleChoiceItems(labels, action) { d, which ->
                    action = which
                    refreshUi()
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        refreshUi()
    }

    private fun pickContact(callback: (String, String) -> Unit) {
        contactCallback = callback
        try {
            contactLauncher.launch(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.err_no_contacts, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshUi() {
        pingBox.visibility = if (swPing.isChecked) View.VISIBLE else View.GONE
        ussdBox.visibility = if (swUssd.isChecked) View.VISIBLE else View.GONE
        dateBox.visibility = if (swDates.isChecked) View.VISIBLE else View.GONE
        tvDateFrom.text = getString(R.string.date_from, DateUtil.format(dateFrom))
        tvDateTo.text = getString(R.string.date_to, DateUtil.format(dateTo))
        tvAction.text = getString(if (action == Rule.ACTION_API) R.string.action_api else R.string.action_sms)
        smsBox.visibility = if (action == Rule.ACTION_SMS) View.VISIBLE else View.GONE
        apiBox.visibility = if (action == Rule.ACTION_API) View.VISIBLE else View.GONE
    }

    private fun metricsMask(): Int {
        var m = 0
        if (swCpu.isChecked) m = m or MeasureRule.METRIC_CPU
        if (swRam.isChecked) m = m or MeasureRule.METRIC_RAM
        if (swDisk.isChecked) m = m or MeasureRule.METRIC_DISK
        if (swIp.isChecked) m = m or MeasureRule.METRIC_IP
        if (swInternet.isChecked) m = m or MeasureRule.METRIC_INTERNET
        if (swPing.isChecked) m = m or MeasureRule.METRIC_PING
        if (swUssd.isChecked) m = m or MeasureRule.METRIC_USSD
        return m
    }

    /** Builds the rule from the form. With [validate] it shows errors and returns null when invalid. */
    private fun buildRule(validate: Boolean = true): MeasureRule? {
        val metrics = metricsMask()
        val dest = Rule.toAsciiNumber(etDest.text.toString())
        val regex = etRegex.text.toString().trim()
        val ussdRegex = etUssdRegex.text.toString().trim()
        if (validate) {
            if (metrics == 0) {
                Toast.makeText(this, R.string.err_metric, Toast.LENGTH_SHORT).show()
                return null
            }
            for ((fld, p) in listOf(etRegex to regex, etUssdRegex to ussdRegex)) {
                if (p.isNotEmpty()) {
                    try {
                        java.util.regex.Pattern.compile(p)
                    } catch (e: Exception) {
                        fld.error = getString(R.string.err_regex, e.message?.lineSequence()?.firstOrNull() ?: "")
                        return null
                    }
                }
            }
            if (swPing.isChecked && etPingHosts.text.toString().isBlank()) {
                etPingHosts.error = getString(R.string.ping_hosts_hint)
                return null
            }
            if (swUssd.isChecked && etUssdCode.text.toString().isBlank()) {
                etUssdCode.error = getString(R.string.ussd_code_hint)
                return null
            }
            if (swDates.isChecked && dateTo < dateFrom) {
                Toast.makeText(this, R.string.err_dates, Toast.LENGTH_SHORT).show()
                return null
            }
            if (action == Rule.ACTION_SMS && dest.isEmpty()) {
                etDest.error = getString(R.string.err_dest)
                return null
            }
            if (action == Rule.ACTION_API && !api.validate()) return null
        }
        return MeasureRule(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = etName.text.toString().trim(),
            enabled = existing?.enabled ?: true,
            metrics = metrics,
            pingHosts = etPingHosts.text.toString().trim(),
            ussdCode = etUssdCode.text.toString().trim(),
            ussdRegex = ussdRegex,
            regex = regex,
            action = action,
            destNumber = dest,
            destName = if (dest == pickedDestNumber) pickedDestName else "",
            api = api.read(),
            intervalMin = (etInterval.text.toString().toIntOrNull() ?: 15).coerceAtLeast(1),
            dateFrom = if (swDates.isChecked) dateFrom else 0L,
            dateTo = if (swDates.isChecked) dateTo else 0L
        )
    }

    private fun preview() {
        val rule = buildRule(false) ?: return
        if (rule.metrics == 0) {
            Toast.makeText(this, R.string.err_metric, Toast.LENGTH_SHORT).show()
            return
        }
        val app = applicationContext
        Toast.makeText(this, R.string.test_running, Toast.LENGTH_SHORT).show()
        Thread {
            val text = MeasureRunner.content(app, rule, SystemClock.elapsedRealtime() + 20000L)
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(R.string.preview_title)
                    .setMessage(text)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }.start()
    }

    private fun sendNow() {
        val rule = buildRule() ?: return
        val app = applicationContext
        Toast.makeText(this, R.string.test_running, Toast.LENGTH_SHORT).show()
        Thread {
            MeasureRunner.execute(app, rule, SystemClock.elapsedRealtime() + 25000L)
            runOnUiThread { Toast.makeText(this, R.string.sent_now, Toast.LENGTH_LONG).show() }
        }.start()
    }
}
