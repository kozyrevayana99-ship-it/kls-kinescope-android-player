package ru.kls.kinescope_android_player

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
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

    private val fullscreenEnabled: Boolean =
        params.booleanValue("fullscreen", true)

    private val options = KinescopePlayerOptions().apply {
        autoplay = params.booleanValue("autoplay", false)
        muted = params.booleanValue("muted", false)
        loop = params.booleanValue("loop", false)
        controls = params.booleanValue("controls", true)
        playsinline = true
        fullscreen = fullscreenEnabled
        pictureInPicture =
            params.booleanValue("pictureInPicture", false)
        accentColor =
            (params["accentColor"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: "#E9C18A"

        syncLegacyChromeFlags()
    }

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

    private val fullscreenPlayerView = KinescopePlayerView(
        context,
        useTextureSurface = false
    ).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private val player = KinescopeVideoPlayer(context, options)

    private var lifecycleBound = false
    private var fullscreenDialog: Dialog? = null
    private var isFullscreen = false
    private var disposed = false

    init {
        activity?.window?.addFlags(
            WindowManager.LayoutParams.FLAG_SECURE
        )

        playerView.setPlayer(player)
        playerView.applyTemplateOptions()

        fullscreenPlayerView.setPlayer(player)
        fullscreenPlayerView.applyTemplateOptions()

        playerView.onFullscreenButtonCallback = {
            enterFullscreen()
        }

        fullscreenPlayerView.onFullscreenButtonCallback = {
            exitFullscreen()
        }

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

    private fun enterFullscreen() {
        if (disposed || !fullscreenEnabled || isFullscreen) {
            return
        }

        val hostActivity = activity
        if (hostActivity == null) {
            Log.e(TAG, "Cannot enter fullscreen: Activity not found")
            return
        }

        isFullscreen = true

        val dialog = Dialog(
            hostActivity,
            android.R.style.Theme_Black_NoTitleBar_Fullscreen
        )

        fullscreenDialog = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = FrameLayout(hostActivity).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        (fullscreenPlayerView.parent as? ViewGroup)
            ?.removeView(fullscreenPlayerView)

        root.addView(
            fullscreenPlayerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        dialog.setContentView(root)

        dialog.setOnShowListener {
            dialog.window?.let { window ->
                window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                window.setBackgroundDrawableResource(
                    android.R.color.black
                )
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SECURE
                )
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                )
                hideSystemBars(window)
            }

            try {
                KinescopePlayerView.switchTargetView(
                    playerView,
                    fullscreenPlayerView,
                    player
                )
            } catch (error: Throwable) {
                Log.e(
                    TAG,
                    "Unable to switch player to fullscreen",
                    error
                )
                isFullscreen = false
                dialog.dismiss()
            }
        }

        dialog.setOnCancelListener {
            restoreInlinePlayer()
        }

        dialog.setOnDismissListener {
            restoreInlinePlayer()
        }

        try {
            dialog.show()
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "Unable to show fullscreen dialog",
                error
            )
            isFullscreen = false
            fullscreenDialog = null
        }
    }

    private fun exitFullscreen() {
        if (!isFullscreen) {
            return
        }

        val dialog = fullscreenDialog

        if (dialog != null && dialog.isShowing) {
            dialog.dismiss()
        } else {
            restoreInlinePlayer()
        }
    }

    private fun restoreInlinePlayer() {
        if (!isFullscreen) {
            return
        }

        isFullscreen = false

        try {
            KinescopePlayerView.switchTargetView(
                fullscreenPlayerView,
                playerView,
                player
            )
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "Unable to switch player back inline",
                error
            )
        }

        (fullscreenPlayerView.parent as? ViewGroup)
            ?.removeView(fullscreenPlayerView)

        fullscreenDialog = null

        activity?.window?.let { window ->
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            showSystemBars(window)
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun dispose() {
        if (disposed) {
            return
        }

        disposed = true

        try {
            if (isFullscreen) {
                restoreInlinePlayer()
            }

            fullscreenDialog?.setOnDismissListener(null)
            fullscreenDialog?.dismiss()
            fullscreenDialog = null
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "Fullscreen cleanup failed",
                error
            )
        }

        try {
            if (lifecycleBound) {
                player.unbindLifecycle()
            }
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "Lifecycle unbind failed",
                error
            )
        }

        try {
            player.release()
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "Player release failed",
                error
            )
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
}
