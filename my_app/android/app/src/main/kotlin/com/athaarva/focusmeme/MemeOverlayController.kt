package com.athaarva.focusmeme

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.VideoView
import java.io.File
import java.io.FileOutputStream

/**
 * Renders an unskippable, full-screen pure-black meme overlay using
 * `TYPE_ACCESSIBILITY_OVERLAY` (requires no "draw over other apps" permission).
 *
 * Uses Android native `VideoView` for videos (hard ~15 s cap) and `ImageDecoder`
 * (`AnimatedImageDrawable` on API 28+) / `BitmapFactory` for GIFs & images.
 */
class MemeOverlayController(private val serviceContext: Context) {
    companion object {
        private const val MAX_MEME_DURATION_MS = 15_000L
        private const val IMAGE_DISPLAY_DURATION_MS = 5_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audioManager =
        serviceContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var rootView: View? = null
    private var activeVideoView: VideoView? = null
    private var activeAnimatedDrawable: Drawable? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var dismissRunnable: Runnable? = null

    val isShowing: Boolean
        get() = rootView != null

    fun show(meme: MemeMedia, onDismissed: (() -> Unit)? = null) {
        mainHandler.post {
            showOnMainThread(meme, onDismissed)
        }
    }

    fun dismiss() {
        mainHandler.post {
            dismissOnMainThread(null)
        }
    }

    private fun showOnMainThread(meme: MemeMedia, onDismissed: (() -> Unit)?) {
        if (rootView != null) return

        val container = object : FrameLayout(serviceContext) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                // Consume Back/Home hardware keys while the unskippable meme plays.
                return true
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
            isClickable = true
            requestFocus()
        }

        val mediaView = if (meme.isVideo) {
            buildVideoView(meme, onDismissed)
        } else {
            buildImageView(meme)
        }

        if (mediaView == null) {
            onDismissed?.invoke()
            return
        }

        val childParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        )
        container.addView(mediaView, childParams)

        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(container, params)
            rootView = container
        } catch (_: Exception) {
            cleanupMediaOnly()
            onDismissed?.invoke()
            return
        }

        val maxDurationMs = if (meme.isVideo) MAX_MEME_DURATION_MS else IMAGE_DISPLAY_DURATION_MS
        val runnable = Runnable { dismissOnMainThread(onDismissed) }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, maxDurationMs)
    }

    private fun buildVideoView(meme: MemeMedia, onDismissed: (() -> Unit)?): View? {
        val filePath = if (meme.isBundledAsset) {
            copyAssetToCache(meme.path) ?: return null
        } else {
            meme.path
        }

        requestTransientAudioFocus()

        return VideoView(serviceContext).apply {
            activeVideoView = this
            setVideoURI(Uri.fromFile(File(filePath)))
            setOnPreparedListener { mp ->
                mp.isLooping = false
                start()
            }
            setOnCompletionListener {
                dismissOnMainThread(onDismissed)
            }
            setOnErrorListener { _, _, _ ->
                dismissOnMainThread(onDismissed)
                true
            }
        }
    }

    private fun buildImageView(meme: MemeMedia): View? {
        val imageView = ImageView(serviceContext).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = if (meme.isBundledAsset) {
                    ImageDecoder.createSource(serviceContext.assets, meme.path)
                } else {
                    ImageDecoder.createSource(File(meme.path))
                }
                val drawable = ImageDecoder.decodeDrawable(source)
                imageView.setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) {
                    activeAnimatedDrawable = drawable
                    drawable.start()
                }
            } else {
                val bitmap = if (meme.isBundledAsset) {
                    serviceContext.assets.open(meme.path).use { BitmapFactory.decodeStream(it) }
                } else {
                    BitmapFactory.decodeFile(meme.path)
                }
                if (bitmap == null) return null
                imageView.setImageBitmap(bitmap)
            }
            imageView
        } catch (_: Exception) {
            null
        }
    }

    private fun copyAssetToCache(assetPath: String): String? {
        return try {
            val ext = assetPath.substringAfterLast('.', "mp4")
            val outFile = File(serviceContext.cacheDir, "active_meme_video.$ext")
            serviceContext.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun requestTransientAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                )
            }
        } catch (_: Exception) {
            // Non-fatal if audio focus is unavailable.
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
            // Ignore cleanup errors.
        }
    }

    private fun cleanupMediaOnly() {
        try {
            activeVideoView?.stopPlayback()
        } catch (_: Exception) {
        }
        activeVideoView = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                (activeAnimatedDrawable as? AnimatedImageDrawable)?.stop()
            } catch (_: Exception) {
            }
        }
        activeAnimatedDrawable = null
        abandonAudioFocus()
    }

    private fun dismissOnMainThread(onDismissed: (() -> Unit)?) {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null

        cleanupMediaOnly()

        val view = rootView
        rootView = null
        if (view != null) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {
                }
            }
        }

        onDismissed?.invoke()
    }
}
