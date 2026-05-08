# OxClient ProGuard rules

# Keep Netty / CloudburstMC
-keep class io.netty.**              { *; }
-keep class org.cloudburstmc.**     { *; }
-keep class com.nukkitx.**          { *; }

# Keep auth models
-keep class com.oxclient.auth.**    { *; }
-keep class com.oxclient.proxy.**   { *; }
-keep class com.oxclient.module.**  { *; }

# Keep Gson models
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.**      { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.**   { *; }

# Suppress warnings
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn reactor.**
