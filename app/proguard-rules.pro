# Room entities and DAOs — needed because Room uses reflection to map columns
-keep class com.businesscard.scanner.data.** { *; }

# ML Kit bundled text recognition — keep the public API; R8 shrinks internals
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.mlkit.vision.common.InputImage { *; }
-keep class com.google.mlkit.common.** { *; }

# ZXing — only the classes we call directly
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.EncodeHintType { *; }
-keep class com.google.zxing.common.BitMatrix { *; }

# Glide — annotation processor generates a stub AppGlideModule; keep its name
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.AppGlideModule
-keep @com.bumptech.glide.annotation.GlideModule public class *

# Keep BuildConfig fields
-keep class com.businesscard.scanner.BuildConfig { *; }

# Standard Android rules
-keepattributes *Annotation*
-keepattributes LineNumberTable
