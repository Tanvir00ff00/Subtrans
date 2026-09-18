# kotlinx.serialization writes a synthetic Companion and serializer for every
# @Serializable class and looks them up reflectively. R8 cannot see those uses,
# so without these keeps the settings and glossary would silently fail to load
# in a release build while working perfectly in debug.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.subtrans.app.**$$serializer { *; }
-keepclassmembers class com.subtrans.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.subtrans.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp ships optional hooks for platforms that are not Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
