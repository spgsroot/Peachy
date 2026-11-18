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
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# libonnxruntime4j_jni.so resolves ai.onnxruntime classes, fields and methods by name.
-keep class ai.onnxruntime.** { *; }

# libmupdf_java.so resolves com.artifex.mupdf.fitz classes, fields and methods by name.
-keep class com.artifex.mupdf.fitz.** { *; }

# LibreOfficeKit JNI and the Office adapter resolve these classes and members by name.
-keep class org.libreoffice.kit.** { *; }

# BouncyCastle is reached through JCA provider lookups and reflective ASN.1 access, and the
# jdk18on artifacts carry multi-release metadata R8 does not understand.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
