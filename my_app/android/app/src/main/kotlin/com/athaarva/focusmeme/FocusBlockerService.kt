package com.athaarva.focusmeme

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * Core native blocker running independently of the Flutter UI process.
 *
 * Reads ONLY local data ([BlockerStore], [UsageTracker], local meme files/assets).
 * Never touches Firestore or the network at block time (PRD section 6).
 */
class FocusBlockerService : AccessibilityService() {
    companion object {
        private const val EVENT_DEBOUNCE_MS = 1_500L
        private const val BLOCK_DEBOUNCE_MS = 1_500L

        @Volatile
        var instance: FocusBlockerService? = null
            private set

        /**
         * Notifies the running service that Dart pushed a fresh `sync` payload so
         * active limits and timers update immediately.
         */
        fun onConfigSynced() {
            instance?.handleConfigSynced()
        }

        fun getLiveUsageSnapshot(nowMs: Long = System.currentTimeMillis()): Map<String, Long>? {
            val service = instance ?: return null
            return if (service::usageTracker.isInitialized) {
                service.usageTracker.getTodayUsageSnapshot(nowMs)
            } else {
                null
            }
        }
    }

    private lateinit var store: BlockerStore
    private lateinit var usageTracker: UsageTracker
    private lateinit var memeSelector: MemeSelector
    private lateinit var overlayController: MemeOverlayController

    private val mainHandler = Handler(Looper.getMainLooper())
    private var launcherPackages: Set<String> = emptySet()
    private var imePackages: Set<String> = emptySet()

    private var currentForegroundPkg: String? = null
    private var lastEventPkg: String? = null
    private var lastEventMs: Long = 0L
    private var lastBlockedPkg: String? = null
    private var lastBlockedMs: Long = 0L

    private var midSessionRunnable: Runnable? = null
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> handleScreenWake()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        store = BlockerStore(this)
        usageTracker = UsageTracker(this, store)
        memeSelector = MemeSelector(this, store)
        overlayController = MemeOverlayController(this)

        refreshIgnoredSystemPackages()
        registerScreenStateReceiver()

        // Evaluate the current foreground app immediately if the service restarts mid-session.
        if (isScreenUnlockedAndInteractive()) {
            val active = usageTracker.getLatestForegroundPackage()
            if (!active.isNullOrEmpty() && !isIgnoredPackage(active)) {
                evaluateForegroundApp(active, bypassDebounce = true)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (overlayController.isShowing) return

        val pkg = event.packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty()) return

        // Ignore keyboards, System UI, FocusMeme itself, and active home launchers.
        if (isIgnoredPackage(pkg)) {
            // Switching back to the launcher or FocusMeme means the user left the limited app.
            if (launcherPackages.contains(pkg) || pkg == applicationContext.packageName) {
                cancelMidSessionTimer()
                currentForegroundPkg = pkg
                usageTracker.onForegroundApp(pkg)
            }
            return
        }

        val nowMs = System.currentTimeMillis()
        if (pkg == lastEventPkg && (nowMs - lastEventMs) < EVENT_DEBOUNCE_MS) {
            return
        }
        lastEventPkg = pkg
        lastEventMs = nowMs

        evaluateForegroundApp(pkg, bypassDebounce = false)
    }

