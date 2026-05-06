# OxClient ProGuard kuralları

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Netty (relay için kritik)
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-keepattributes Signature
-keepattributes *Annotation*

# Cloudburst Protocol
-keep class org.cloudburstmc.** { *; }
-dontwarn org.cloudburstmc.**

# Bedrock-connection
-keep class com.nukkitx.** { *; }
-dontwarn com.nukkitx.**

# OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# OxClient model sınıfları
-keep class com.oxclient.auth.** { *; }
-keep class com.oxclient.relay.** { *; }
-keep class com.oxclient.module.** { *; }
-keep class com.oxclient.modules.** { *; }
-keep class com.oxclient.session.** { *; }
-keep class com.oxclient.events.** { *; }

# Timber
-dontwarn org.jetbrains.annotations.**

# Snappy
-dontwarn org.xerial.snappy.**
-keep class org.xerial.snappy.** { *; }
