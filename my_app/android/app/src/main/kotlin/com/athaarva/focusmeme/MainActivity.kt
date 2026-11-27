package com.athaarva.focusmeme

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/**
 * Hosts the locked 8-method `focusmeme/blocker` MethodChannel (PRD section 6):
 * `sync`, `status`, `getInstalledApps`, `getUsageStats`,
 * `openAccessibility`, `openUsageAccess`, `openBattery`, `openAutostart`.
 */
class MainActivity : FlutterActivity() {
    companion object {
        private const val CHANNEL_NAME = "focusmeme/blocker"
    }

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private lateinit var store: BlockerStore
    private lateinit var usageTracker: UsageTracker

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        store = BlockerStore(applicationContext)
        usageTracker = UsageTracker(applicationContext, store)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "sync" -> handleSync(call, result)
                "status" -> handleStatus(result)
                "getInstalledApps" -> handleGetInstalledApps(result)
                "getUsageStats" -> handleGetUsageStats(result)
                "openAccessibility" -> {
                    PermissionHelper.openAccessibilitySettings(this)
                    result.success(null)
                }
                "openUsageAccess" -> {
                    PermissionHelper.openUsageAccessSettings(this)
                    result.success(null)
                }
                "openBattery" -> {
                    PermissionHelper.openBatterySettings(this)
                    result.success(null)
                }
                "openAutostart" -> {
                    PermissionHelper.openAutostartSettings(this)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun handleSync(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *>
        if (args == null) {
            result.error("INVALID_ARGS", "Expected a Map payload for sync.", null)
            return
        }

        val limitsRaw = args["limits"] as? List<*>
        val hasStrictScheduleKey = args.containsKey("strictSchedule")
        val strictScheduleRaw = args["strictSchedule"] as? Map<*, *>
        val settingsRaw = args["settings"] as? Map<*, *>

        backgroundExecutor.execute {
            try {
                store.saveSyncPayload(
                    limitsRaw = limitsRaw,
                    strictScheduleRaw = strictScheduleRaw,
                    settingsRaw = settingsRaw,
                    hasStrictScheduleKey = hasStrictScheduleKey,
                )
                FocusBlockerService.onConfigSynced()
                runOnUiThread { result.success(null) }
            } catch (e: Exception) {
                runOnUiThread {
                    result.error("SYNC_ERROR", e.message, null)
                }
            }
        }
    }

    private fun handleStatus(result: MethodChannel.Result) {
        val statusMap = mapOf<String, Any?>(
            "accessibilityEnabled" to PermissionHelper.isAccessibilityEnabled(applicationContext),
            "usageAccessGranted" to usageTracker.hasUsageAccess(),
            "batteryOptimizationIgnored" to PermissionHelper.isBatteryOptimizationIgnored(applicationContext),
            "serviceRunning" to (FocusBlockerService.instance != null),
            // Returning strictSchedule lets Flutter recover Pomodoro UI after an app kill (M4).
            "strictSchedule" to store.getStrictScheduleMap(),
        )
        result.success(statusMap)
    }

    private fun handleGetUsageStats(result: MethodChannel.Result) {
        backgroundExecutor.execute {
            try {
                val nowMs = System.currentTimeMillis()
                val dayKey = store.todayKey(nowMs)
                val perAppUsageMs = FocusBlockerService.getLiveUsageSnapshot(nowMs)
                    ?: usageTracker.getTodayUsageSnapshot(nowMs)
                val perAppAttempts = store.getAttemptsMap(nowMs)

                val payload = mapOf<String, Any>(
                    "dayKey" to dayKey,
                    "perAppUsageMs" to perAppUsageMs,
                    "perAppAttempts" to perAppAttempts,
                )
                runOnUiThread { result.success(payload) }
            } catch (e: Exception) {
                runOnUiThread {
                    result.error("USAGE_STATS_ERROR", e.message, null)
                }
            }
        }
    }

    private fun handleGetInstalledApps(result: MethodChannel.Result) {
        // Querying launcher intents reads package metadata; keep it off the UI thread.
        backgroundExecutor.execute {
            try {
                val pm = packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        launcherIntent,
                        PackageManager.ResolveInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(launcherIntent, 0)
                }

                // Deduplicate apps with multiple launcher icons and skip FocusMeme itself.
                val ownPackage = applicationContext.packageName
                val seenPackages = HashSet<String>()
                val apps = mutableListOf<Map<String, String>>()

                for (info in resolveInfos) {
                    val pkg = info.activityInfo?.packageName ?: continue
                    if (pkg == ownPackage || !seenPackages.add(pkg)) continue
                    val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                    apps.add(
                        mapOf(
                            "name" to label.ifEmpty { pkg },
                            "packageName" to pkg,
                        ),
                    )
                }

                apps.sortBy { it["name"]?.lowercase() }
                runOnUiThread { result.success(apps) }
            } catch (e: Exception) {
                runOnUiThread {
                    result.error("INSTALLED_APPS_ERROR", e.message, null)
                }
            }
        }
    }

    override fun onDestroy() {
        backgroundExecutor.shutdown()
        super.onDestroy()
    }
}
