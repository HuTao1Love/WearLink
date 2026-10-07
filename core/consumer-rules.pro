# gomobile bindings are called from native code by name.
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.** { *; }
