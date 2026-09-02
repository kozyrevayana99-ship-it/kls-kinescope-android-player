import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

/// ============================================================
/// СОБЫТИЕ НАТИВНОГО KINESCOPE PLAYER
/// ============================================================

class KlsKinescopePlayerEvent {
  const KlsKinescopePlayerEvent({
    required this.type,
    this.positionMilliseconds = 0,
    this.durationMilliseconds = 0,
    this.isPlaying = false,
    this.message = '',
  });

  final String type;
  final int positionMilliseconds;
  final int durationMilliseconds;
  final bool isPlaying;
  final String message;

  int get positionSeconds =>
      (positionMilliseconds / 1000).floor();

  int get durationSeconds =>
      (durationMilliseconds / 1000).floor();

  bool get isEnded => type == 'ended';

  bool get isPaused => type == 'pause';

  bool get isPlay => type == 'play';

  factory KlsKinescopePlayerEvent.fromDynamic(
    dynamic value,
  ) {
    if (value is! Map) {
      return const KlsKinescopePlayerEvent(
        type: 'unknown',
      );
    }

    final map = Map<dynamic, dynamic>.from(value);

    return KlsKinescopePlayerEvent(
      type: (map['type'] ?? '')
          .toString()
          .trim()
          .toLowerCase(),
      positionMilliseconds:
          _toInt(map['positionMs']),
      durationMilliseconds:
          _toInt(map['durationMs']),
      isPlaying: _toBool(
        map['isPlaying'],
      ),
      message: (map['message'] ?? '')
          .toString(),
    );
  }
}

/// ============================================================
/// CONTROLLER
///
/// Flutter сможет управлять нативным Android Kinescope:
///
/// play()
/// pause()
/// seekTo()
/// getPosition()
/// getDuration()
/// isPlaying()
/// enterPictureInPicture()
/// ============================================================

class KlsKinescopeAndroidPlayerController {
  MethodChannel? _methodChannel;

  bool get isAttached =>
      _methodChannel != null;

  void _attach(
    int viewId,
  ) {
    _methodChannel = MethodChannel(
      'kls_kinescope_android_player/methods/$viewId',
    );
  }

  void _detach() {
    _methodChannel = null;
  }

  Future<void> play() async {
    await _invokeVoid(
      'play',
    );
  }

  Future<void> pause() async {
    await _invokeVoid(
      'pause',
    );
  }

  Future<void> seekTo(
    Duration position,
  ) async {
    await _invokeVoid(
      'seekTo',
      <String, dynamic>{
        'positionMs':
            position.inMilliseconds,
      },
    );
  }

  Future<void> seekToSeconds(
    int seconds,
  ) async {
    await seekTo(
      Duration(
        seconds: seconds < 0
            ? 0
            : seconds,
      ),
    );
  }

  Future<Duration> getPosition() async {
    final value = await _invoke<int>(
      'getPositionMs',
    );

    return Duration(
      milliseconds:
          value == null || value < 0
              ? 0
              : value,
    );
  }

  Future<Duration> getDuration() async {
    final value = await _invoke<int>(
      'getDurationMs',
    );

    return Duration(
      milliseconds:
          value == null || value < 0
              ? 0
              : value,
    );
  }

  Future<bool> getIsPlaying() async {
    final value = await _invoke<bool>(
      'isPlaying',
    );

    return value ?? false;
  }

  Future<bool> getIsEnded() async {
    final value = await _invoke<bool>(
      'isEnded',
    );

    return value ?? false;
  }

  Future<void> enterPictureInPicture() async {
    await _invokeVoid(
      'enterPictureInPicture',
    );
  }

  Future<T?> _invoke<T>(
    String method, [
    dynamic arguments,
  ]) async {
    final channel = _methodChannel;

    if (channel == null) {
      return null;
    }

    try {
      return await channel.invokeMethod<T>(
        method,
        arguments,
      );
    } on MissingPluginException catch (error) {
      debugPrint(
        'Kinescope native method not connected yet: '
        '$method — $error',
      );

      return null;
    } on PlatformException catch (error) {
      debugPrint(
        'Kinescope native method error: '
        '$method — ${error.message}',
      );

      return null;
    } catch (error) {
      debugPrint(
        'Kinescope method error: '
        '$method — $error',
      );

      return null;
    }
  }

