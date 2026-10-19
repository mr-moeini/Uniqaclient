package com.mrmoeini.smsforwarder

import android.content.Context
import android.os.SystemClock

/** Runs measurement rules: collect -> optional regex -> SMS or API. */
object MeasureRunner {

    fun label(rule: MeasureRule): String =
        if (rule.name.isNotBlank()) rule.name else "measure"

    /** Collects the text of a rule (used by the editor's preview too). */
    fun content(context: Context, rule: MeasureRule, deadlineElapsed: Long?): String =
        Measurements.collect(context, rule, deadlineElapsed)

    /** Collects and forwards one rule now. */
    fun execute(context: Context, rule: MeasureRule, deadlineElapsed: Long?) {
        val name = label(rule)
        val raw = content(context, rule, deadlineElapsed)
        val text: String
        if (rule.regex.isBlank()) {
            text = raw
        } else {
            val r = try {
                Rule.applyRegex(rule.regex, raw)
            } catch (e: Exception) {
                raw
            }
            if (r == null) {
                RuleStore.addLog(context, "measure '$name': regex did not match, not sent")
                return
            }
            text = r
        }
        val ts = System.currentTimeMillis()
        if (rule.action == Rule.ACTION_API) {
            val res = ApiClient.send(context, rule.api, "measure:$name", text, ts, deadlineElapsed)
            when {
                res.success -> RuleStore.addLog(context, "measure '$name' -> API OK (${res.message.take(40)})")
                res.retryable -> {
                    RuleStore.addLog(context, "measure '$name' -> API failed (${res.message.take(60)}), queued for retry")
                    ApiForwardWorker.enqueue(context, ApiForwardWorker.KIND_MEASURE, rule.id, "measure:$name", text, ts)
                }
                else -> RuleStore.addLog(context, "measure '$name' -> API FAILED: ${res.message.take(80)}")
            }
        } else {
            SmsSender.send(context, rule.destNumber, text, -1, "measure '$name'")
        }
    }

    /** Runs every rule that is due (stops when the time budget is used up), then re-schedules. */
    fun runDue(context: Context, budgetMs: Long) {
        val app = context.applicationContext
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        val rules = MeasureStore.load(app)
        val now = System.currentTimeMillis()
        for (r in rules) {
            if (SystemClock.elapsedRealtime() > deadline - 1500L) break   // leave the rest for the next alarm
            if (!r.isConfigured() || !r.activeAt(now)) continue
            val last = MeasureStore.lastRun(app, r.id)
            if (last > 0L && now < last + r.intervalMin * 60_000L) continue
            MeasureStore.setLastRun(app, r.id, now)
            try {
                execute(app, r, deadline)
            } catch (e: Exception) {
                RuleStore.addLog(app, "measure '${label(r)}' ERROR ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        MeasureScheduler.schedule(app)
    }
}
