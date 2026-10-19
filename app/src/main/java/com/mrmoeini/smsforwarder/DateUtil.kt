package com.mrmoeini.smsforwarder

import android.app.DatePickerDialog
import android.content.Context
import android.icu.util.ULocale
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import java.util.Calendar
import java.util.Locale

/** Dates are shown in the Persian (Jalali) calendar when the device language is Persian. */
object DateUtil {

    private fun persianCal(): android.icu.util.Calendar =
        android.icu.util.Calendar.getInstance(ULocale("fa_IR@calendar=persian"))

    val useJalali: Boolean
        get() = Locale.getDefault().language == "fa"

    fun format(millis: Long): String {
        return if (useJalali) {
            val c = persianCal()
            c.timeInMillis = millis
            String.format(
                Locale.US, "%04d/%02d/%02d",
                c.get(android.icu.util.Calendar.YEAR),
                c.get(android.icu.util.Calendar.MONTH) + 1,
                c.get(android.icu.util.Calendar.DAY_OF_MONTH)
            )
        } else {
            val c = Calendar.getInstance()
            c.timeInMillis = millis
            String.format(
                Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
            )
        }
    }

    fun startOfDay(millis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun endOfDay(millis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = startOfDay(millis)
        c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis - 1
    }

    fun pick(context: Context, initial: Long, onPicked: (Long) -> Unit) {
        val base = if (initial > 0L) initial else System.currentTimeMillis()
        if (useJalali) {
            pickJalali(context, base, onPicked)
        } else {
            val c = Calendar.getInstance()
            c.timeInMillis = base
            DatePickerDialog(
                context,
                { _, y, m, d ->
                    val r = Calendar.getInstance()
                    r.clear()
                    r.set(y, m, d)
                    onPicked(r.timeInMillis)
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    private fun pickJalali(context: Context, base: Long, onPicked: (Long) -> Unit) {
        val cal = persianCal()
        cal.timeInMillis = base
        val y0 = cal.get(android.icu.util.Calendar.YEAR)
        val m0 = cal.get(android.icu.util.Calendar.MONTH) + 1
        val d0 = cal.get(android.icu.util.Calendar.DAY_OF_MONTH)

        fun picker(min: Int, max: Int, value: Int): NumberPicker {
            val p = NumberPicker(context)
            p.minValue = min
            p.maxValue = max
            p.value = value
            p.wrapSelectorWheel = true
            return p
        }

        val py = picker(y0 - 3, y0 + 20, y0)
        val pm = picker(1, 12, m0)
        val pd = picker(1, 31, d0)
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        row.setPadding(pad, pad, pad, pad)
        row.addView(py)
        row.addView(pm)
        row.addView(pd)

        AlertDialog.Builder(context)
            .setView(row)
            .setPositiveButton(R.string.save) { _, _ ->
                val c = persianCal()
                c.set(py.value, pm.value - 1, 1)
                val maxDay = c.getActualMaximum(android.icu.util.Calendar.DAY_OF_MONTH)
                c.set(py.value, pm.value - 1, minOf(pd.value, maxDay))
                onPicked(c.timeInMillis)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