  Future<void> _invokeVoid(
    String method, [
    dynamic arguments,
  ]) async {
    await _invoke<dynamic>(
      method,
      arguments,
    );
  }
}

/// ============================================================
/// NATIVE KINESCOPE ANDROID PLAYER
///
/// На iOS / Web ничего не меняем.
/// Там родительский KLS widget продолжает использовать
/// существующую реализацию.
/// ============================================================

class KlsKinescopeAndroidPlayer
    extends StatefulWidget {
  const KlsKinescopeAndroidPlayer({
    super.key,
    required this.videoId,

    // ----------------------------------------------------------
    // СТАРЫЕ ПАРАМЕТРЫ — СОХРАНЕНЫ
    // ----------------------------------------------------------

    this.autoplay = false,
    this.muted = false,
    this.loop = false,
    this.controls = true,
    this.fullscreen = true,
    this.pictureInPicture = true,
    this.accentColor = '#E9C18A',

    // ----------------------------------------------------------
    // НОВОЕ
    // ----------------------------------------------------------

    this.backgroundPlayback = true,
    this.initialPositionSeconds = 0,

    this.onControllerCreated,
    this.onEvent,
    this.onReady,
    this.onPlay,
    this.onPause,
    this.onEnded,
    this.onPositionChanged,
  });

  final String videoId;

  final bool autoplay;
  final bool muted;
  final bool loop;
  final bool controls;
  final bool fullscreen;

  /// Picture-in-Picture.
  ///
  /// По умолчанию теперь включён.
  final bool pictureInPicture;

  /// Фоновое воспроизведение.
  ///
  /// true:
  /// - блокировка телефона не должна останавливать звук;
  /// - Kinescope сможет использовать MediaSession;
  /// - системный lock-screen player сможет управлять playback.
  final bool backgroundPlayback;

  final String accentColor;

  /// Позиция, с которой нужно продолжить просмотр.
  ///
  /// Потом сюда передадим:
  ///
  /// learning_progress.last_position_seconds
  final int initialPositionSeconds;

  /// Позволяет родительскому Flutter widget получить
  /// controller нативного Kinescope.
  final ValueChanged<
      KlsKinescopeAndroidPlayerController>?
      onControllerCreated;

  /// Любое событие.
  final ValueChanged<KlsKinescopePlayerEvent>?
      onEvent;

  final ValueChanged<KlsKinescopePlayerEvent>?
      onReady;

  final ValueChanged<KlsKinescopePlayerEvent>?
      onPlay;

  final ValueChanged<KlsKinescopePlayerEvent>?
      onPause;

  final ValueChanged<KlsKinescopePlayerEvent>?
      onEnded;

  final ValueChanged<KlsKinescopePlayerEvent>?
      onPositionChanged;

  @override
  State<KlsKinescopeAndroidPlayer>
      createState() =>
          _KlsKinescopeAndroidPlayerState();
}

