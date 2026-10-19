package com.mrmoeini.smsforwarder

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/** Sends an SMS (splitting long text) and logs the queue / real send result. */
object SmsSender {

    fun send(context: Context, dest: String, text: String, subId: Int, logPrefix: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            RuleStore.addLog(
                context,
                "$logPrefix -> $dest  NOT SENT: SEND_SMS permission is not granted. " +
                    "Open the app and allow SMS (App info > Permissions > SMS)."
            )
            return
        }
        try {
            val sms = smsManager(context, subId)
            val parts = sms.divideMessage(text)
            val base = (System.currentTimeMillis() and 0xFFFFF).toInt() * 200
            if (parts.size > 1) {
                val sent = ArrayList<PendingIntent>()
                for (i in parts.indices) sent.add(sentIntent(context, dest, i + 1, parts.size, base + i))
                sms.sendMultipartTextMessage(dest, null, parts, sent, null)
            } else {
                sms.sendTextMessage(dest, null, text, sentIntent(context, dest, 1, 1, base), null)
            }
            RuleStore.addLog(context, "$logPrefix -> $dest  queued")
        } catch (e: Exception) {
            RuleStore.addLog(context, "$logPrefix -> $dest  FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun sentIntent(
        context: Context, dest: String, part: Int, total: Int, requestCode: Int
    ): PendingIntent {
        val i = Intent(context, SmsSentReceiver::class.java).apply {
            action = SmsSentReceiver.ACTION_SENT
            putExtra(SmsSentReceiver.EXTRA_DEST, dest)
            putExtra(SmsSentReceiver.EXTRA_PART, part)
            putExtra(SmsSentReceiver.EXTRA_TOTAL, total)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context, subId: Int): SmsManager {
        if (subId >= 0) {
            try {
                return SmsManager.getSmsManagerForSubscriptionId(subId)
            } catch (_: Exception) {
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }
}
