-keep class kotlin.sequences.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-dontwarn kotlinx.coroutinesz.**

-keeppackagenames kotlin.**, kotlinx.**

-keep class com.samskivert.mustache.** { *; }
-dontwarn com.samskivert.mustache.**
-keep class com.dantsu.escposprinter.** { *; }
-dontwarn com.dantsu.escposprinter.**

-keep class co.id.rmms.printbridge.data.** { *; }
-keep class co.id.rmms.printbridge.updater.** { *; }