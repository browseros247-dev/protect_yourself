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
