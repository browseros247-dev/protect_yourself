# ProGuard rules for protect.yourself

# --- General ---
-dontwarn org.jetbrains.annotations.**
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# --- Kotlin ---
-dontwarn kotlin.**
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlin.Metadata { *; }

# --- Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# --- Firebase ---
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# --- Lottie ---
-keep class com.airbnb.lottie.** { *; }
-dontwarn com.airbnb.lottie.**

# --- Image Cropper (CanHub) ---
-keep class com.canhub.cropper.** { *; }
-dontwarn com.canhub.cropper.**

# --- Joda-Time ---
-keep class org.joda.time.** { *; }
-dontwarn org.joda.time.**

# --- Gson ---
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- App data models (Room entities, Parcelable) ---
-keep class protect.yourself.database.** { *; }
-keep class protect.yourself.features.**.data.** { *; }
-keepclassmembers class protect.yourself.database.** {
    *;
}

# --- Splitties ---
-keep class splitties.** { *; }
-dontwarn splitties.**

# --- Signature killer (vendor) ---
-keep class protect.yourself.commons.signaturekiller.** { *; }

# --- Timber ---
-dontwarn org.jetbrains.annotations.**

# --- WorkManager ---
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# --- Keep BuildConfig ---
-keep class protect.yourself.BuildConfig { *; }

# =====================================================================
# PERF-01 (v1.0.67) — rules required because release R8 is now enabled.
# Every Kotlin/Java reflection entry point in the app is covered below;
# guarded by ProguardRulesRegressionTest.
# =====================================================================

# --- Gson + Room TypeConverters ---
# Enum CONSTANT NAMES are data: Gson serializes enums by name() and Room
# TypeConverters persist them by name(). Renaming would silently corrupt
# backup files, crash logs, and DB values. Restricted to our package.
-keepclassmembers enum protect.yourself.** { *; }

# --- CrashLogger models (serialized to / parsed from JSON via Gson) ---
-keep class protect.yourself.features.crashLog.CrashLogEntry { *; }
-keep class protect.yourself.features.crashLog.ServiceStateInfo { *; }
-keep class protect.yourself.features.crashLog.DeviceInfo { *; }
-keep class protect.yourself.features.crashLog.AppInfo { *; }
-keep class protect.yourself.features.crashLog.MemoryInfo { *; }
-keep class protect.yourself.features.crashLog.DiskInfo { *; }
-keep class protect.yourself.features.crashLog.Breadcrumb { *; }
-keep class protect.yourself.features.crashLog.CrashLogExport { *; }

# --- BackupRestore models (BackupManager Gson envelope; field names = the
#     backup file schema — renaming breaks backup compatibility) ---
-keep class protect.yourself.features.backupRestore.BackupEnvelope { *; }
-keep class protect.yourself.features.backupRestore.BackupTables { *; }
-keep class protect.yourself.features.backupRestore.BackupTablesContainer { *; }
-keep class protect.yourself.features.backupRestore.BackupStats { *; }
-keep class protect.yourself.features.backupRestore.RestoredCounts { *; }

# --- Room entities: BackupManager serializes ENTITIES directly via Gson, so
#     entity FIELD names are also part of the backup schema. The class-level
#     keep above is not enough — preserve members as well.
-keepclassmembers @androidx.room.Entity class * { *; }

# --- WorkManager ---
# The default WorkerFactory instantiates workers REFLECTIVELY by persisted
# class name and calls the (Context, WorkerParameters) constructor.
-keepnames class * extends androidx.work.ListenableWorker
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
