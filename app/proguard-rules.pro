# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# JNI-backed and process-wrapper libraries use reflection/native entry points.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.libvlc.util.** { *; }
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keepclassmembers class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# Launcher/crash-reporting entry points must keep their manifest names in release builds.
-keep class com.selxo.rougo.RougoApplication { *; }
-keep class com.selxo.rougo.MainActivity { *; }
