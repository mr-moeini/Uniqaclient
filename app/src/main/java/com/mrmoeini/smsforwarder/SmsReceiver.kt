package com.mrmoeini.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Telephony

/** Receives incoming SMS and forwards them (to a number or an API) according to the saved rules. */
class SmsReceiver : BroadcastReceiver() {

    private class Job(val from: String, val body: String, val rule: Rule)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages == null || messages.isEmpty()) return

        val app = context.applicationContext
        val rules = RuleStore.load(app)
        val subId = subscriptionId(intent)
        val timestamp = System.currentTimeMillis()

        val jobs = mutableListOf<Job>()
        // Long SMS arrive as several parts: join parts that share the same sender.
        val bySender = messages.groupBy { it.originatingAddress ?: "" }
        for ((from, parts) in bySender) {
            if (from.isEmpty()) continue
            val body = parts.joinToString("") { it.messageBody ?: "" }
            val matched = rules.filter { it.isConfigured() && it.matches(from, body) }
            // Log every received SMS (sender only, never the text) so problems can be diagnosed.
            RuleStore.addLog(app, "RECV from $from  rules=${rules.size} matched=${matched.size}")
            matched.forEach { jobs.add(Job(from, body, it)) }
        }
        if (jobs.isEmpty()) return

        val pending = goAsync()
        Thread {
            try {
                for (j in jobs) handle(app, j, subId, timestamp)
            } catch (e: Exception) {
                RuleStore.addLog(app, "ERROR ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(context: Context, job: Job, subId: Int, timestamp: Long) {
        val rule = job.rule
        val label = if (rule.name.isNotBlank()) rule.name else rule.sender
        val text = rule.extract(job.body)
        if (text == null) {
            RuleStore.addLog(context, "rule '$label': regex did not match, not forwarded")
            return
        }
        if (rule.action == Rule.ACTION_API) forwardToApi(context, rule, label, job.from, text, timestamp)
        else forwardSms(context, rule, job.from, text, subId)
    }

    private fun forwardToApi(
        context: Context, rule: Rule, label: String, from: String, text: String, timestamp: Long
    ) {
        val deadline = SystemClock.elapsedRealtime() + 7000L   // a receiver must finish quickly
        val res = ApiClient.send(context, rule.api, from, text, timestamp, deadline)
        when {
            res.success -> RuleStore.addLog(context, "API '$label' OK (${res.message.take(40)})")
            res.retryable -> {
                RuleStore.addLog(context, "API '$label' failed (${res.message.take(60)}), queued for retry")
                ApiForwardWorker.enqueue(context, ApiForwardWorker.KIND_SMS, rule.id, from, text, timestamp)
            }
            else -> RuleStore.addLog(context, "API '$label' FAILED: ${res.message.take(80)}")
        }
    }

    private fun forwardSms(context: Context, rule: Rule, from: String, body: String, subId: Int) {
        // Never forward a message back to the number it came from.
        val fromDigits = Rule.digitsOnly(from)
        if (fromDigits.isNotEmpty() &&
            Rule.digitsOnly(rule.destNumber).takeLast(10) == fromDigits.takeLast(10)
        ) {
            RuleStore.addLog(context, "skip: destination equals sender ($from)")
            return
        }
        SmsSender.send(context, rule.destNumber, "[$from] $body", subId, from)
    }

    /** SIM (subscription) that received the message, or -1 if unknown. */
    private fun subscriptionId(intent: Intent): Int {
        val a = intent.getIntExtra("subscription", -1)
        if (a >= 0) return a
        return intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
    }
}
