package com.mrmoeini.smsforwarder

import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

/** Drives the shared "API server" form (layout_api_form) inside any editor activity. */
class ApiFormController(private val activity: AppCompatActivity) {

    private val etUrl: EditText = activity.findViewById(R.id.etUrl)
    private val tvMethod: TextView = activity.findViewById(R.id.tvMethod)
    private val tvFormat: TextView = activity.findViewById(R.id.tvFormat)
    private val customBox: View = activity.findViewById(R.id.customBox)
    private val etContentType: EditText = activity.findViewById(R.id.etContentType)
    private val etBody: EditText = activity.findViewById(R.id.etBody)
    private val tvAuth: TextView = activity.findViewById(R.id.tvAuth)
    private val etTokenUrl: EditText = activity.findViewById(R.id.etTokenUrl)
    private val etAuthUser: EditText = activity.findViewById(R.id.etAuthUser)
    private val etAuthName: EditText = activity.findViewById(R.id.etAuthName)
    private val etAuthSecret: EditText = activity.findViewById(R.id.etAuthSecret)
    private val etScope: EditText = activity.findViewById(R.id.etScope)
    private val etHeaders: EditText = activity.findViewById(R.id.etHeaders)
    private val etTimeout: EditText = activity.findViewById(R.id.etTimeout)
    private val swInsecure: SwitchMaterial = activity.findViewById(R.id.swInsecure)
    private val btnTestApi: Button = activity.findViewById(R.id.btnTestApi)

    private var methodIdx = 0
    private var format = ApiConfig.FORMAT_TEXT
    private var authType = ApiConfig.AUTH_NONE

    init {
        tvMethod.setOnClickListener {
            pickOption(R.string.sec_api, ApiConfig.METHODS, methodIdx) { methodIdx = it; refresh() }
        }
        tvFormat.setOnClickListener {
            pickOption(R.string.sec_api, formatLabels(), format) { format = it; refresh() }
        }
        tvAuth.setOnClickListener {
            pickOption(R.string.sec_auth, authLabels(), authType) { authType = it; refresh() }
        }
        btnTestApi.setOnClickListener { test() }
    }

    private fun formatLabels() = arrayOf(
        activity.getString(R.string.fmt_text), activity.getString(R.string.fmt_json),
        activity.getString(R.string.fmt_form), activity.getString(R.string.fmt_custom)
    )

    private fun authLabels() = arrayOf(
        activity.getString(R.string.auth_none), activity.getString(R.string.auth_basic),
        activity.getString(R.string.auth_bearer), activity.getString(R.string.auth_key_header),
        activity.getString(R.string.auth_key_query), activity.getString(R.string.auth_oauth2)
    )

