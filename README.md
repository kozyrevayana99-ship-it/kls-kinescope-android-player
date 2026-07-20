# KLS Kinescope Android Player

Flutter plugin that embeds the native Kinescope Android SDK inside Flutter/FlutterFlow.

## What it does

- Native Android playback inside the app
- Kinescope DRM/Widevine support through `KinescopePlayerView`
- Uses `SurfaceView`, required for protected DRM playback
- Enables Android `FLAG_SECURE` while the player is visible
- Does not open an external browser

## FlutterFlow dependency

```yaml
kls_kinescope_android_player:
  git:
    url: https://github.com/kozyrevyana99-ship-it/kls-kinescope-android-player.git
```

## Dart usage

```dart
import 'package:kls_kinescope_android_player/kls_kinescope_android_player.dart';

KlsKinescopeAndroidPlayer(
  videoId: 'YOUR_KINESCOPE_VIDEO_ID',
  autoplay: false,
  muted: false,
  loop: false,
  accentColor: '#E9C18A',
)
```

## Important

The host Android project must be able to resolve the JitPack repository:

```gradle
maven { url 'https://jitpack.io' }
```

The plugin currently targets Android. The existing Kinescope WebView player can remain in use on iOS.
