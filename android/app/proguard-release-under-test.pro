# Applied to the app only with -Ppos.testBuildType=release. The test APK leaves
# out libraries the app already contains and loads them from the app, so R8 must
# not strip or rename them. Shipped builds never use this file.
-dontobfuscate
-keep class androidx.tracing.** { *; }
-keep class androidx.annotation.** { *; }
-keep class androidx.core.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class org.jetbrains.annotations.** { *; }
