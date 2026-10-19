package com.mrmoeini.smsforwarder

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

/** Receives the real send result of a forwarded SMS and writes it to the log. */
class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT) return
        val dest = intent.getStringExtra(EXTRA_DEST) ?: "?"
        val part = intent.getIntExtra(EXTRA_PART, 1)
        val total = intent.getIntExtra(EXTRA_TOTAL, 1)
        val suffix = if (total > 1) " (part $part/$total)" else ""
        val result = when (resultCode) {
            Activity.RESULT_OK -> "SENT OK"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "FAILED: generic failure (check SIM, credit, SMS permission)"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "FAILED: no service"
            SmsManager.RESULT_ERROR_NULL_PDU -> "FAILED: null PDU"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "FAILED: radio off / airplane mode"
            else -> "FAILED: code $resultCode"
        }
        RuleStore.addLog(context, "-> $dest$suffix  $result")
    }

    companion object {
        const val ACTION_SENT = "com.mrmoeini.smsforwarder.SMS_SENT"
        const val EXTRA_DEST = "dest"
        const val EXTRA_PART = "part"
        const val EXTRA_TOTAL = "total"
    }
}
