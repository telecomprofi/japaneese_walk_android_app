# ---------------------------------------------------------------------------
# Release rules. R8 full mode is on by default in AGP 8, and Compose, Room,
# Media3, WorkManager and DataStore all ship their own consumer rules, so this
# file only needs to cover what is specific to this app.
# ---------------------------------------------------------------------------

# WorkManager instantiates workers reflectively from a class name stored in its
# database, so the constructor has to survive shrinking under its real name.
-keep class com.telecomprofi.japanesewalk.game.ReminderWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room reads and writes these through generated adapters that reference the
# field names directly.
-keep class com.telecomprofi.japanesewalk.data.db.** { *; }

# Strip diagnostic logging from the shipped build. Log.e is deliberately kept:
# a crash report with no error line is worse than a slightly larger APK.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** isLoggable(...);
}

# Line numbers make a stack trace readable; the original file names add nothing
# once the classes are renamed.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Optional platform pieces these libraries reference but never reach on Android.
-dontwarn androidx.media3.**
-dontwarn kotlinx.coroutines.**
