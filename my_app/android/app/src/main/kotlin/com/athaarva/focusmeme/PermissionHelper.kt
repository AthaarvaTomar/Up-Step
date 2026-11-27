package com.athaarva.focusmeme

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils

/**
 * Checks Android permission/service status and opens the corresponding system
 * settings screens for `status` and `open*` MethodChannel calls.
 */
object PermissionHelper {
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, FocusBlockerService::class.java)
            .flattenToString()
        val expectedShort = ComponentName(context, FocusBlockerService::class.java)
            .flattenToShortString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val entry = splitter.next()
            if (entry.equals(expected, ignoreCase = true) ||
                entry.equals(expectedShort, ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openAccessibilitySettings(context: Context) {
        startActivitySafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (!startActivitySafely(context, intent)) {
            startActivitySafely(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    fun openBatterySettings(context: Context) {
        // Open the general battery-optimization list rather than requesting the
        // restricted REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            appDetailsIntent(context)
        }
        if (!startActivitySafely(context, intent)) {
            startActivitySafely(context, appDetailsIntent(context))
        }
    }

    fun openAutostartSettings(context: Context) {
        // Known OEM autostart / background-startup screens (Xiaomi, Oppo, Vivo, Realme, OnePlus).
        val oemIntents = listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            ComponentName(
                "com.oplus.safecenter",
                "com.oplus.safecenter.startupapp.StartupAppListActivity",
            ),
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            ),
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            ),
        )

        for (component in oemIntents) {
            val intent = Intent().setComponent(component)
            if (startActivitySafely(context, intent)) {
                return
            }
        }

        // Safe fallback on stock Android / Samsung / Pixel / unsupported OEMs.
        startActivitySafely(context, appDetailsIntent(context))
    }

    private fun appDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    private fun startActivitySafely(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
