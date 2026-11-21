package com.example.wifiguardian

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Launches a short, fixed diagnostic in the user's separate Termux app. */
object TermuxBridge {
    private const val PACKAGE = "com.termux"
    private const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val PREFIX = "/data/data/com.termux/files/usr/bin/"

    fun installed(activity: Activity): Boolean =
        activity.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    fun hasPermission(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun open(activity: Activity): Boolean {
        val intent = activity.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        activity.startActivity(intent)
        return true
    }

    fun permissionSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${activity.packageName}"))
        activity.startActivity(intent)
    }

    fun runGatewayDiagnostic(activity: Activity, gateway: String, ports: Boolean): Boolean {
        // Never accept a user-supplied executable or option string. The gateway
        // comes from Android's active Wi-Fi link properties and is validated here.
        val octets = gateway.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 } ||
            !(octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 ||
              octets[0] == 192 && octets[1] == 168)) return false
        val executable = if (ports) "nmap" else "ping"
        val arguments = if (ports) arrayOf("-sT", "-Pn", "--top-ports", "20", gateway)
                        else arrayOf("-c", "4", gateway)
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(PACKAGE, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", PREFIX + executable)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        return try {
            activity.startService(intent) != null
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }
}
