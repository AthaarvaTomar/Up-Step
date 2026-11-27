package com.athaarva.focusmeme

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    companion object {
        private const val CHANNEL_NAME = "focusmeme/blocker"
    }

    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "getInstalledApps" -> handleGetInstalledApps(result)
                // Remaining PRD methods (sync, status, getUsageStats, open*) are wired in M2-M3.
                else -> result.notImplemented()
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
