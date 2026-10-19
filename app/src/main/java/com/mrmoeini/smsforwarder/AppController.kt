package com.mrmoeini.smsforwarder

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** "App Controller": launch / best-effort stop / install. (clear data and true force-stop need root.) */
object AppController {

    fun launch(context: Context, packageName: String, activity: String? = null): JSONObject {
        return try {
            val intent = if (!activity.isNullOrBlank()) {
                Intent().setClassName(packageName, activity)
            } else {
                context.packageManager.getLaunchIntentForPackage(packageName)
            }
            if (intent == null) {
                JSONObject().put("ok", false).put("error", "No launchable activity found for $packageName")
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                JSONObject().put("ok", true)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.javaClass.simpleName + ": " + e.message)
        }
    }

    /**
     * Best effort only: kills the package's background processes. Android does not let a normal
     * app force-stop another app in the foreground (that needs a system/shell permission) - unlike
     * a host-side `adb shell am force-stop`, which an on-device agent cannot replicate without root.
     */
    fun stopBestEffort(context: Context, packageName: String): JSONObject {
        return try {
            val am = context.getSystemService(ActivityManager::class.java)
            am.killBackgroundProcesses(packageName)
            JSONObject().put("ok", true).put("forced", false)
                .put("note", "Background processes killed. A running foreground app cannot be force-stopped without root/ADB.")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.javaClass.simpleName + ": " + e.message)
        }
    }

    fun clearDataUnsupported(packageName: String): JSONObject =
        JSONObject().put("ok", false)
            .put("error", "Clearing another app's data requires a system/root permission and is not possible from a normal app.")

    fun currentForegroundPackage(): String? = AgentAccessibilityService.instance?.currentPackage()

    fun listInstalledPackages(context: Context): JSONArray {
        val arr = JSONArray()
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val installed = pm.getInstalledApplications(0)
            for (info in installed) {
                arr.put(
                    JSONObject().put("package", info.packageName)
                        .put("label", pm.getApplicationLabel(info).toString())
                )
            }
        } catch (_: Exception) {
        }
        return arr
    }

    /**
     * Installs an APK already saved on this device's storage (e.g. pushed via the Evidence upload
     * endpoint). Android requires one user confirmation tap unless the app is a device owner.
     */
    fun installFromFile(context: Context, filePath: String): JSONObject {
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) return JSONObject().put("ok", false).put("error", "File not found: $filePath")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            JSONObject().put("ok", true).put("note", "Install screen opened - user confirmation is required by Android.")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.javaClass.simpleName + ": " + e.message)
        }
    }
}