    private fun pickOption(title: Int, labels: Array<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setSingleChoiceItems(labels, current) { d, which ->
                onPick(which)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun show(v: View, visible: Boolean) {
        v.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Fills the form from [cfg] (or defaults when null). */
    fun bind(cfg: ApiConfig?) {
        if (cfg != null) {
            etUrl.setText(cfg.url)
            methodIdx = ApiConfig.METHODS.indexOf(cfg.method).coerceAtLeast(0)
            format = cfg.format
            etBody.setText(cfg.bodyTemplate)
            etContentType.setText(cfg.contentType)
            authType = cfg.authType
            etTokenUrl.setText(cfg.tokenUrl)
            etAuthUser.setText(cfg.authUser)
            etAuthName.setText(cfg.authName)
            etAuthSecret.setText(cfg.authSecret)
            etScope.setText(cfg.scope)
            etHeaders.setText(cfg.headers)
            etTimeout.setText(cfg.timeoutSec.toString())
            swInsecure.isChecked = cfg.insecureSsl
        } else {
            etContentType.setText("application/json")
            etTimeout.setText("15")
        }
        refresh()
    }

    fun refresh() {
        tvMethod.text = activity.getString(R.string.method_label, ApiConfig.METHODS[methodIdx])
        tvFormat.text = activity.getString(R.string.format_label, formatLabels()[format.coerceIn(0, 3)])
        val isGet = ApiConfig.METHODS[methodIdx] == "GET"
        show(tvFormat, !isGet)
        show(customBox, !isGet && format == ApiConfig.FORMAT_CUSTOM)

        tvAuth.text = activity.getString(R.string.auth_label, authLabels()[authType.coerceIn(0, 5)])
        show(etTokenUrl, authType == ApiConfig.AUTH_OAUTH2)
        show(etScope, authType == ApiConfig.AUTH_OAUTH2)
        show(etAuthUser, authType == ApiConfig.AUTH_BASIC || authType == ApiConfig.AUTH_OAUTH2)
        show(etAuthName, authType == ApiConfig.AUTH_KEY_HEADER || authType == ApiConfig.AUTH_KEY_QUERY)
        show(etAuthSecret, authType != ApiConfig.AUTH_NONE)
        when (authType) {
            ApiConfig.AUTH_BASIC -> {
                etAuthUser.hint = activity.getString(R.string.hint_username)
                etAuthSecret.hint = activity.getString(R.string.hint_password)
            }
            ApiConfig.AUTH_BEARER -> etAuthSecret.hint = activity.getString(R.string.hint_token)
            ApiConfig.AUTH_KEY_HEADER -> {
                etAuthName.hint = activity.getString(R.string.hint_key_header)
                etAuthSecret.hint = activity.getString(R.string.hint_api_key)
            }
            ApiConfig.AUTH_KEY_QUERY -> {
                etAuthName.hint = activity.getString(R.string.hint_key_query)
                etAuthSecret.hint = activity.getString(R.string.hint_api_key)
            }
            ApiConfig.AUTH_OAUTH2 -> {
                etAuthUser.hint = activity.getString(R.string.hint_client_id)
                etAuthSecret.hint = activity.getString(R.string.hint_client_secret)
            }
        }
    }

    fun read(): ApiConfig = ApiConfig(
        url = etUrl.text.toString().trim(),
        method = ApiConfig.METHODS[methodIdx],
        format = format,
        bodyTemplate = etBody.text.toString(),
        contentType = etContentType.text.toString().trim().ifEmpty { "application/json" },
        authType = authType,
        authUser = etAuthUser.text.toString().trim(),
        authSecret = etAuthSecret.text.toString().trim(),
        authName = etAuthName.text.toString().trim(),
        tokenUrl = etTokenUrl.text.toString().trim(),
        scope = etScope.text.toString().trim(),
        headers = etHeaders.text.toString(),
        timeoutSec = (etTimeout.text.toString().toIntOrNull() ?: 15).coerceIn(1, 120),
        insecureSsl = swInsecure.isChecked
    )

    private fun validUrl(u: String): Boolean =
        u.startsWith("http://", true) || u.startsWith("https://", true)

    /** Shows field errors and returns false when the API settings are unusable. */
    fun validate(): Boolean {
        val cfg = read()
        if (!validUrl(cfg.url)) {
            etUrl.error = activity.getString(R.string.err_url)
            return false
        }
        if (cfg.authType == ApiConfig.AUTH_OAUTH2 && !validUrl(cfg.tokenUrl)) {
            etTokenUrl.error = activity.getString(R.string.err_url)
            return false
        }
        return true
    }

    private fun test() {
        val cfg = read()
        if (!validUrl(cfg.url)) {
            etUrl.error = activity.getString(R.string.err_url)
            return
        }
        btnTestApi.isEnabled = false
        btnTestApi.setText(R.string.test_running)
        val app = activity.applicationContext
        Thread {
            val res = ApiClient.send(
                app, cfg, "TEST", "This is a test message from Uniqa Client",
                System.currentTimeMillis(), SystemClock.elapsedRealtime() + 20000L, true
            )
            activity.runOnUiThread {
                btnTestApi.isEnabled = true
                btnTestApi.setText(R.string.btn_test_api)
                AlertDialog.Builder(activity)
                    .setTitle(if (res.success) R.string.test_ok else R.string.test_failed)
                    .setMessage(res.message)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }.start()
    }
}
