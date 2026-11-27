package com.athaarva.focusmeme

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class StoredLimit(
    val packageName: String,
    val limitMinutes: Int,
    val focusOnly: Boolean,
    val pendingMinutes: Int? = null,
    val pendingRemove: Boolean? = null,
    val pendingFrom: String? = null,
)

data class BlockerSettings(
    val farewellMemeOn: Boolean = true,
    val wifiOnlySync: Boolean = true,
)

data class FocusWindow(
    val startMs: Long,
    val endMs: Long,
)

data class StrictSchedule(
    val blockedPackages: Set<String>,
    val focusWindows: List<FocusWindow>,
)

/**
 * Owns FocusMeme's native-only SharedPreferences store (`focusmeme_native_prefs`).
 *
 * Kept strictly separate from Flutter's `shared_preferences` file (PRD section 6).
 * Every daily counter resets automatically via local date key (`yyyy-MM-dd`)
 * rather than background timers so midnight rollover and service restarts stay reliable.
 */
class BlockerStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "focusmeme_native_prefs"
        private const val KEY_LIMITS = "limits_json"
        private const val KEY_STRICT_SCHEDULE = "strict_schedule_json"
        private const val KEY_SETTINGS = "settings_json"
        private const val KEY_ATTEMPTS_DAY = "attempts_day"
        private const val KEY_ATTEMPTS_MAP = "attempts_json"
        private const val KEY_USAGE_DAY = "usage_day"
        private const val KEY_USAGE_LAST_END = "usage_last_end_ms"
        private const val KEY_USAGE_MAP = "usage_map_json"
        private const val KEY_USAGE_ACTIVE_PKG = "usage_active_pkg"
        private const val KEY_USAGE_ACTIVE_START = "usage_active_start_ms"
        private const val PREFIX_BAG = "shuffle_bag_"
        private const val PREFIX_LAST = "shuffle_last_"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun todayKey(nowMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    fun startOfDayMillis(nowMs: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /**
     * Persists the Dart `sync` payload atomically into native storage.
     */
    @Synchronized
    fun saveSyncPayload(
        limitsRaw: List<*>?,
        strictScheduleRaw: Map<*, *>?,
        settingsRaw: Map<*, *>?,
        hasStrictScheduleKey: Boolean,
    ) {
        val editor = prefs.edit()

        if (limitsRaw != null) {
            val arr = JSONArray()
            for (item in limitsRaw) {
                val map = item as? Map<*, *> ?: continue
                arr.put(toJsonValue(map))
            }
            editor.putString(KEY_LIMITS, arr.toString())
        }

        if (hasStrictScheduleKey) {
            if (strictScheduleRaw == null) {
                editor.remove(KEY_STRICT_SCHEDULE)
            } else {
                editor.putString(KEY_STRICT_SCHEDULE, toJsonValue(strictScheduleRaw).toString())
            }
        }

        if (settingsRaw != null) {
            editor.putString(KEY_SETTINGS, toJsonValue(settingsRaw).toString())
        }

        editor.apply()
    }

    @Synchronized
    fun getAllLimits(): Map<String, StoredLimit> {
        val raw = prefs.getString(KEY_LIMITS, null) ?: return emptyMap()
        val result = LinkedHashMap<String, StoredLimit>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pkg = obj.optString("packageName", "").trim()
                if (pkg.isEmpty()) continue
                result[pkg] = StoredLimit(
                    packageName = pkg,
                    limitMinutes = obj.optInt("limitMinutes", 0).coerceAtLeast(0),
                    focusOnly = obj.optBoolean("focusOnly", false),
                    pendingMinutes = if (obj.has("pendingMinutes") && !obj.isNull("pendingMinutes")) {
                        obj.optInt("pendingMinutes")
                    } else {
                        null
                    },
                    pendingRemove = if (obj.has("pendingRemove") && !obj.isNull("pendingRemove")) {
                        obj.optBoolean("pendingRemove")
                    } else {
                        null
                    },
                    pendingFrom = obj.optString("pendingFrom", "").ifEmpty { null },
                )
            }
        } catch (_: Exception) {
            return emptyMap()
        }
        return result
    }

    /**
     * Resolves the effective limit for [packageName] on [nowMs]'s local date.
     *
     * Promotes queued `pendingRemove` / `pendingMinutes` once `today >= pendingFrom`
     * so midnight rollover works even if Flutter has not been reopened.
     */
    @Synchronized
    fun getEffectiveLimit(
        packageName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): StoredLimit? {
        val raw = getAllLimits()[packageName] ?: return null
        val today = todayKey(nowMs)
        val pendingFrom = raw.pendingFrom
        if (pendingFrom != null && today >= pendingFrom) {
            if (raw.pendingRemove == true) {
                return null
            }
            if (raw.pendingMinutes != null) {
                return raw.copy(
                    limitMinutes = raw.pendingMinutes.coerceAtLeast(0),
                    pendingMinutes = null,
                    pendingRemove = null,
                    pendingFrom = null,
                )
            }
        }
        return raw
    }

    @Synchronized
    fun getSettings(): BlockerSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return BlockerSettings()
        return try {
            val obj = JSONObject(raw)
            BlockerSettings(
                farewellMemeOn = obj.optBoolean("farewellMemeOn", true),
                wifiOnlySync = obj.optBoolean("wifiOnlySync", true),
            )
        } catch (_: Exception) {
            BlockerSettings()
        }
    }

    @Synchronized
    fun getStrictScheduleMap(): Map<String, Any?>? {
        val raw = prefs.getString(KEY_STRICT_SCHEDULE, null) ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            fromJsonValue(JSONObject(raw)) as? Map<String, Any?>
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun getStrictSchedule(): StrictSchedule? {
        val raw = prefs.getString(KEY_STRICT_SCHEDULE, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val blocked = HashSet<String>()
            val pkgArr = obj.optJSONArray("blockedPackages")
            if (pkgArr != null) {
                for (i in 0 until pkgArr.length()) {
                    val pkg = pkgArr.optString(i, "").trim()
                    if (pkg.isNotEmpty()) blocked.add(pkg)
                }
            }
            val windows = mutableListOf<FocusWindow>()
            val winArr = obj.optJSONArray("focusWindows")
            if (winArr != null) {
                for (i in 0 until winArr.length()) {
                    val w = winArr.optJSONObject(i) ?: continue
                    val startMs = w.optLong("startMs", 0L)
                    val endMs = w.optLong("endMs", 0L)
                    if (endMs > startMs) {
                        windows.add(FocusWindow(startMs = startMs, endMs = endMs))
                    }
                }
            }
            StrictSchedule(blockedPackages = blocked, focusWindows = windows)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns `true` when [nowMs] falls inside an active strict Pomodoro focus window
     * and [packageName] is restricted during focus.
     */
    @Synchronized
    fun isBlockedByStrictFocus(
        packageName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val schedule = getStrictSchedule() ?: return false
        val inFocusWindow = schedule.focusWindows.any { nowMs in it.startMs until it.endMs }
        if (!inFocusWindow) return false

        // Prefer the session-start package snapshot so turning off `focusOnly`
        // mid-session cannot act as a loophole (PRD section 4.2).
        if (schedule.blockedPackages.isNotEmpty()) {
            return schedule.blockedPackages.contains(packageName)
        }

        val limit = getEffectiveLimit(packageName, nowMs) ?: return false
        return limit.focusOnly || limit.limitMinutes > 0
    }

    @Synchronized
    fun getAttemptsMap(nowMs: Long = System.currentTimeMillis()): MutableMap<String, Int> {
        val today = todayKey(nowMs)
        val savedDay = prefs.getString(KEY_ATTEMPTS_DAY, null)
        if (savedDay != today) {
            prefs.edit()
                .putString(KEY_ATTEMPTS_DAY, today)
                .putString(KEY_ATTEMPTS_MAP, "{}")
                .apply()
            return LinkedHashMap()
        }
        val raw = prefs.getString(KEY_ATTEMPTS_MAP, null) ?: return LinkedHashMap()
        val map = LinkedHashMap<String, Int>()
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optInt(k, 0)
            }
        } catch (_: Exception) {
            return LinkedHashMap()
        }
        return map
    }

    @Synchronized
    fun incrementAttempts(
        packageName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val today = todayKey(nowMs)
        val map = getAttemptsMap(nowMs)
        val nextCount = (map[packageName] ?: 0) + 1
        map[packageName] = nextCount

        val obj = JSONObject()
        for ((k, v) in map) {
            obj.put(k, v)
        }
        prefs.edit()
            .putString(KEY_ATTEMPTS_DAY, today)
            .putString(KEY_ATTEMPTS_MAP, obj.toString())
            .apply()
        return nextCount
    }

    @Synchronized
    fun getUsageDay(): String? = prefs.getString(KEY_USAGE_DAY, null)

    @Synchronized
    fun getUsageLastEndMs(): Long = prefs.getLong(KEY_USAGE_LAST_END, 0L)

    @Synchronized
    fun getUsageActivePackage(): String? = prefs.getString(KEY_USAGE_ACTIVE_PKG, null)

    @Synchronized
    fun getUsageActiveStartMs(): Long = prefs.getLong(KEY_USAGE_ACTIVE_START, 0L)

    @Synchronized
    fun getUsageMap(): MutableMap<String, Long> {
        val raw = prefs.getString(KEY_USAGE_MAP, null) ?: return LinkedHashMap()
        val map = LinkedHashMap<String, Long>()
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optLong(k, 0L).coerceAtLeast(0L)
            }
        } catch (_: Exception) {
            return LinkedHashMap()
        }
        return map
    }

    @Synchronized
    fun saveUsageState(
        dayKey: String,
        lastEndMs: Long,
        usageMap: Map<String, Long>,
        activePkg: String?,
        activeStartMs: Long,
    ) {
        val obj = JSONObject()
        for ((k, v) in usageMap) {
            obj.put(k, v)
        }
        val editor = prefs.edit()
            .putString(KEY_USAGE_DAY, dayKey)
            .putLong(KEY_USAGE_LAST_END, lastEndMs)
            .putString(KEY_USAGE_MAP, obj.toString())
            .putLong(KEY_USAGE_ACTIVE_START, activeStartMs)
        if (activePkg == null) {
            editor.remove(KEY_USAGE_ACTIVE_PKG)
        } else {
            editor.putString(KEY_USAGE_ACTIVE_PKG, activePkg)
        }
        editor.apply()
    }

    @Synchronized
    fun getShuffleBag(bagKey: String): MutableList<String> {
        val raw = prefs.getString(PREFIX_BAG + bagKey, null) ?: return mutableListOf()
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "")
                if (item.isNotEmpty()) list.add(item)
            }
        } catch (_: Exception) {
            return mutableListOf()
        }
        return list
    }

    @Synchronized
    fun getLastPlayedMeme(bagKey: String): String? =
        prefs.getString(PREFIX_LAST + bagKey, null)

    @Synchronized
    fun saveShuffleBag(bagKey: String, remaining: List<String>, lastPlayed: String?) {
        val arr = JSONArray()
        for (item in remaining) {
            arr.put(item)
        }
        val editor = prefs.edit().putString(PREFIX_BAG + bagKey, arr.toString())
        if (lastPlayed == null) {
            editor.remove(PREFIX_LAST + bagKey)
        } else {
            editor.putString(PREFIX_LAST + bagKey, lastPlayed)
        }
        editor.apply()
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value) {
                    if (k != null) {
                        obj.put(k.toString(), toJsonValue(v))
                    }
                }
                obj
            }
            is List<*> -> {
                val arr = JSONArray()
                for (item in value) {
                    arr.put(toJsonValue(item))
                }
                arr
            }
            is Boolean, is Int, is Long, is Double, is String -> value
            is Number -> value.toDouble()
            else -> value.toString()
        }
    }

    private fun fromJsonValue(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                val map = LinkedHashMap<String, Any?>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = fromJsonValue(value.opt(k))
                }
                map
            }
            is JSONArray -> {
                val list = mutableListOf<Any?>()
                for (i in 0 until value.length()) {
                    list.add(fromJsonValue(value.opt(i)))
                }
                list
            }
            else -> value
        }
    }
}
