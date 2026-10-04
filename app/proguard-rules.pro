# InkProof proguard rules.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.inkproof.app.**$$serializer { *; }
-keepclassmembers class com.inkproof.app.** { *** Companion; }
-keepclasseswithmembers class com.inkproof.app.** { kotlinx.serialization.KSerializer serializer(...); }

# ML Kit digital ink
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
