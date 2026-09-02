package ru.kls.kinescope_android_player

import android.content.Context

import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory


class KlsKinescopePlayerFactory(
    private val messenger: BinaryMessenger
) : PlatformViewFactory(
    StandardMessageCodec.INSTANCE
) {

    override fun create(
        context: Context,
        viewId: Int,
        args: Any?
    ): PlatformView {

        val params: Map<*, *> =
            (args as? Map<*, *>)
                ?: emptyMap<String, Any?>()

        return KlsKinescopePlayerView(
            context = context,
            params = params
        )
    }
}
