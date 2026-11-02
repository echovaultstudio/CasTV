# Keep names readable in crash logs.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,Signature

# kotlinx.serialization
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.castv.**$$serializer { *; }

# FFmpeg decoder is loaded by reflection from Media3.
-keep class androidx.media3.decoder.ffmpeg.** { *; }

-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
