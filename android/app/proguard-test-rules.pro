# Instrumented tests built against the minified release (-Ppos.testBuildType=release).
# Test code is not shipped; keep it whole and ignore compile-only annotations.
-dontobfuscate
-dontoptimize
-keep class com.poscafe.pos.e2e.** { *; }
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-dontwarn com.google.errorprone.annotations.**
