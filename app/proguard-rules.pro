# NewPipe Extractor: services are looked up by name and Rhino (JavaScript engine used to
# decipher YouTube stream URLs) relies heavily on reflection.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**

# jsoup / nanojson / protobuf (used by NewPipe)
-keep class com.grack.nanojson.** { *; }
-dontwarn org.jsoup.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# OkHttp optional platform integrations
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WebView JavaScript bridge used by the browser's video detector
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# yt-dlp Android wrapper (Jackson-mapped models, reflection)
-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
