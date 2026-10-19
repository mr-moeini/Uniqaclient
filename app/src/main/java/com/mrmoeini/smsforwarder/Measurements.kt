package com.mrmoeini.smsforwarder

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Collects the device measurements of a [MeasureRule] into one "name: value" per line text. */
object Measurements {

    fun collect(context: Context, rule: MeasureRule, deadlineElapsed: Long?): String {
        val lines = mutableListOf<String>()
        if (rule.has(MeasureRule.METRIC_CPU)) lines.add("cpu: " + cpu())
        if (rule.has(MeasureRule.METRIC_RAM)) lines.add("ram: " + ram(context))
        if (rule.has(MeasureRule.METRIC_DISK)) lines.add("disk: " + disk())
        if (rule.has(MeasureRule.METRIC_IP)) lines.add("ip: " + ip())
        if (rule.has(MeasureRule.METRIC_INTERNET)) lines.add("internet: " + internet(context))
        if (rule.has(MeasureRule.METRIC_PING)) {
            val hosts = rule.pingHosts.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            for (h in hosts) lines.add("ping $h: " + ping(h, remaining(deadlineElapsed, 4000L)))
        }
        if (rule.has(MeasureRule.METRIC_USSD) && rule.ussdCode.isNotBlank()) {
            val code = rule.ussdCode.trim()
            lines.add("ussd $code: " + ussd(context, code, rule.ussdRegex, remaining(deadlineElapsed, 8000L)))
        }
        return lines.joinToString("\n")
    }

    private fun remaining(deadlineElapsed: Long?, max: Long): Long {
        if (deadlineElapsed == null) return max
        val left = deadlineElapsed - SystemClock.elapsedRealtime()
        return left.coerceIn(500L, max)
    }

    // ------------------------------------------------------------------ cpu

    private fun readStat(): LongArray? {
        return try {
            RandomAccessFile("/proc/stat", "r").use { r ->
                val t = (r.readLine() ?: return null).trim().split(Regex("\\s+"))
                val v = t.drop(1).take(8).map { it.toLong() }
                if (v.size < 5) return null
                longArrayOf(v[3] + v[4], v.sum())   // idle (+iowait), total
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Whole-device CPU usage. Newer Android versions block /proc/stat for apps; then load average. */
    private fun cpu(): String {
        val a = readStat()
        if (a != null) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
            }
            val b = readStat()
            if (b != null) {
                val dt = b[1] - a[1]
                val di = b[0] - a[0]
                if (dt > 0) return String.format(Locale.US, "%.1f%%", 100.0 * (dt - di) / dt)
            }
        }
        try {
            val parts = File("/proc/loadavg").readText().trim().split(" ")
            if (parts.size >= 3) {
                return "load ${parts[0]} ${parts[1]} ${parts[2]} (${Runtime.getRuntime().availableProcessors()} cores)"
            }
        } catch (_: Exception) {
        }
        return "unavailable (blocked by this Android version)"
    }

    // ------------------------------------------------------------------ ram / disk

    private fun mb(bytes: Long): String = String.format(Locale.US, "%d MB", bytes / (1024 * 1024))
    private fun gb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)

    private fun ram(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val used = mi.totalMem - mi.availMem
        val pct = if (mi.totalMem > 0) 100.0 * used / mi.totalMem else 0.0
        return String.format(Locale.US, "%.1f%% used (%s of %s)", pct, mb(used), mb(mi.totalMem))
    }

    private fun disk(): String {
        return try {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = st.blockCountLong * st.blockSizeLong
            val free = st.availableBlocksLong * st.blockSizeLong
            val pct = if (total > 0) 100.0 * (total - free) / total else 0.0
            String.format(Locale.US, "%.1f%% used (free %s of %s)", pct, gb(free), gb(total))
        } catch (e: Exception) {
            "unavailable"
        }
    }

    // ------------------------------------------------------------------ ip / internet

    private fun ip(): String {
        val out = mutableListOf<String>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs != null && ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        out.add(ni.name + " " + addr.hostAddress)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return if (out.isEmpty()) "none" else out.joinToString(", ")
    }

    private fun internet(context: Context): String {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val net = cm.activeNetwork ?: return "disconnected"
            val caps = cm.getNetworkCapabilities(net) ?: return "disconnected"
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "OTHER"
            }
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (ok) "connected ($type)" else "connected ($type) but no internet access"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // ------------------------------------------------------------------ ping

    private fun ping(host: String, timeoutMs: Long): String {
        if (!Regex("^[A-Za-z0-9._:-]{1,253}$").matches(host)) return "invalid host"
        try {
            val p = ProcessBuilder("ping", "-c", "1", "-W", "2", host).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
                } catch (_: Exception) {
                }
            }
            reader.start()
            reader.join(timeoutMs)
            if (reader.isAlive) {
                p.destroy()
                return "timeout"
            }
            val m = Regex("time[=<]([0-9.]+)\\s*ms").find(out)
            return if (m != null) m.groupValues[1] + " ms" else "unreachable"
        } catch (_: Exception) {
            // ping binary not usable: fall back to Java reachability
        }
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val ok = InetAddress.getByName(host).isReachable(timeoutMs.toInt())
            if (ok) (SystemClock.elapsedRealtime() - t0).toString() + " ms" else "unreachable"
        } catch (e: Exception) {
            "unknown host"
        }
    }

    // ------------------------------------------------------------------ ussd

    private fun ussd(context: Context, code: String, regex: String, timeoutMs: Long): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "needs Android 8 or newer"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) return "CALL_PHONE permission is not granted"
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>("")
        try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            tm.sendUssdRequest(
                code,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager, request: String, response: CharSequence
                    ) {
                        result.set(response.toString().trim())
                        latch.countDown()
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager, request: String, failureCode: Int
                    ) {
                        result.set("failed (code $failureCode)")
                        latch.countDown()
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            return "error " + e.javaClass.simpleName
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return "timeout (no response)"
        val text = result.get()
        if (regex.isBlank()) return text
        return try {
            Rule.applyRegex(regex, text) ?: "no match"
        } catch (e: Exception) {
            "invalid regex"
        }
    }
}
