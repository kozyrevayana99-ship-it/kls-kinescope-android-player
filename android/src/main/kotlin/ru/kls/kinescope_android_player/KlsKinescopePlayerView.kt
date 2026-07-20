package ru.kls.kinescope_android_player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.lifecycle.LifecycleOwner
import io.flutter.plugin.platform.PlatformView
import io.kinescope.sdk.models.players.syncLegacyChromeFlags
import io.kinescope.sdk.player.KinescopePlayerOptions
import io.kinescope.sdk.player.KinescopeVideoPlayer
import io.kinescope.sdk.view.KinescopePlayerView

class KlsKinescopePlayerView(
    context: Context,
    params: Map<*, *>
) : PlatformView {

    companion object {
        private const val TAG = "KlsKinescopePlayer"
    }

    private val activity: Activity? = context.findActivity()
    private val videoId: String =
        (params["videoId"] as? String).orEmpty().trim()

    private val options = KinescopePlayerOptions().apply {
        autoplay = params.booleanValue("autoplay", false)
        muted = params.booleanValue("muted", false)
        loop = params.booleanValue("loop", false)
        controls = params.booleanValue("controls", true)
        playsinline = true
        fullscreen = params.booleanValue("fullscreen", true)
        pictureInPicture =
            params.booleanValue("pictureInPicture", false)
        accentColor =
            (params["accentColor"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: "#E9C18A"

        syncLegacyChromeFlags()
    }

    // SurfaceView is the default and is intentionally retained for
    // Widevine-protected playback.
    private val playerView = KinescopePlayerView(
        context,
        useTextureSurface = false
    ).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private val player = KinescopeVideoPlayer(context, options)
    private var lifecycleBound = false

    init {
        // Block screenshots and screen recording of the whole Android window
        // while the protected player is on screen.
        activity?.window?.addFlags(
            WindowManager.LayoutParams.FLAG_SECURE
        )

        playerView.setPlayer(player)
        playerView.applyTemplateOptions()

        val lifecycleOwner = activity as? LifecycleOwner
        if (lifecycleOwner != null) {
            player.bindLifecycle(lifecycleOwner.lifecycle)
            lifecycleBound = true
        }

        if (videoId.isNotEmpty()) {
            player.loadVideo(
                videoId,
                onSuccess = {
                    Log.d(TAG, "Video loaded: $videoId")
                },
                onFailed = { error ->
                    Log.e(
                        TAG,
                        "Unable to load video: $videoId",
                        error
                    )
                }
            )
        } else {
            Log.e(TAG, "videoId is empty")
        }
    }

    override fun getView(): View = playerView

    override fun dispose() {
        try {
            if (lifecycleBound) {
                player.unbindLifecycle()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Lifecycle unbind failed", error)
        }

        try {
            player.release()
        } catch (error: Throwable) {
            Log.w(TAG, "Player release failed", error)
        }

        activity?.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }
}

private fun Map<*, *>.booleanValue(
    key: String,
    fallback: Boolean
): Boolean = this[key] as? Boolean ?: fallback

private fun Context.findActivity(): Activity? {
    var current: Context? = this

    while (current is ContextWrapper) {
        if (current is Activity) {
            return current
        }
        current = current.baseContext
    }

    return current as? Activity
}
