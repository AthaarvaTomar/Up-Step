package com.athaarva.focusmeme

import android.content.Context
import java.io.File
import java.util.Calendar
import java.util.Locale

enum class BlockReason {
    DAILY_LIMIT,
    STRICT_FOCUS,
    UNINSTALL_ATTEMPT,
}

data class MemeMedia(
    val section: String,
    val id: String,
    val isBundledAsset: Boolean,
    val path: String,
    val isVideo: Boolean,
)

/**
 * Picks memes completely locally using PRD section 4.4 priority and persisted
 * per-section shuffle bags (no repeats until a section's bag is exhausted).
 *
 * Synced files live in `context.filesDir/memes/<section>/` (matching Dart's
 * `getApplicationSupportDirectory()/memes/<section>/`). Bundled fallback memes
 * live in Android `assets/memes/<section>/`.
 */
class MemeSelector(
    private val context: Context,
    private val store: BlockerStore,
) {
    companion object {
        const val SECTION_UNINSTALL = "uninstall_attempt"
        const val SECTION_REPEAT_OFFENDER = "repeat_offender"
        const val SECTION_STRICT_SESSION = "strict_session"
        const val SECTION_LATE_NIGHT = "late_night"
        const val SECTION_LIMIT_HIT = "limit_hit"
        const val SECTION_GENERAL = "general"

        private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "3gp")
        private val IMAGE_EXTENSIONS = setOf("gif", "png", "jpg", "jpeg", "webp")
    }

    private val appContext = context.applicationContext

    /**
     * Section priority (first match wins, PRD section 4.4):
     * `uninstall_attempt` > `repeat_offender` (5+ today for same app) >
     * `strict_session` > `late_night` (00:00-05:00) > `limit_hit` > `general`.
     */
    fun resolveSection(
        reason: BlockReason,
        attemptsTodayForApp: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        if (reason == BlockReason.UNINSTALL_ATTEMPT) return SECTION_UNINSTALL
        if (attemptsTodayForApp >= 5) return SECTION_REPEAT_OFFENDER
        if (reason == BlockReason.STRICT_FOCUS) return SECTION_STRICT_SESSION

        val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
        if (hour in 0..4) return SECTION_LATE_NIGHT

        if (reason == BlockReason.DAILY_LIMIT) return SECTION_LIMIT_HIT
        return SECTION_GENERAL
    }

    /**
     * Picks the next meme from the resolved section's shuffle bag.
     *
     * Fallback chain (PRD section 4.4):
     * synced target section -> synced `general` -> bundled target section -> bundled `general`.
     */
    fun pickMeme(
        reason: BlockReason,
        attemptsTodayForApp: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): MemeMedia? {
        val targetSection = resolveSection(reason, attemptsTodayForApp, nowMs)

        // 1. Synced files in target section (populated by M6 sync).
        pickFromPool(
            section = targetSection,
            poolTag = "synced_$targetSection",
            candidates = listSyncedMemes(targetSection),
        )?.let { return it }

        // 2. Synced files in `general` fallback.
        if (targetSection != SECTION_GENERAL) {
            pickFromPool(
                section = SECTION_GENERAL,
                poolTag = "synced_$SECTION_GENERAL",
                candidates = listSyncedMemes(SECTION_GENERAL),
            )?.let { return it }
        }

        // 3. Bundled Android assets in target section.
        pickFromPool(
            section = targetSection,
            poolTag = "bundled_$targetSection",
            candidates = listBundledMemes(targetSection),
        )?.let { return it }

        // 4. Bundled Android assets in `general` fallback.
        if (targetSection != SECTION_GENERAL) {
            return pickFromPool(
                section = SECTION_GENERAL,
                poolTag = "bundled_$SECTION_GENERAL",
                candidates = listBundledMemes(SECTION_GENERAL),
            )
        }

        return null
    }

    private fun pickFromPool(
        section: String,
        poolTag: String,
        candidates: List<MemeMedia>,
    ): MemeMedia? {
        if (candidates.isEmpty()) return null

        val byId = candidates.associateBy { it.id }
        val bag = store.getShuffleBag(poolTag)
            .filter { byId.containsKey(it) }
            .toMutableList()

        if (bag.isEmpty()) {
            val fresh = candidates.map { it.id }.shuffled().toMutableList()
            val lastPlayed = store.getLastPlayedMeme(poolTag)
            // Avoid back-to-back repeats across reshuffle boundaries when >1 meme exists.
            if (fresh.size > 1 && fresh.first() == lastPlayed) {
                val swapIndex = 1
                val first = fresh[0]
                fresh[0] = fresh[swapIndex]
                fresh[swapIndex] = first
            }
            bag.addAll(fresh)
        }

        val pickedId = bag.removeAt(0)
        store.saveShuffleBag(bagKey = poolTag, remaining = bag, lastPlayed = pickedId)
        return byId[pickedId]
    }

    private fun listSyncedMemes(section: String): List<MemeMedia> {
        val dir = File(appContext.filesDir, "memes/$section")
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.length() > 0L }
            .mapNotNull { file ->
                val ext = file.extension.lowercase(Locale.US)
                val isVideo = when {
                    VIDEO_EXTENSIONS.contains(ext) -> true
                    IMAGE_EXTENSIONS.contains(ext) -> false
                    else -> return@mapNotNull null
                }
                MemeMedia(
                    section = section,
                    id = "file:${file.name}",
                    isBundledAsset = false,
                    path = file.absolutePath,
                    isVideo = isVideo,
                )
            }
            .sortedBy { it.id }
    }

    private fun listBundledMemes(section: String): List<MemeMedia> {
        val assetDir = "memes/$section"
        val names = try {
            appContext.assets.list(assetDir)
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        return names
            .mapNotNull { fileName ->
                val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
                val isVideo = when {
                    VIDEO_EXTENSIONS.contains(ext) -> true
                    IMAGE_EXTENSIONS.contains(ext) -> false
                    else -> return@mapNotNull null
                }
                MemeMedia(
                    section = section,
                    id = "asset:$fileName",
                    isBundledAsset = true,
                    path = "$assetDir/$fileName",
                    isVideo = isVideo,
                )
            }
            .sortedBy { it.id }
    }
}
