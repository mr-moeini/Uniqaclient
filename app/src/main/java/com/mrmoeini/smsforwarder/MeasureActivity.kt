package com.mrmoeini.smsforwarder

import android.content.Intent
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

class MeasureActivity : BaseListActivity<MeasureRule>() {

    override val titleRes = R.string.measure_forwarder
    override val requiredPermissions = emptyArray<String>()

    private val editorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val json = result.data?.getStringExtra(MeasureEditorActivity.EXTRA_RULE)
                if (json != null) {
                    try {
                        val rule = MeasureRule.fromJson(JSONObject(json))
                        val i = items.indexOfFirst { it.id == rule.id }
                        if (i >= 0) items[i] = rule else items.add(rule)
                        persist()
                    } catch (_: Exception) {
                    }
                }
            }
        }

    override fun loadItems(): MutableList<MeasureRule> = MeasureStore.load(this)
    override fun saveItems() = MeasureStore.save(this, items)
    override fun idOf(item: MeasureRule) = item.id
    override fun enabledOf(item: MeasureRule) = item.enabled
    override fun withEnabled(item: MeasureRule, enabled: Boolean) = item.copy(enabled = enabled)

    override fun titleOf(item: MeasureRule): String =
        if (item.name.isNotBlank()) item.name else metricNames(item)

    private fun metricNames(item: MeasureRule): String {
        val n = mutableListOf<String>()
        if (item.has(MeasureRule.METRIC_CPU)) n.add(getString(R.string.m_cpu))
        if (item.has(MeasureRule.METRIC_RAM)) n.add(getString(R.string.m_ram))
        if (item.has(MeasureRule.METRIC_DISK)) n.add(getString(R.string.m_disk))
        if (item.has(MeasureRule.METRIC_IP)) n.add(getString(R.string.m_ip))
        if (item.has(MeasureRule.METRIC_INTERNET)) n.add(getString(R.string.m_internet))
        if (item.has(MeasureRule.METRIC_PING)) n.add(getString(R.string.m_ping))
        if (item.has(MeasureRule.METRIC_USSD)) n.add(getString(R.string.m_ussd))
        return n.joinToString(", ")
    }

    override fun detailOf(item: MeasureRule): String {
        val target = if (item.action == Rule.ACTION_API) {
            "API: " + item.api.method + " " + item.api.url
        } else {
            val dest = if (item.destName.isNotBlank()) "${item.destName} (${item.destNumber})" else item.destNumber
            "SMS: $dest"
        }
        var d = metricNames(item) + "\n" + getString(R.string.measure_every, item.intervalMin) + "\n" + target
        if (item.dateFrom > 0L || item.dateTo > 0L) {
            d += "\n" + DateUtil.format(item.dateFrom) + " - " + DateUtil.format(item.dateTo)
        }
        return d
    }

    override fun onItemsChanged() {
        MeasureScheduler.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        MeasureScheduler.schedule(this)
    }

    override fun setUpExtra(container: LinearLayout) {
        val v = layoutInflater.inflate(R.layout.layout_measure_header, container, false)
        container.addView(v)
        v.findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    override fun showEditor(existing: MeasureRule?) {
        val i = Intent(this, MeasureEditorActivity::class.java)
        if (existing != null) i.putExtra(MeasureEditorActivity.EXTRA_RULE, existing.toJson().toString())
        editorLauncher.launch(i)
    }
}
