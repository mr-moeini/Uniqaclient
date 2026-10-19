package com.mrmoeini.smsforwarder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.UUID

/** iOS-style form to create / edit one SMS forwarding rule. */
class RuleEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RULE = "rule"
    }

    private lateinit var etName: EditText
    private lateinit var etSender: EditText
    private lateinit var etKeyword: EditText
    private lateinit var etRegex: EditText
    private lateinit var tvAction: TextView
    private lateinit var smsBox: View
    private lateinit var apiBox: View
    private lateinit var etDest: EditText
    private lateinit var api: ApiFormController

    private var existing: Rule? = null
    private var action = Rule.ACTION_SMS
    private var pickedSenderName = ""
    private var pickedSenderNumber = ""
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_editor)

        etName = findViewById(R.id.etName)
        etSender = findViewById(R.id.etSender)
        etKeyword = findViewById(R.id.etKeyword)
        etRegex = findViewById(R.id.etRegex)
        tvAction = findViewById(R.id.tvAction)
        smsBox = findViewById(R.id.smsBox)
        apiBox = findViewById(R.id.apiBox)
        etDest = findViewById(R.id.etDest)
        api = ApiFormController(this)

        val json = intent.getStringExtra(EXTRA_RULE)
        if (json != null) {
            try {
                existing = Rule.fromJson(JSONObject(json))
            } catch (_: Exception) {
            }
        }
        findViewById<TextView>(R.id.tvEditorTitle)
            .setText(if (existing == null) R.string.new_rule else R.string.edit_rule)

        val r = existing
        if (r != null) {
            etName.setText(r.name)
            etSender.setText(r.sender)
            etKeyword.setText(r.keyword)
            etRegex.setText(r.regex)
            etDest.setText(r.destNumber)
            action = r.action
            pickedSenderName = r.senderName
            pickedSenderNumber = r.sender
            pickedDestName = r.destName
            pickedDestNumber = r.destNumber
        }
        api.bind(r?.api)

        findViewById<View>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }

        findViewById<Button>(R.id.btnPickSender).setOnClickListener {
            pickContact { number, name ->
                pickedSenderNumber = number
                pickedSenderName = name
                etSender.setText(number)
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
        tvAction.text = getString(if (action == Rule.ACTION_API) R.string.action_api else R.string.action_sms)
        smsBox.visibility = if (action == Rule.ACTION_SMS) View.VISIBLE else View.GONE
        apiBox.visibility = if (action == Rule.ACTION_API) View.VISIBLE else View.GONE
    }

    private fun save() {
        val sender = Rule.cleanSender(etSender.text.toString())
        if (sender.isEmpty()) {
            etSender.error = getString(R.string.err_sender)
            return
        }
        val regex = etRegex.text.toString().trim()
        if (regex.isNotEmpty()) {
            try {
                java.util.regex.Pattern.compile(regex)
            } catch (e: Exception) {
                etRegex.error = getString(R.string.err_regex, e.message?.lineSequence()?.firstOrNull() ?: "")
                return
            }
        }
        val dest = Rule.toAsciiNumber(etDest.text.toString())
        if (action == Rule.ACTION_SMS && dest.isEmpty()) {
            etDest.error = getString(R.string.err_dest)
            return
        }
        if (action == Rule.ACTION_API && !api.validate()) return

        val rule = Rule(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = etName.text.toString().trim(),
            sender = sender,
            senderName = if (sender == pickedSenderNumber) pickedSenderName else "",
            keyword = etKeyword.text.toString().trim(),
            destNumber = dest,
            destName = if (dest == pickedDestNumber) pickedDestName else "",
            enabled = existing?.enabled ?: true,
            regex = regex,
            action = action,
            api = api.read()
        )
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RULE, rule.toJson().toString()))
        finish()
    }
}
