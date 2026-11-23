# Ayman Tunnel Plus — R8 / ProGuard rules (release builds only).
# The defaults from the Android Gradle Plugin + the consumer rules
# shipped by Room / Hilt / WorkManager already cover most cases;
# these are the rules specific to this app.

# libbox (sing-box for Android, gomobile bindings): the Go runtime
# reaches these classes through JNI reflection — never obfuscate,
# shrink, or strip them.
-keep class io.nekohasekai.libbox.** { *; }
-keepnames class io.nekohasekai.libbox.** { *; }

# Room: keep the entities + DAOs (their consumer rules cover the
# generated code, this is a belt-and-braces keep for the schema).
-keep class dev.aalhdydy550.tunnel.core.database.** { *; }

# kotlinx-serialization: the app builds JSON with the builder API
# (no @Serializable classes), so no serializer keep rules are needed.

# OkHttp / Retrofit: the standard consumer rules apply.