    override fun onInterrupt() {
        cancelMidSessionTimer()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        cancelMidSessionTimer()
        if (::overlayController.isInitialized) {
            overlayController.dismiss()
        }
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
            screenReceiverRegistered = false
        }
        if (instance === this) {
            instance = null
        }
    }

    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    private fun handleScreenOff() {
        // Pause/cancel the exact-second mid-session timer while the screen is off
        // and commit accrued foreground usage so locked time is never counted.
        cancelMidSessionTimer()
        usageTracker.onScreenOff()
    }

    private fun handleScreenWake() {
        if (!isScreenUnlockedAndInteractive()) return
        if (overlayController.isShowing) return

        // Android often emits no TYPE_WINDOW_STATE_CHANGED when unlocking back into
        // the same app, so re-evaluate the foreground app explicitly on wake/unlock.
        refreshIgnoredSystemPackages()
        val nowMs = System.currentTimeMillis()
        val pkg = usageTracker.getLatestForegroundPackage(nowMs) ?: currentForegroundPkg
        if (pkg.isNullOrEmpty() || isIgnoredPackage(pkg)) return

        evaluateForegroundApp(pkg, bypassDebounce = true)
    }

    private fun handleConfigSynced() {
        mainHandler.post {
            if (!isScreenUnlockedAndInteractive() || overlayController.isShowing) return@post
            val pkg = currentForegroundPkg ?: usageTracker.getLatestForegroundPackage()
            if (pkg.isNullOrEmpty() || isIgnoredPackage(pkg)) {
                cancelMidSessionTimer()
                return@post
            }
            evaluateForegroundApp(pkg, bypassDebounce = true)
        }
    }

    private fun evaluateForegroundApp(pkg: String, bypassDebounce: Boolean) {
        cancelMidSessionTimer()
        currentForegroundPkg = pkg

        val nowMs = System.currentTimeMillis()
        usageTracker.onForegroundApp(pkg, nowMs)

        // 1. Strict Pomodoro focus window check takes precedence during focus phases.
        if (store.isBlockedByStrictFocus(pkg, nowMs)) {
            triggerBlock(pkg, BlockReason.STRICT_FOCUS, nowMs, bypassDebounce)
            return
        }

        // 2. Daily limit check (respecting local midnight rollover & pending changes).
        val effectiveLimit = store.getEffectiveLimit(pkg, nowMs) ?: return
        if (effectiveLimit.limitMinutes <= 0) return

        val limitMs = effectiveLimit.limitMinutes * 60_000L
        val usedMs = usageTracker.getTodayUsageMs(pkg, nowMs)
        val remainingMs = limitMs - usedMs

        if (remainingMs <= 0L) {
            triggerBlock(pkg, BlockReason.DAILY_LIMIT, nowMs, bypassDebounce)
        } else {
            scheduleMidSessionTimer(pkg, remainingMs)
        }
    }

    private fun scheduleMidSessionTimer(pkg: String, remainingMs: Long) {
        cancelMidSessionTimer()
        val runnable = Runnable {
            if (!isScreenUnlockedAndInteractive()) return@Runnable
            if (currentForegroundPkg != pkg || overlayController.isShowing) return@Runnable

            val nowMs = System.currentTimeMillis()
            val effectiveLimit = store.getEffectiveLimit(pkg, nowMs) ?: return@Runnable
            if (effectiveLimit.limitMinutes <= 0) return@Runnable

            val limitMs = effectiveLimit.limitMinutes * 60_000L
            val usedMs = usageTracker.getTodayUsageMs(pkg, nowMs)
            val stillRemainingMs = limitMs - usedMs

            if (stillRemainingMs <= 250L) {
                // Exact-second mid-session limit hit counts as attempt #1 (PRD section 4.1).
                triggerBlock(pkg, BlockReason.DAILY_LIMIT, nowMs, bypassDebounce = true)
            } else {
                scheduleMidSessionTimer(pkg, stillRemainingMs)
            }
        }
        midSessionRunnable = runnable
        mainHandler.postDelayed(runnable, remainingMs.coerceAtLeast(250L))
    }

    private fun cancelMidSessionTimer() {
        midSessionRunnable?.let { mainHandler.removeCallbacks(it) }
        midSessionRunnable = null
    }

    private fun triggerBlock(
        pkg: String,
        reason: BlockReason,
        nowMs: Long,
        bypassDebounce: Boolean,
    ) {
        if (!bypassDebounce && pkg == lastBlockedPkg && (nowMs - lastBlockedMs) < BLOCK_DEBOUNCE_MS) {
            return
        }
        lastBlockedPkg = pkg
        lastBlockedMs = nowMs

        cancelMidSessionTimer()
        currentForegroundPkg = null
        val attemptsToday = store.incrementAttempts(pkg, nowMs)

        // Always close the blocked app by returning to the Home screen first.
        performGlobalAction(GLOBAL_ACTION_HOME)

        val appLabel = resolveAppLabel(pkg)

        if (attemptsToday <= 1) {
            // 1st block today: close + toast reflecting exact reason. No meme.
            val message = when (reason) {
                BlockReason.STRICT_FOCUS -> "$appLabel blocked during focus"
                BlockReason.DAILY_LIMIT, BlockReason.UNINSTALL_ATTEMPT -> "$appLabel limit reached"
            }
            mainHandler.post {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            }
            return
        }

        // 2nd+ block today: close + play unskippable meme from native shuffle bag.
        val meme = memeSelector.pickMeme(
            reason = reason,
            attemptsTodayForApp = attemptsToday,
            nowMs = nowMs,
        )
        if (meme != null) {
            overlayController.show(meme)
        } else {
            mainHandler.post {
                Toast.makeText(applicationContext, "$appLabel blocked", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isIgnoredPackage(pkg: String): Boolean {
        if (pkg == applicationContext.packageName) return true
        if (pkg == "com.android.systemui" || pkg == "android") return true
        if (launcherPackages.contains(pkg)) return true
        if (imePackages.contains(pkg)) return true
        return false
    }

    private fun refreshIgnoredSystemPackages() {
        // Enabled keyboard (IME) packages so typing never resets or triggers blocking.
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imePackages = try {
            imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }

        // Installed Home launcher packages.
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        launcherPackages = try {
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    homeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun isScreenUnlockedAndInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val interactive = pm?.isInteractive ?: true
        val locked = km?.isKeyguardLocked ?: false
        return interactive && !locked
    }

    private fun resolveAppLabel(pkg: String): String {
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    pkg,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, 0)
            }
            packageManager.getApplicationLabel(appInfo)?.toString()?.trim()?.ifEmpty { pkg } ?: pkg
        } catch (_: Exception) {
            pkg
        }
    }
}
