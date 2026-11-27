package com.athaarva.focusmeme

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Tracks per-app daily usage incrementally using [UsageStatsManager.queryEvents].
 *
 * Only queries new events since `lastQueryEndMs` (starting from local midnight),
 * persists state to [BlockerStore] so same-day service restarts keep totals,
 * and falls back gracefully to accessibility foreground transitions if Usage
 * Access has not been granted yet.
 */
class UsageTracker(
    private val context: Context,
    private val store: BlockerStore,
) {
    companion object {
        // Stable UsageEvents.Event constants across API 21-36:
        // 1 = MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
        // 2 = MOVE_TO_BACKGROUND / ACTIVITY_PAUSED
        // 16 = SCREEN_NON_INTERACTIVE
        // 17 = KEYGUARD_SHOWN
        private const val EVENT_RESUMED = 1
        private const val EVENT_PAUSED = 2
        private const val EVENT_SCREEN_NON_INTERACTIVE = 16
        private const val EVENT_KEYGUARD_SHOWN = 17
    }

    private val appContext = context.applicationContext
    private var currentDayKey: String? = null
    private var lastQueryEndMs: Long = 0L
    private var usageByPkg: MutableMap<String, Long> = LinkedHashMap()
    private var activePkg: String? = null
    private var activeStartMs: Long = 0L
    private var lastKnownForegroundPkg: String? = null
    private var loadedFromStore = false

    fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Synchronized
    private fun ensureLoadedForToday(nowMs: Long) {
        val today = store.todayKey(nowMs)
        if (!loadedFromStore) {
            val savedDay = store.getUsageDay()
            if (savedDay == today) {
                currentDayKey = savedDay
                lastQueryEndMs = store.getUsageLastEndMs()
                usageByPkg = store.getUsageMap()
                activePkg = store.getUsageActivePackage()
                activeStartMs = store.getUsageActiveStartMs()
                lastKnownForegroundPkg = activePkg
            }
            loadedFromStore = true
        }

        if (currentDayKey != today) {
            // Midnight rollover (or first run today): reset counters by date key.
            currentDayKey = today
            lastQueryEndMs = store.startOfDayMillis(nowMs)
            usageByPkg = LinkedHashMap()
            activePkg = null
            activeStartMs = 0L
            persistLocked()
        }
    }

    @Synchronized
    private fun persistLocked() {
        val day = currentDayKey ?: return
        store.saveUsageState(
            dayKey = day,
            lastEndMs = lastQueryEndMs,
            usageMap = usageByPkg,
            activePkg = activePkg,
            activeStartMs = activeStartMs,
        )
    }

    @Synchronized
    fun refreshIncremental(nowMs: Long = System.currentTimeMillis()) {
        ensureLoadedForToday(nowMs)

        if (!hasUsageAccess()) {
            // Without Usage Access, accumulate from live accessibility session timestamps.
            val pkg = activePkg
            if (pkg != null && activeStartMs > 0L && nowMs > activeStartMs) {
                usageByPkg[pkg] = (usageByPkg[pkg] ?: 0L) + (nowMs - activeStartMs)
                activeStartMs = nowMs
                lastQueryEndMs = nowMs
                persistLocked()
            }
            return
        }

        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return

        val startOfDay = store.startOfDayMillis(nowMs)
        val queryStart = maxOf(lastQueryEndMs, startOfDay)
        if (nowMs <= queryStart) return

        val events = usm.queryEvents(queryStart, nowMs) ?: return
        val event = UsageEvents.Event()
        var processedAny = false

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            val pkg = event.packageName
            processedAny = true

            when (event.eventType) {
                EVENT_RESUMED -> {
                    val prevPkg = activePkg
                    if (prevPkg != null && activeStartMs > 0L && ts > activeStartMs) {
                        usageByPkg[prevPkg] = (usageByPkg[prevPkg] ?: 0L) + (ts - activeStartMs)
                    }
                    if (!pkg.isNullOrEmpty()) {
                        activePkg = pkg
                        activeStartMs = ts
                        lastKnownForegroundPkg = pkg
                    }
                }
                EVENT_PAUSED -> {
                    if (pkg != null && pkg == activePkg && activeStartMs > 0L && ts > activeStartMs) {
                        usageByPkg[pkg] = (usageByPkg[pkg] ?: 0L) + (ts - activeStartMs)
                        activePkg = null
                        activeStartMs = 0L
                    }
                }
                EVENT_SCREEN_NON_INTERACTIVE, EVENT_KEYGUARD_SHOWN -> {
                    val prevPkg = activePkg
                    if (prevPkg != null && activeStartMs > 0L && ts > activeStartMs) {
                        usageByPkg[prevPkg] = (usageByPkg[prevPkg] ?: 0L) + (ts - activeStartMs)
                    }
                    activePkg = null
                    activeStartMs = 0L
                }
            }
        }

        lastQueryEndMs = nowMs
        if (processedAny) {
            persistLocked()
        }
    }

    /**
     * Called when [FocusBlockerService] sees a non-ignored app enter the foreground.
     */
    @Synchronized
    fun onForegroundApp(packageName: String, nowMs: Long = System.currentTimeMillis()) {
        refreshIncremental(nowMs)

        // If UsageStatsManager has not flushed the RESUMED event yet (or permission is off),
        // close any previous open session and start tracking the new foreground package.
        if (activePkg != packageName) {
            val prevPkg = activePkg
            if (prevPkg != null && activeStartMs > 0L && nowMs > activeStartMs) {
                usageByPkg[prevPkg] = (usageByPkg[prevPkg] ?: 0L) + (nowMs - activeStartMs)
            }
            activePkg = packageName
            activeStartMs = nowMs
            lastKnownForegroundPkg = packageName
            persistLocked()
        } else {
            lastKnownForegroundPkg = packageName
        }
    }

    /**
     * Called on `ACTION_SCREEN_OFF` to commit accrued usage and stop counting while locked.
     */
    @Synchronized
    fun onScreenOff(nowMs: Long = System.currentTimeMillis()) {
        refreshIncremental(nowMs)
        val pkg = activePkg
        if (pkg != null && activeStartMs > 0L && nowMs > activeStartMs) {
            usageByPkg[pkg] = (usageByPkg[pkg] ?: 0L) + (nowMs - activeStartMs)
            lastKnownForegroundPkg = pkg
        }
        activePkg = null
        activeStartMs = 0L
        lastQueryEndMs = nowMs
        persistLocked()
    }

    /**
     * Returns total milliseconds used today for [packageName], including the currently
     * open foreground session if [packageName] is active right now.
     */
    @Synchronized
    fun getTodayUsageMs(packageName: String, nowMs: Long = System.currentTimeMillis()): Long {
        refreshIncremental(nowMs)
        val base = usageByPkg[packageName] ?: 0L
        val openTail = if (activePkg == packageName && activeStartMs > 0L && nowMs > activeStartMs) {
            nowMs - activeStartMs
        } else {
            0L
        }
        return base + openTail
    }

    /**
     * Returns the best-known current foreground package (used when the screen wakes
     * and Android does not emit a fresh `TYPE_WINDOW_STATE_CHANGED` event).
     */
    @Synchronized
    fun getLatestForegroundPackage(nowMs: Long = System.currentTimeMillis()): String? {
        refreshIncremental(nowMs)
        return activePkg ?: lastKnownForegroundPkg
    }

    /**
     * Returns a full snapshot of today's per-app usage in milliseconds for `getUsageStats`.
     */
    @Synchronized
    fun getTodayUsageSnapshot(nowMs: Long = System.currentTimeMillis()): Map<String, Long> {
        refreshIncremental(nowMs)
        val snapshot = LinkedHashMap<String, Long>(usageByPkg)
        val pkg = activePkg
        if (pkg != null && activeStartMs > 0L && nowMs > activeStartMs) {
            snapshot[pkg] = (snapshot[pkg] ?: 0L) + (nowMs - activeStartMs)
        }
        return snapshot
    }
}
