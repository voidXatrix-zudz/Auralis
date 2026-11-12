# Coil 3
-keep class coil3.** { *; }
-dontwarn coil3.**

# YouTube stream extraction (NewPipeExtractor). The extractor resolves service classes and
# serialized info objects reflectively in places, so keep the whole namespace when minifying.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn javax.annotation.**
-dontwarn org.jsoup.**
-dontwarn org.mozilla.javascript.**

# kotlinx.serialization: JsonElement parsing (InnerTube) plus generated serializers if added later.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
