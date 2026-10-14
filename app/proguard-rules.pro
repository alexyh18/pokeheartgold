-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.bw2factory.**$$serializer { *; }
-keepclassmembers class com.bw2factory.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
