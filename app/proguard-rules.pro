# kotlinx.serialization: keep generated serializers for the protocol data model.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.sonopulse.echoguide.** {
    *** Companion;
}
-keepclasseswithmembers class com.sonopulse.echoguide.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sonopulse.echoguide.**$$serializer { *; }
