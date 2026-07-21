# Optional TLS providers checked by OkHttp.
# Они не нужны для стандартного TLS на Android,
# поэтому R8 не должен останавливать сборку из-за их отсутствия.

-dontwarn org.bouncycastle.jsse.**
-dontwarn org.bouncycastle.jsse.provider.**

-dontwarn org.conscrypt.**

-dontwarn org.openjsse.javax.net.ssl.**
-dontwarn org.openjsse.net.ssl.**

# Сохраняем классы нашего нативного Flutter-плагина.

-keep class ru.kls.kinescope_android_player.** {
    *;
}

# Сохраняем классы Android SDK Kinescope.

-keep class io.kinescope.sdk.** {
    *;
}
