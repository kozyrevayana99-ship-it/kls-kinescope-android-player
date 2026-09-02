package ru.kls.kinescope_android_player

import android.app.Activity
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player

import io.flutter.plugin.platform.PlatformView

import io.kinescope.sdk.models.players.syncLegacyChromeFlags
import io.kinescope.sdk.player.KinescopePictureInPicture
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

    // ============================================================
    // CONTEXT / ACTIVITY
    // ============================================================

    private val appContext: Context =
        context.applicationContext

    private val activity: Activity? =
        context.findActivity()

    // ============================================================
    // INPUT PARAMS
    // ============================================================

    private val videoId: String =
        (params["videoId"] as? String)
            .orEmpty()
            .trim()

    private val fullscreenEnabled: Boolean =
        params.booleanValue(
            "fullscreen",
            true
        )

    private val pictureInPictureEnabled: Boolean =
        params.booleanValue(
            "pictureInPicture",
            true
        )

    /**
     * Фоновое воспроизведение.
     *
     * По умолчанию включаем.
     *
     * Это означает:
     * - при блокировке телефона звук продолжает играть;
     * - при уходе Activity в background Kinescope подключает
     *   свой MediaSession / PlaybackService;
     * - после возврата не создаётся новый плеер с нуля.
     */
    private val backgroundPlaybackEnabled: Boolean =
        params.booleanValue(
            "backgroundPlayback",
            true
        )

    // ============================================================
    // PLAYER OPTIONS
    // ============================================================

    private val options =
        KinescopePlayerOptions().apply {

            autoplay =
                params.booleanValue(
                    "autoplay",
                    false
                )

            muted =
                params.booleanValue(
                    "muted",
                    false
                )

            loop =
                params.booleanValue(
                    "loop",
                    false
                )

            controls =
                params.booleanValue(
                    "controls",
                    true
                )

            playsinline = true

            fullscreen =
                fullscreenEnabled

            pictureInPicture =
                pictureInPictureEnabled

            backgroundPlaybackAllowed =
                backgroundPlaybackEnabled

            accentColor =
                (params["accentColor"] as? String)
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "#E9C18A"

            syncLegacyChromeFlags()
        }

    // ============================================================
    // INLINE PLAYER VIEW
    //
    // ВАЖНО:
    // useTextureSurface = false ОСТАВЛЯЕМ.
    //
    // Для Widevine / DRM нужен SurfaceView.
    // ============================================================

    private val playerView =
        KinescopePlayerView(
            context,
            useTextureSurface = false
        ).apply {

            setBackgroundColor(
                Color.BLACK
            )

            layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
        }

    // ============================================================
    // FULLSCREEN PLAYER VIEW
    //
    // Тоже SurfaceView — DRM не ломаем.
    // ============================================================

    private val fullscreenPlayerView =
        KinescopePlayerView(
            context,
            useTextureSurface = false
        ).apply {

            setBackgroundColor(
                Color.BLACK
            )

            layoutParams =
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
        }

    // ============================================================
    // PLAYER
    // ============================================================

    private val player =
        KinescopeVideoPlayer(
            context,
            options
        )

    // ============================================================
    // STATE
    // ============================================================

    private var lifecycleBound = false

    private var fullscreenDialog: Dialog? = null

    private var isFullscreen = false

    private var disposed = false

    private var pipReceiverRegistered = false

    /**
     * Мы скрыли chrome перед входом в PiP.
     */
    private var pipUiPrepared = false

    // ============================================================
    // PLAYER LISTENER
    //
    // Уже сейчас отслеживаем состояние.
    //
    // В следующем файле подключим это к Flutter EventChannel,
    // чтобы отправлять:
    //
    // pause
    // play
    // ended
    // position
    // duration
    // ============================================================

    private val playerListener =
        object : Player.Listener {

            override fun onIsPlayingChanged(
                isPlaying: Boolean
            ) {
                if (disposed) {
                    return
                }

                Log.d(
                    TAG,
                    "isPlaying=$isPlaying " +
                        "position=${currentPositionMs()} " +
                        "duration=${durationMs()}"
                )

                updatePictureInPictureActions()

                updateAutoEnterPictureInPicture(
                    isPlaying
                )
            }

            override fun onPlaybackStateChanged(
                playbackState: Int
            ) {
                if (disposed) {
                    return
                }

                when (playbackState) {

                    Player.STATE_READY -> {
                        Log.d(
                            TAG,
                            "STATE_READY " +
                                "duration=${durationMs()}"
                        )
                    }

                    Player.STATE_BUFFERING -> {
                        Log.d(
                            TAG,
                            "STATE_BUFFERING"
                        )
                    }

                    Player.STATE_ENDED -> {
                        Log.d(
                            TAG,
                            "STATE_ENDED " +
                                "position=${currentPositionMs()} " +
                                "duration=${durationMs()}"
                        )

                        updateAutoEnterPictureInPicture(
                            false
                        )
                    }

                    Player.STATE_IDLE -> {
                        Log.d(
                            TAG,
                            "STATE_IDLE"
                        )
                    }
                }
            }
        }

    // ============================================================
    // PiP REMOTE PLAY / PAUSE
    //
    // Кнопка в маленьком системном окне Android.
    // ============================================================

    private val pipReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action !=
                    KinescopePictureInPicture.ACTION_PLAY_PAUSE
                ) {
                    return
                }

                togglePlaybackFromPictureInPicture()
            }
        }

    // ============================================================
    // EXTRA LIFECYCLE
    //
    // bindLifecycle самого Kinescope отвечает за background
    // playback / MediaSession.
    //
    // Этот observer нужен нам для восстановления PiP UI.
    // ============================================================

    private val pipLifecycleObserver =
        object : DefaultLifecycleObserver {

            override fun onStart(
                owner: LifecycleOwner
            ) {
                if (disposed) {
                    return
                }

                val hostActivity =
                    activity
                        ?: return

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.N
                ) {
                    if (
                        !hostActivity
                            .isInPictureInPictureMode
                    ) {
                        restorePictureInPictureUi()
                    }
                } else {
                    restorePictureInPictureUi()
                }
            }

            override fun onStop(
                owner: LifecycleOwner
            ) {
                if (disposed) {
                    return
                }

                val hostActivity =
                    activity
                        ?: return

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.N &&
                    hostActivity
                        .isInPictureInPictureMode
                ) {
                    preparePictureInPictureUi()
                    updatePictureInPictureActions()
                }
            }
        }

    // ============================================================
    // INIT
    // ============================================================

    init {

        // --------------------------------------------------------
        // ЗАПРЕТ СКРИНШОТОВ / ЗАПИСИ
        //
        // ОСТАВЛЯЕМ СУЩЕСТВУЮЩЕЕ ПОВЕДЕНИЕ.
        // --------------------------------------------------------

        activity
            ?.window
            ?.addFlags(
                WindowManager
                    .LayoutParams
                    .FLAG_SECURE
            )

        // --------------------------------------------------------
        // ATTACH PLAYER
        // --------------------------------------------------------

        playerView.setPlayer(
            player
        )

        playerView.applyTemplateOptions()

        // --------------------------------------------------------
        // FULLSCREEN
        // --------------------------------------------------------

        playerView
            .onFullscreenButtonCallback = {
                enterFullscreen()
            }

        fullscreenPlayerView
            .onFullscreenButtonCallback = {
                exitFullscreen()
            }

        // --------------------------------------------------------
        // PICTURE IN PICTURE
        // --------------------------------------------------------

        if (
            pictureInPictureEnabled
        ) {

            playerView
                .onPictureInPictureButtonCallback = {
                    enterPictureInPicture()
                }

            fullscreenPlayerView
                .onPictureInPictureButtonCallback = {
                    enterPictureInPicture()
                }
        }

        // --------------------------------------------------------
        // PLAYER STATE LISTENER
        // --------------------------------------------------------

        player.exoPlayer
            ?.addListener(
                playerListener
            )

        // --------------------------------------------------------
        // PiP PLAY / PAUSE RECEIVER
        // --------------------------------------------------------

        registerPictureInPictureReceiver()

        // --------------------------------------------------------
        // LIFECYCLE + BACKGROUND PLAYBACK
        // --------------------------------------------------------

        val lifecycleOwner =
            activity as? LifecycleOwner

        if (
            lifecycleOwner != null
        ) {

            player.bindLifecycle(
                lifecycle =
                    lifecycleOwner.lifecycle,

                isPipActive = {
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.N
                    ) {
                        activity
                            ?.isInPictureInPictureMode
                            == true
                    } else {
                        false
                    }
                },

                backgroundPlaybackAllowed =
                    backgroundPlaybackEnabled,

                // release() делаем сами в dispose().
                // Так Flutter PlatformView полностью контролирует
                // время жизни плеера.
                releaseOnDestroy = false
            )

            lifecycleOwner
                .lifecycle
                .addObserver(
                    pipLifecycleObserver
                )

            lifecycleBound = true
        }

        // --------------------------------------------------------
        // LOAD VIDEO
        // --------------------------------------------------------

        if (
            videoId.isNotEmpty()
        ) {

            player.loadVideo(

                videoId,

                onSuccess = {

                    Log.d(
                        TAG,
                        "Video loaded: $videoId"
                    )

                    // ------------------------------------------------
                    // SurfaceView может появиться раньше,
                    // чем Flutter завершит layout PlatformView.
                    //
                    // Сохраняем твою существующую защиту
                    // от чёрного экрана.
                    // ------------------------------------------------

                    playerView.post {

                        playerView.requestLayout()

                        playerView.invalidate()

                        updateAutoEnterPictureInPicture(
                            isPlaying()
                        )
                    }
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

            Log.e(
                TAG,
                "videoId is empty"
            )
        }
    }

    // ============================================================
    // PLATFORM VIEW
    // ============================================================

    override fun getView(): View =
        playerView

    // ============================================================
    // PUBLIC PLAYER CONTROL
    //
    // Эти методы понадобятся следующему слою Flutter.
    // ============================================================

    fun play() {

        if (disposed) {
            return
        }

        player.play()
    }

    fun pause() {

        if (disposed) {
            return
        }

        player.pause()
    }

    fun seekToPosition(
        positionMs: Long
    ) {
        if (disposed) {
            return
        }

        player.seekToPosition(
            positionMs.coerceAtLeast(
                0L
            )
        )
    }

    fun currentPositionMs(): Long {

        return player
            .playbackPlayer
            ?.currentPosition
            ?.coerceAtLeast(
                0L
            )
            ?: 0L
    }

    fun durationMs(): Long {

        val duration =
            player
                .playbackPlayer
                ?.duration
                ?: 0L

        return if (
            duration > 0L
        ) {
            duration
        } else {
            0L
        }
    }

    fun isPlaying(): Boolean {

        return player
            .playbackPlayer
            ?.isPlaying
            == true
    }

    fun isEnded(): Boolean {

        return player
            .playbackPlayer
            ?.playbackState
            == Player.STATE_ENDED
    }

    // ============================================================
    // PICTURE IN PICTURE
    // ============================================================

    private fun enterPictureInPicture() {

        if (
            disposed ||
            !pictureInPictureEnabled
        ) {
            return
        }

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            Log.d(
                TAG,
                "PiP requires Android 8+"
            )

            return
        }

        val hostActivity =
            activity

        if (
            hostActivity == null
        ) {
            Log.e(
                TAG,
                "Cannot enter PiP: Activity not found"
            )

            return
        }

        if (
            !KinescopePictureInPicture
                .isSupported(
                    hostActivity
                )
        ) {
            Log.d(
                TAG,
                "PiP is not supported on this device"
            )

            return
        }

        // --------------------------------------------------------
        // Если человек был в нашем fullscreen Dialog,
        // сначала аккуратно возвращаем player в inline view.
        //
        // Иначе Android может попытаться уменьшить Dialog,
        // а не основную Activity.
        // --------------------------------------------------------

        if (
            isFullscreen
        ) {
            restoreInlinePlayer()
        }

        preparePictureInPictureUi()

        // Если пользователь нажал PiP во время паузы,
        // продолжаем воспроизведение.
        //
        // Так ведёт себя рекомендуемая PiP session Kinescope.
        if (
            !isPlaying()
        ) {
            player.play()
        }

        playerView.post {

            if (
                disposed
            ) {
                restorePictureInPictureUi()
                return@post
            }

            val anchorView =
                playerView
                    .getPipAnchorView()

            anchorView.post {

                if (
                    disposed
                ) {
                    restorePictureInPictureUi()
                    return@post
                }

                try {

                    val entered =
                        KinescopePictureInPicture
                            .enter(
                                activity =
                                    hostActivity,

                                anchorView =
                                    anchorView,

                                aspectRatio =
                                    KinescopePictureInPicture
                                        .getAspectRatio(
                                            player.exoPlayer
                                        ),

                                exoPlayer =
                                    player.exoPlayer
                            )

                    if (
                        entered
                    ) {
                        Log.d(
                            TAG,
                            "Entered Picture-in-Picture"
                        )

                        updatePictureInPictureActions()

                    } else {

                        Log.w(
                            TAG,
                            "Unable to enter Picture-in-Picture"
                        )

                        restorePictureInPictureUi()
                    }

                } catch (
                    error: Throwable
                ) {

                    Log.e(
                        TAG,
                        "Picture-in-Picture failed",
                        error
                    )

                    restorePictureInPictureUi()
                }
            }
        }
    }

    // ============================================================
    // AUTO PiP
    //
    // Android 12+ умеет автоматически переводить Activity
    // в PiP при Home / swipe home.
    //
    // На Android 8–11 автоматический Home → PiP
    // закончим через ActivityPluginBinding в следующем файле.
    // ============================================================

    private fun updateAutoEnterPictureInPicture(
        isPlaying: Boolean
    ) {
        if (
            disposed ||
            !pictureInPictureEnabled
        ) {
            return
        }

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S
        ) {
            return
        }

        val hostActivity =
            activity
                ?: return

        if (
            !KinescopePictureInPicture
                .isSupported(
                    hostActivity
                )
        ) {
            return
        }

        try {

            val aspectRatio =
                KinescopePictureInPicture
                    .getAspectRatio(
                        player.exoPlayer
                    )

            val pipParams =
                PictureInPictureParams
                    .Builder()
                    .setAspectRatio(
                        aspectRatio
                    )
                    .setAutoEnterEnabled(
                        isPlaying
                    )
                    .setSeamlessResizeEnabled(
                        true
                    )
                    .build()

            hostActivity
                .setPictureInPictureParams(
                    pipParams
                )

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "Unable to configure auto PiP",
                error
            )
        }
    }

    private fun preparePictureInPictureUi() {

        if (
            pipUiPrepared
        ) {
            return
        }

        pipUiPrepared = true

        try {

            playerView
                .prepareForPictureInPicture(
                    true
                )

            fullscreenPlayerView
                .prepareForPictureInPicture(
                    true
                )

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "Unable to prepare PiP UI",
                error
            )
        }
    }

    private fun restorePictureInPictureUi() {

        if (
            !pipUiPrepared
        ) {
            return
        }

        pipUiPrepared = false

        try {

            playerView
                .prepareForPictureInPicture(
                    false
                )

            playerView
                .refreshPlayerChromeAfterPictureInPictureExit()

            fullscreenPlayerView
                .prepareForPictureInPicture(
                    false
                )

            fullscreenPlayerView
                .refreshPlayerChromeAfterPictureInPictureExit()

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "Unable to restore PiP UI",
                error
            )
        }
    }

    private fun updatePictureInPictureActions() {

        if (
            disposed ||
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            return
        }

        val hostActivity =
            activity
                ?: return

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.N &&
            !hostActivity
                .isInPictureInPictureMode
        ) {
            return
        }

        try {

            KinescopePictureInPicture
                .updateActions(
                    hostActivity,
                    player.exoPlayer
                )

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "Unable to update PiP actions",
                error
            )
        }
    }

    private fun togglePlaybackFromPictureInPicture() {

        if (
            disposed
        ) {
            return
        }

        if (
            isPlaying()
        ) {
            player.pause()
        } else {
            player.play()
        }

        updatePictureInPictureActions()
    }

    // ============================================================
    // PiP RECEIVER
    // ============================================================

    private fun registerPictureInPictureReceiver() {

        if (
            pipReceiverRegistered ||
            !pictureInPictureEnabled ||
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            return
        }

        try {

            val filter =
                IntentFilter(
                    KinescopePictureInPicture
                        .ACTION_PLAY_PAUSE
                )

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                appContext.registerReceiver(
                    pipReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )

            } else {

                @Suppress("DEPRECATION")
                appContext.registerReceiver(
                    pipReceiver,
                    filter
                )
            }

            pipReceiverRegistered = true

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "Unable to register PiP receiver",
                error
            )
        }
    }

    private fun unregisterPictureInPictureReceiver() {

        if (
            !pipReceiverRegistered
        ) {
            return
        }

        pipReceiverRegistered = false

        try {

            appContext.unregisterReceiver(
                pipReceiver
            )

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "Unable to unregister PiP receiver",
                error
            )
        }
    }

    // ============================================================
    // FULLSCREEN
    // ============================================================

    private fun enterFullscreen() {

        if (
            disposed ||
            !fullscreenEnabled ||
            isFullscreen
        ) {
            return
        }

        val hostActivity =
            activity

        if (
            hostActivity == null
        ) {

            Log.e(
                TAG,
                "Cannot enter fullscreen: Activity not found"
            )

            return
        }

        isFullscreen = true

        val dialog =
            Dialog(
                hostActivity,
                android.R.style
                    .Theme_Black_NoTitleBar_Fullscreen
            )

        fullscreenDialog =
            dialog

        dialog.requestWindowFeature(
            Window.FEATURE_NO_TITLE
        )

        val root =
            FrameLayout(
                hostActivity
            ).apply {

                setBackgroundColor(
                    Color.BLACK
                )

                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
            }

        (
            fullscreenPlayerView.parent
                as? ViewGroup
            )
            ?.removeView(
                fullscreenPlayerView
            )

        root.addView(
            fullscreenPlayerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        dialog.setContentView(
            root
        )

        dialog.setOnShowListener {

            dialog.window
                ?.let { window ->

                    window.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    window
                        .setBackgroundDrawableResource(
                            android.R.color.black
                        )

                    window.addFlags(
                        WindowManager
                            .LayoutParams
                            .FLAG_SECURE
                    )

                    window.addFlags(
                        WindowManager
                            .LayoutParams
                            .FLAG_FULLSCREEN
                    )

                    hideSystemBars(
                        window
                    )
                }

            try {

                KinescopePlayerView
                    .switchTargetView(
                        playerView,
                        fullscreenPlayerView,
                        player
                    )

                fullscreenPlayerView
                    .applyTemplateOptions()

                fullscreenPlayerView
                    .requestLayout()

                fullscreenPlayerView
                    .invalidate()

                root.requestLayout()

                root.invalidate()

            } catch (
                error: Throwable
            ) {

                Log.e(
                    TAG,
                    "Unable to switch player to fullscreen",
                    error
                )

                isFullscreen =
                    false

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

        } catch (
            error: Throwable
        ) {

            Log.e(
                TAG,
                "Unable to show fullscreen dialog",
                error
            )

            isFullscreen =
                false

            fullscreenDialog =
                null
        }
    }

    private fun exitFullscreen() {

        if (
            !isFullscreen
        ) {
            return
        }

        val dialog =
            fullscreenDialog

        if (
            dialog != null &&
            dialog.isShowing
        ) {

            dialog.dismiss()

        } else {

            restoreInlinePlayer()
        }
    }

    private fun restoreInlinePlayer() {

        if (
            !isFullscreen
        ) {
            return
        }

        isFullscreen =
            false

        try {

            KinescopePlayerView
                .switchTargetView(
                    fullscreenPlayerView,
                    playerView,
                    player
                )

            playerView
                .applyTemplateOptions()

            playerView
                .requestLayout()

            playerView
                .invalidate()

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "Unable to switch player back inline",
                error
            )
        }

        (
            fullscreenPlayerView.parent
                as? ViewGroup
            )
            ?.removeView(
                fullscreenPlayerView
            )

        fullscreenDialog =
            null

        activity
            ?.window
            ?.let { window ->

                window.clearFlags(
                    WindowManager
                        .LayoutParams
                        .FLAG_FULLSCREEN
                )

                showSystemBars(
                    window
                )
            }
    }

    // ============================================================
    // SYSTEM BARS
    // ============================================================

    @Suppress("DEPRECATION")
    private fun hideSystemBars(
        window: Window
    ) {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {

            window
                .insetsController
                ?.hide(
                    android.view.WindowInsets
                        .Type
                        .statusBars() or
                        android.view.WindowInsets
                            .Type
                            .navigationBars()
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
    private fun showSystemBars(
        window: Window
    ) {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {

            window
                .insetsController
                ?.show(
                    android.view.WindowInsets
                        .Type
                        .statusBars() or
                        android.view.WindowInsets
                            .Type
                            .navigationBars()
                )

        } else {

            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ============================================================
    // DISPOSE
    // ============================================================

    override fun dispose() {

        if (
            disposed
        ) {
            return
        }

        disposed =
            true

        // --------------------------------------------------------
        // PiP
        // --------------------------------------------------------

        unregisterPictureInPictureReceiver()

        try {

            player.exoPlayer
                ?.removeListener(
                    playerListener
                )

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "Player listener cleanup failed",
                error
            )
        }

        try {

            val lifecycleOwner =
                activity as? LifecycleOwner

            lifecycleOwner
                ?.lifecycle
                ?.removeObserver(
                    pipLifecycleObserver
                )

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "PiP lifecycle cleanup failed",
                error
            )
        }

        // --------------------------------------------------------
        // FULLSCREEN
        // --------------------------------------------------------

        try {

            if (
                isFullscreen
            ) {
                restoreInlinePlayer()
            }

            fullscreenDialog
                ?.setOnDismissListener(
                    null
                )

            fullscreenDialog
                ?.dismiss()

            fullscreenDialog =
                null

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "Fullscreen cleanup failed",
                error
            )
        }

        // --------------------------------------------------------
        // LIFECYCLE
        // --------------------------------------------------------

        try {

            if (
                lifecycleBound
            ) {

                player.unbindLifecycle()

                lifecycleBound =
                    false
            }

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "Lifecycle unbind failed",
                error
            )
        }

        // --------------------------------------------------------
        // RELEASE PLAYER
        // --------------------------------------------------------

        try {

            player.release()

        } catch (
            error: Throwable
        ) {

            Log.w(
                TAG,
                "Player release failed",
                error
            )
        }

        // --------------------------------------------------------
        // FLAG SECURE
        //
        // Сохраняем прежнюю логику.
        // --------------------------------------------------------

        activity
            ?.window
            ?.clearFlags(
                WindowManager
                    .LayoutParams
                    .FLAG_SECURE
            )
    }
}


// ============================================================
// PARAM HELPERS
// ============================================================

private fun Map<*, *>.booleanValue(
    key: String,
    fallback: Boolean
): Boolean {

    return this[key]
        as? Boolean
        ?: fallback
}


// ============================================================
// ACTIVITY LOOKUP
// ============================================================

private fun Context.findActivity(): Activity? {

    var current: Context? =
        this

    while (
        current is ContextWrapper
    ) {

        if (
            current is Activity
        ) {
            return current
        }

        current =
            current.baseContext
    }

    return current
        as? Activity
}
