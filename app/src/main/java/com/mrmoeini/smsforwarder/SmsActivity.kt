package com.mrmoeini.smsforwarder

import android.Manifest
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

class SmsActivity : BaseListActivity<Rule>() {

    override val titleRes = R.string.sms_forwarder
    override val requiredPermissions =
        arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)

    private val editorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val json = result.data?.getStringExtra(RuleEditorActivity.EXTRA_RULE)
                if (json != null) {
                    try {
                        val rule = Rule.fromJson(JSONObject(json))
                        val i = items.indexOfFirst { it.id == rule.id }
                        if (i >= 0) items[i] = rule else items.add(rule)
                        persist()
                    } catch (_: Exception) {
                    }
                }
            }
        }

    override fun loadItems(): MutableList<Rule> = RuleStore.load(this)
    override fun saveItems() = RuleStore.save(this, items)
    override fun idOf(item: Rule) = item.id
    override fun titleOf(item: Rule) = if (item.name.isNotBlank()) item.name else item.sender
    override fun enabledOf(item: Rule) = item.enabled
    override fun withEnabled(item: Rule, enabled: Boolean) = item.copy(enabled = enabled)

    override fun detailOf(item: Rule): String {
        val from = if (item.senderName.isNotBlank()) "${item.senderName} (${item.sender})" else item.sender
        var detail = if (item.action == Rule.ACTION_API) {
            getString(R.string.rule_detail_api, from, item.api.method + " " + item.api.url)
        } else {
            val dest = if (item.destName.isNotBlank()) "${item.destName} (${item.destNumber})" else item.destNumber
            getString(R.string.rule_detail, from, dest)
        }
        if (item.keyword.isNotBlank()) detail += "\n" + getString(R.string.rule_keyword, item.keyword)
        if (item.regex.isNotBlank()) detail += "\n" + getString(R.string.rule_regex, item.regex)
        return detail
    }

    override fun showEditor(existing: Rule?) {
        val i = Intent(this, RuleEditorActivity::class.java)
        if (existing != null) i.putExtra(RuleEditorActivity.EXTRA_RULE, existing.toJson().toString())
        editorLauncher.launch(i)
    }
}
