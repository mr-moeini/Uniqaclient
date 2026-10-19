package com.mrmoeini.smsforwarder

import android.view.Gravity
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** "Test Regex" dialog: paste a sample text and see what would be forwarded. */
object RegexTester {

    fun show(activity: AppCompatActivity, pattern: String) {
        val sample = EditText(activity)
        sample.hint = activity.getString(R.string.regex_sample_hint)
        sample.minLines = 3
        sample.gravity = Gravity.TOP or Gravity.START
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        sample.setPadding(pad, pad, pad, pad)
        AlertDialog.Builder(activity)
            .setTitle(R.string.btn_test_regex)
            .setView(sample)
            .setPositiveButton(R.string.regex_test_run) { _, _ ->
                val msg = try {
                    val r = Rule.applyRegex(pattern, sample.text.toString())
                    if (r == null) activity.getString(R.string.regex_no_match)
                    else activity.getString(R.string.regex_result, r)
                } catch (e: Exception) {
                    activity.getString(R.string.err_regex, e.message?.lineSequence()?.firstOrNull() ?: "")
                }
                AlertDialog.Builder(activity).setMessage(msg).setPositiveButton(R.string.close, null).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
