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
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player

import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView

import io.kinescope.sdk.models.players.syncLegacyChromeFlags
import io.kinescope.sdk.player.KinescopePictureInPicture
import io.kinescope.sdk.player.KinescopePlayerOptions
import io.kinescope.sdk.player.KinescopeVideoPlayer
import io.kinescope.sdk.view.KinescopePlayerView


class KlsKinescopePlayerView(
    context: Context,
    params: Map<*, *>,
    messenger: BinaryMessenger,
    viewId: Int
) : PlatformView {

    companion object {
        private const val TAG = "KlsKinescopePlayer"

        private const val METHOD_CHANNEL_PREFIX =
            "kls_kinescope_android_player/methods"

        private const val EVENT_CHANNEL_PREFIX =
            "kls_kinescope_android_player/events"
    }

    // ============================================================
    // CONTEXT / ACTIVITY
    // ============================================================

    private val appContext: Context =
        context.applicationContext

    private val activity: Activity? =
        context.findActivity()

    // ============================================================
    // FLUTTER CHANNELS
    //
    // У каждого PlatformView свой viewId:
    //
    // methods/17
    // events/17
    //
    // Поэтому несколько плееров друг другу не мешают.
    // ============================================================

    private val methodChannel =
        MethodChannel(
            messenger,
            "$METHOD_CHANNEL_PREFIX/$viewId"
        )

    private val eventChannel =
        EventChannel(
            messenger,
            "$EVENT_CHANNEL_PREFIX/$viewId"
        )

    private var eventSink: EventChannel.EventSink? =
        null

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
     * true:
     * - блокировка телефона не должна останавливать playback;
     * - Kinescope подключает PlaybackService / MediaSession;
     * - системные media controls смогут управлять видео.
     */
    private val backgroundPlaybackEnabled: Boolean =
        params.booleanValue(
            "backgroundPlayback",
            true
        )

    /**
     * Позиция, с которой нужно продолжить видео.
     *
     * Приходит из Flutter в СЕКУНДАХ.
     *
     * Например:
     * initialPositionSeconds = 2846
     *
     * = 47:26.
     */
    private val initialPositionMs: Long =
        params.longValue(
            "initialPositionSeconds",
            0L
        )
            .coerceAtLeast(0L)
            .times(1000L)

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
    //
    // useTextureSurface = false НЕ МЕНЯЕМ.
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
    // Тоже SurfaceView.
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

    private var fullscreenDialog: Dialog? =
        null

    private var isFullscreen = false

    private var disposed = false

    private var pipReceiverRegistered = false

    private var pipUiPrepared = false

    /**
     * Реальный Player уже дошёл до STATE_READY.
     */
    private var playerReady = false

    /**
     * Восстановление позиции выполняем ровно один раз.
     */
    private var initialSeekApplied =
        initialPositionMs <= 0L

    /**
     * Нужны для корректного определения именно PLAY / PAUSE.
     *
     * isPlaying нельзя использовать для определения паузы:
     * во время buffering isPlaying временно становится false.
     *
     * Поэтому pause определяем через playWhenReady.
     */
    private var lastPlayWhenReady: Boolean? =
        null

    private var hasStartedPlayback = false

    private var endedEventSent = false

    // ============================================================
    // EVENT CHANNEL
    // ============================================================

    private val streamHandler =
        object : EventChannel.StreamHandler {

            override fun onListen(
                arguments: Any?,
                events: EventChannel.EventSink?
            ) {
                eventSink =
                    events

                // Если Flutter подключился уже после того,
                // как видео подготовилось — всё равно сообщим READY.
                if (
                    playerReady &&
                    !disposed
                ) {
                    emitEvent(
                        type = "ready"
                    )
                }
            }

            override fun onCancel(
                arguments: Any?
            ) {
                eventSink =
                    null
            }
        }

    // ============================================================
    // METHOD CHANNEL
    //
    // Flutter → Kotlin
    // ============================================================

    private val methodHandler =
        MethodChannel.MethodCallHandler {
                call: MethodCall,
                result: MethodChannel.Result ->

            if (disposed) {
                result.error(
                    "PLAYER_DISPOSED",
                    "Kinescope player is already disposed",
                    null
                )

                return@MethodCallHandler
            }

            when (call.method) {

                // ------------------------------------------------
                // PLAY
                // ------------------------------------------------

                "play" -> {
                    play()

                    result.success(
                        null
                    )
                }

                // ------------------------------------------------
                // PAUSE
                // ------------------------------------------------

                "pause" -> {
                    pause()

                    result.success(
                        null
                    )
                }

                // ------------------------------------------------
                // SEEK
                // ------------------------------------------------

                "seekTo" -> {
                    val positionMs =
                        (
                            call.argument<Any?>(
                                "positionMs"
                            ) as? Number
                            )
                            ?.toLong()
                            ?: 0L

                    seekToPosition(
                        positionMs
                    )

                    result.success(
                        null
                    )
                }

                // ------------------------------------------------
                // POSITION
                // ------------------------------------------------

                "getPositionMs" -> {
                    result.success(
                        currentPositionMs()
                    )
                }

                // ------------------------------------------------
                // DURATION
                // ------------------------------------------------

                "getDurationMs" -> {
                    result.success(
                        durationMs()
                    )
                }

                // ------------------------------------------------
                // IS PLAYING
                // ------------------------------------------------

                "isPlaying" -> {
                    result.success(
                        isPlaying()
                    )
                }

                // ------------------------------------------------
                // IS ENDED
                // ------------------------------------------------

                "isEnded" -> {
                    result.success(
                        isEnded()
                    )
                }

                // ------------------------------------------------
                // PiP
                // ------------------------------------------------

                "enterPictureInPicture" -> {
                    enterPictureInPicture()

                    result.success(
                        null
                    )
                }

                // ------------------------------------------------
                // FULL CURRENT STATE
                //
                // Полезно будет для сохранения перед закрытием.
                // ------------------------------------------------

                "getState" -> {
                    result.success(
                        buildEventPayload(
                            type = "state"
                        )
                    )
                }

                else -> {
                    result.notImplemented()
                }
            }
        }

    // ============================================================
    // PLAYER LISTENER
    // ============================================================

    private val playerListener =
        object : Player.Listener {

            // ----------------------------------------------------
            // PLAY / PAUSE
            //
            // Используем playWhenReady, а не isPlaying.
            //
            // Buffering НЕ будет ошибочно считаться паузой.
            // ----------------------------------------------------

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int
            ) {
                if (disposed) {
                    return
                }

                val previous =
                    lastPlayWhenReady

                lastPlayWhenReady =
                    playWhenReady

                // Первый callback может просто сообщить
                // начальное состояние false.
                // Его как Pause не отправляем.
                if (
                    previous == null
                ) {
                    if (playWhenReady) {
                        hasStartedPlayback = true
                        endedEventSent = false

                        emitEvent(
                            type = "play"
                        )
                    }

                    updateAutoEnterPictureInPicture(
                        playWhenReady
                    )

                    return
                }

                if (
                    previous == playWhenReady
                ) {
                    return
                }

                if (playWhenReady) {

                    hasStartedPlayback = true
                    endedEventSent = false

                    emitEvent(
                        type = "play"
                    )

                } else {

                    // Если видео уже закончилось,
                    // отдельный pause нам не нужен:
                    // будет event = ended.
                    if (
                        hasStartedPlayback &&
                        !isEnded()
                    ) {
                        emitEvent(
                            type = "pause"
                        )
                    }
                }

                updateAutoEnterPictureInPicture(
                    playWhenReady
                )
            }

            // ----------------------------------------------------
            // IS PLAYING
            //
            // Для PiP-кнопки обновляем системное состояние.
            // ----------------------------------------------------

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
            }

            // ----------------------------------------------------
            // PLAYBACK STATE
            // ----------------------------------------------------

            override fun onPlaybackStateChanged(
                playbackState: Int
            ) {
                if (disposed) {
                    return
                }

                when (playbackState) {

                    Player.STATE_READY -> {

                        playerReady =
                            true

                        endedEventSent =
                            false

                        applyInitialPositionIfNeeded()

                        Log.d(
                            TAG,
                            "STATE_READY " +
                                "position=${currentPositionMs()} " +
                                "duration=${durationMs()}"
                        )

                        emitEvent(
                            type = "ready"
                        )

                        updateAutoEnterPictureInPicture(
                            player
                                .playbackPlayer
                                ?.playWhenReady
                                == true
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

                        if (
                            !endedEventSent
                        ) {
                            endedEventSent =
                                true

                            emitEvent(
                                type = "ended"
                            )
                        }

                        hasStartedPlayback =
                            false

                        updateAutoEnterPictureInPicture(
                            false
                        )

                        updatePictureInPictureActions()
                    }

                    Player.STATE_IDLE -> {

                        Log.d(
                            TAG,
                            "STATE_IDLE"
                        )
                    }
                }
            }

            // ----------------------------------------------------
            // SEEK / POSITION JUMP
            //
            // Это НЕ сетевой запрос.
            //
            // Просто Flutter узнаёт новую позицию после перемотки.
            // ----------------------------------------------------

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (disposed) {
                    return
                }

                emitEvent(
                    type = "position"
                )
            }
        }

    // ============================================================
    // PiP REMOTE PLAY / PAUSE
    // ============================================================

    private val pipReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action !=
                    KinescopePictureInPicture
                        .ACTION_PLAY_PAUSE
                ) {
                    return
                }

                togglePlaybackFromPictureInPicture()
            }
        }

    // ============================================================
    // EXTRA LIFECYCLE
    //
    // Сам background playback обрабатывает
    // KinescopeVideoPlayer.bindLifecycle().
    //
    // Здесь только восстанавливаем UI после PiP.
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
        // FLUTTER CHANNELS
        // --------------------------------------------------------

        methodChannel.setMethodCallHandler(
            methodHandler
        )

        eventChannel.setStreamHandler(
            streamHandler
        )

        // --------------------------------------------------------
        // SCREEN SECURITY
        //
        // СТАРОЕ ПОВЕДЕНИЕ СОХРАНЯЕМ.
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
        // PLAYER LISTENER
        // --------------------------------------------------------

        player.exoPlayer
            ?.addListener(
                playerListener
            )

        // --------------------------------------------------------
        // PiP REMOTE BUTTON
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

                // PlatformView сам release() делает в dispose().
                releaseOnDestroy =
                    false
            )

            lifecycleOwner
                .lifecycle
                .addObserver(
                    pipLifecycleObserver
                )

            lifecycleBound =
                true
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

                    // SurfaceView может быть создан раньше,
                    // чем Flutter завершит layout.
                    //
                    // Это твоя старая защита от чёрного экрана —
                    // СОХРАНЯЕМ.
                    playerView.post {

                        playerView.requestLayout()

                        playerView.invalidate()

                        updateAutoEnterPictureInPicture(
                            player
                                .playbackPlayer
                                ?.playWhenReady
                                == true
                        )
                    }
                },

                onFailed = { error ->

                    val message =
                        error
                            ?.message
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: "Unable to load video"

                    Log.e(
                        TAG,
                        "Unable to load video: $videoId",
                        error
                    )

                    emitEvent(
                        type = "error",
                        message = message
                    )
                }
            )

        } else {

            Log.e(
                TAG,
                "videoId is empty"
            )

            emitEvent(
                type = "error",
                message = "videoId is empty"
            )
        }
    }

    // ============================================================
    // PLATFORM VIEW
    // ============================================================

    override fun getView(): View =
        playerView

    // ============================================================
    // INITIAL POSITION
    //
    // Возвращаем человека туда, где он остановился.
    // ============================================================

    private fun applyInitialPositionIfNeeded() {

        if (
            initialSeekApplied
        ) {
            return
        }

        initialSeekApplied =
            true

        if (
            initialPositionMs <= 0L
        ) {
            return
        }

        val duration =
            durationMs()

        val target =
            if (
                duration > 0L
            ) {
                initialPositionMs.coerceIn(
                    0L,
                    duration
                )
            } else {
                initialPositionMs
            }

        Log.d(
            TAG,
            "Restore position: $target ms"
        )

        player.seekToPosition(
            target
        )
    }

    // ============================================================
    // PUBLIC PLAYER CONTROL
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

        val duration =
            durationMs()

        val target =
            if (
                duration > 0L
            ) {
                positionMs.coerceIn(
                    0L,
                    duration
                )
            } else {
                positionMs.coerceAtLeast(
                    0L
                )
            }

        player.seekToPosition(
            target
        )

        emitEvent(
            type = "position"
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
    // EVENT PAYLOAD
    //
    // Kotlin → Flutter
    // ============================================================

    private fun buildEventPayload(
        type: String,
        message: String = ""
    ): Map<String, Any?> {

        val playbackPlayer =
            player.playbackPlayer

        return mapOf(
            "type" to type,
            "videoId" to videoId,
            "positionMs" to currentPositionMs(),
            "durationMs" to durationMs(),
            "isPlaying" to isPlaying(),
            "playWhenReady" to (
                playbackPlayer
                    ?.playWhenReady
                    ?: false
                ),
            "playbackState" to (
                playbackPlayer
                    ?.playbackState
                    ?: Player.STATE_IDLE
                ),
            "message" to message,
            "timestampMs" to
                System.currentTimeMillis()
        )
    }

    private fun emitEvent(
        type: String,
        message: String = ""
    ) {
        if (disposed) {
            return
        }

        val payload =
            buildEventPayload(
                type = type,
                message = message
            )

        val send = {

            if (
                !disposed
            ) {
                try {
                    eventSink
                        ?.success(
                            payload
                        )
                } catch (
                    error: Throwable
                ) {
                    Log.w(
                        TAG,
                        "Unable to emit Flutter event: $type",
                        error
                    )
                }
            }
        }

        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {
            send()
        } else {
            playerView.post(
                send
            )
        }
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

        // Если открыт наш fullscreen Dialog,
        // сначала возвращаем Surface в Activity.
        if (
            isFullscreen
        ) {
            restoreInlinePlayer()
        }

        preparePictureInPictureUi()

        // При ручном входе в PiP продолжаем воспроизведение.
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

                        emitEvent(
                            type = "pip_enter"
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
    // AUTO PiP — ANDROID 12+
    //
    // Если видео воспроизводится и человек жмёт Home /
    // делает swipe Home — Android сможет перевести Activity
    // в PiP автоматически.
    //
    // Для Android 8–11 потом отдельно подключим Activity callback.
    // ============================================================

    private fun updateAutoEnterPictureInPicture(
        shouldAutoEnter: Boolean
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
                        shouldAutoEnter &&
                            !isEnded()
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

        pipUiPrepared =
            true

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

        pipUiPrepared =
            false

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

        if (disposed) {
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

            pipReceiverRegistered =
                true

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

        pipReceiverRegistered =
            false

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
    //
    // ТВОЮ СУЩЕСТВУЮЩУЮ РЕАЛИЗАЦИЮ СОХРАНЯЕМ.
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

        isFullscreen =
            true

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

        // Сохраняем последнее состояние в логах.
        Log.d(
            TAG,
            "dispose " +
                "position=${currentPositionMs()} " +
                "duration=${durationMs()} " +
                "playing=${isPlaying()}"
        )

        // Flutter-событие пытаемся отправить ДО disposed=true.
        //
        // На него как на единственный механизм сохранения
        // полагаться не будем — родительский Flutter widget
        // тоже сам спросит controller.getPosition()
        // перед закрытием страницы.
        emitEvent(
            type = "dispose"
        )

        disposed =
            true

        // --------------------------------------------------------
        // CHANNELS
        // --------------------------------------------------------

        methodChannel
            .setMethodCallHandler(
                null
            )

        eventChannel
            .setStreamHandler(
                null
            )

        eventSink =
            null

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
        // Старое поведение сохраняем.
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


private fun Map<*, *>.longValue(
    key: String,
    fallback: Long
): Long {

    val value =
        this[key]

    return when (value) {

        is Long ->
            value

        is Int ->
            value.toLong()

        is Number ->
            value.toLong()

        is String ->
            value
                .toLongOrNull()
                ?: fallback

        else ->
            fallback
    }
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