class _KlsKinescopeAndroidPlayerState
    extends State<KlsKinescopeAndroidPlayer> {
  static const String _viewType =
      'kls_kinescope_android_player/native_player';

  final KlsKinescopeAndroidPlayerController
      _playerController =
      KlsKinescopeAndroidPlayerController();

  StreamSubscription<dynamic>?
      _eventSubscription;

  int? _nativeViewId;

  bool _disposed = false;

  /// ============================================================
  /// PLATFORM VIEW CREATED
  /// ============================================================

  void _handlePlatformViewCreated(
    int viewId,
  ) {
    if (_disposed) {
      return;
    }

    _nativeViewId = viewId;

    _playerController._attach(
      viewId,
    );

    widget.onControllerCreated?.call(
      _playerController,
    );

    _listenNativeEvents(
      viewId,
    );
  }

  /// ============================================================
  /// EVENT CHANNEL
  /// ============================================================

  void _listenNativeEvents(
    int viewId,
  ) {
    unawaited(
      _eventSubscription?.cancel(),
    );

    final eventChannel = EventChannel(
      'kls_kinescope_android_player/events/$viewId',
    );

    _eventSubscription =
        eventChannel
            .receiveBroadcastStream()
            .listen(
      _handleNativeEvent,
      onError: (dynamic error) {
        // Пока Kotlin EventChannel ещё не подключён,
        // приложение НЕ должно падать.
        debugPrint(
          'Kinescope event channel: $error',
        );
      },
      cancelOnError: false,
    );
  }

  /// ============================================================
  /// NATIVE EVENT
  /// ============================================================

  void _handleNativeEvent(
    dynamic rawEvent,
  ) {
    if (_disposed) {
      return;
    }

    final event =
        KlsKinescopePlayerEvent
            .fromDynamic(
      rawEvent,
    );

    widget.onEvent?.call(
      event,
    );

    switch (event.type) {
      case 'ready':
        widget.onReady?.call(
          event,
        );
        break;

      case 'play':
        widget.onPlay?.call(
          event,
        );
        break;

      case 'pause':
        widget.onPause?.call(
          event,
        );
        break;

      case 'ended':
        widget.onEnded?.call(
          event,
        );
        break;

      case 'position':
        widget.onPositionChanged?.call(
          event,
        );
        break;
    }
  }

  /// ============================================================
  /// BUILD
  /// ============================================================

  @override
  Widget build(
    BuildContext context,
  ) {
    final id =
        widget.videoId
            .trim();

    if (
        kIsWeb ||
        defaultTargetPlatform !=
            TargetPlatform.android ||
        id.isEmpty
    ) {
      return const SizedBox.expand();
    }

    final initialPositionSeconds =
        widget.initialPositionSeconds < 0
            ? 0
            : widget.initialPositionSeconds;

    final creationParams =
        <String, dynamic>{
      // --------------------------------------------------------
      // СУЩЕСТВУЮЩИЕ
      // --------------------------------------------------------

      'videoId': id,

      'autoplay':
          widget.autoplay,

      'muted':
          widget.muted,

      'loop':
          widget.loop,

      'controls':
          widget.controls,

      'fullscreen':
          widget.fullscreen,

      'pictureInPicture':
          widget.pictureInPicture,

      'accentColor':
          widget.accentColor,

      // --------------------------------------------------------
      // НОВЫЕ
      // --------------------------------------------------------

      'backgroundPlayback':
          widget.backgroundPlayback,

      'initialPositionSeconds':
          initialPositionSeconds,
    };

    // ==========================================================
    // ВАЖНО:
    //
    // Surface AndroidView СОХРАНЯЕМ.
    //
    // Kinescope DRM / Widevine использует SurfaceView.
    // TextureView сюда не ставим.
    // ==========================================================

    return PlatformViewLink(
      key: ValueKey<String>(
        'kinescope-native-$id',
      ),
      viewType: _viewType,
      surfaceFactory: (
        BuildContext context,
        PlatformViewController controller,
      ) {
        return AndroidViewSurface(
          controller:
              controller
                  as AndroidViewController,
          gestureRecognizers:
              const {},
          hitTestBehavior:
              PlatformViewHitTestBehavior
                  .opaque,
        );
      },
      onCreatePlatformView:
          (
        PlatformViewCreationParams params,
      ) {
        final controller =
            PlatformViewsService
                .initSurfaceAndroidView(
          id: params.id,
          viewType: _viewType,
          layoutDirection:
              TextDirection.ltr,
          creationParams:
              creationParams,
          creationParamsCodec:
              const StandardMessageCodec(),
        );

        controller
            .addOnPlatformViewCreatedListener(
          (int viewId) {
            // Сначала обязательно сообщаем Flutter
            // PlatformViewLink, что View создан.
            params.onPlatformViewCreated(
              viewId,
            );

            // Потом подключаем наши каналы.
            _handlePlatformViewCreated(
              viewId,
            );
          },
        );

        unawaited(
          controller.create(),
        );

        return controller;
      },
    );
  }

  /// ============================================================
  /// DISPOSE
  /// ============================================================

  @override
  void dispose() {
    _disposed = true;

    unawaited(
      _eventSubscription?.cancel(),
    );

    _eventSubscription = null;

    _playerController._detach();

    _nativeViewId = null;

    super.dispose();
  }
}

/// ============================================================
/// HELPERS
/// ============================================================

int _toInt(
  dynamic value,
) {
  if (value == null) {
    return 0;
  }

  if (value is int) {
    return value;
  }

  if (value is num) {
    return value.toInt();
  }

  return int.tryParse(
        value.toString(),
      ) ??
      0;
}

bool _toBool(
  dynamic value,
) {
  if (value is bool) {
    return value;
  }

  if (value is num) {
    return value != 0;
  }

  final text =
      value
          .toString()
          .trim()
          .toLowerCase();

  return text == 'true' ||
      text == '1' ||
      text == 'yes' ||
      text == 'on';
}
