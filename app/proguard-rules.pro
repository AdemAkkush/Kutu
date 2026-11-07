-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.kutu.chat.**$$serializer { *; }
-keepclassmembers class com.kutu.chat.** { *** Companion; }
-keepclasseswithmembers class com.kutu.chat.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# PDFBox (PDF metin çıkarma): yansıma ile yüklenen yazı tipi/filtre sınıfları korunur; isteğe bağlı JPEG2000 çözücü yok.
-keep class com.tom_roush.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**
