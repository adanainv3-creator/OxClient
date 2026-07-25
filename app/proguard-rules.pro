-dontwarn io.netty.**
-keep class io.netty.util.internal.** { *; }
-keepclassmembers class io.netty.** { *; }

-dontwarn org.cloudburstmc.**
-keepclassmembers class org.cloudburstmc.** { *; }

-dontwarn org.bitbucket.b_c.jose4j.**
-keepclassmembers class org.bitbucket.b_c.jose4j.** { *; }

-dontwarn com.google.gson.**
-keep class com.google.gson.stream.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepattributes Signature, *Annotation*

-keepclassmembers class com.oxclient.**.model.** {
    <fields>;
}
-keepclassmembers class com.oxclient.**.config.** {
    <fields>;
}
-keep,allowobfuscation,allowoptimization class com.oxclient.**.model.** {}
-keep,allowobfuscation,allowoptimization class com.oxclient.**.config.** {}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
-keepattributes InnerClasses, EnclosingMethod

-keep class com.oxclient.OxClientApp {
    public <init>(...);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-repackageclasses "a"
-allowaccessmodification
-overloadaggressively
-mergeinterfacesaggressively
-optimizationpasses 5

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
}

-adaptclassstrings
-adaptresourcefilenames **.properties,**.xml,**.json
-adaptresourcefilecontents **.properties,META-INF/MANIFEST.MF

-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable
-keepattributes !LocalVariableTable,!LocalVariableTypeTable

-verbose
-printmapping mapping.txt
