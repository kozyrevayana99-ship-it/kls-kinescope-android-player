import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

/// Native Kinescope video player for Android.
///
/// On non-Android platforms this widget returns an empty box. The KLS app can
/// continue using its existing iOS WebView/FairPlay implementation.
class KlsKinescopeAndroidPlayer extends StatelessWidget {
  const KlsKinescopeAndroidPlayer({
    super.key,
    required this.videoId,
    this.autoplay = false,
    this.muted = false,
    this.loop = false,
    this.controls = true,
    this.fullscreen = true,
    this.pictureInPicture = false,
    this.accentColor = '#E9C18A',
  });

  static const String _viewType =
      'kls_kinescope_android_player/native_player';

  final String videoId;
  final bool autoplay;
  final bool muted;
  final bool loop;
  final bool controls;
  final bool fullscreen;
  final bool pictureInPicture;
  final String accentColor;

  @override
  Widget build(BuildContext context) {
    final id = videoId.trim();

    if (kIsWeb ||
        defaultTargetPlatform != TargetPlatform.android ||
        id.isEmpty) {
      return const SizedBox.expand();
    }

    final creationParams = <String, dynamic>{
      'videoId': id,
      'autoplay': autoplay,
      'muted': muted,
      'loop': loop,
      'controls': controls,
      'fullscreen': fullscreen,
      'pictureInPicture': pictureInPicture,
      'accentColor': accentColor,
    };

    // Surface AndroidView is used deliberately because the native Kinescope
    // SDK renders DRM video through SurfaceView.
    return PlatformViewLink(
      key: ValueKey<String>('kinescope-native-$id'),
      viewType: _viewType,
      surfaceFactory: (
        BuildContext context,
        PlatformViewController controller,
      ) {
        return AndroidViewSurface(
          controller: controller as AndroidViewController,
          gestureRecognizers: const {},
          hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        );
      },
      onCreatePlatformView: (PlatformViewCreationParams params) {
        final controller = PlatformViewsService.initSurfaceAndroidView(
          id: params.id,
          viewType: _viewType,
          layoutDirection: TextDirection.ltr,
          creationParams: creationParams,
          creationParamsCodec: const StandardMessageCodec(),
        );

        controller.addOnPlatformViewCreatedListener(
          params.onPlatformViewCreated,
        );
        controller.create();

        return controller;
      },
    );
  }
}
