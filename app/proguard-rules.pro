# Keep kotlinx.serialization models for Proguard/R8
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vesper.app.domain.model.**$$serializer { *; }
-keep,includedescriptorclasses class com.vesper.app.data.remote.**$$serializer { *; }
