package ru.kls.kinescope_android_player

import io.flutter.embedding.engine.plugins.FlutterPlugin


class KlsKinescopeAndroidPlayerPlugin : FlutterPlugin {

    override fun onAttachedToEngine(
        flutterPluginBinding: FlutterPlugin.FlutterPluginBinding
    ) {

        flutterPluginBinding
            .platformViewRegistry
            .registerViewFactory(
                "kls_kinescope_android_player/native_player",
                KlsKinescopePlayerFactory(
                    flutterPluginBinding.binaryMessenger
                )
            )
    }

    override fun onDetachedFromEngine(
        binding: FlutterPlugin.FlutterPluginBinding
    ) {
        // Каждый PlatformView самостоятельно освобождает:
        // - Kinescope player
        // - listeners
        // - lifecycle
        // - PiP receiver
        //
        // Поэтому глобально здесь ничего release() не вызываем.
    }
}
