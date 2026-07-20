package ru.kls.kinescope_android_player

import io.flutter.embedding.engine.plugins.FlutterPlugin

class KlsKinescopeAndroidPlayerPlugin : FlutterPlugin {
    override fun onAttachedToEngine(
        flutterPluginBinding: FlutterPlugin.FlutterPluginBinding
    ) {
        flutterPluginBinding.platformViewRegistry.registerViewFactory(
            "kls_kinescope_android_player/native_player",
            KlsKinescopePlayerFactory()
        )
    }

    override fun onDetachedFromEngine(
        binding: FlutterPlugin.FlutterPluginBinding
    ) {
        // Each PlatformView releases its own player in dispose().
    }
}
