# MAX Production Release ProGuard / R8 Rules

# Preserve source file & line numbers for production crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Room entities, DAOs, and generated Room database
-keep class androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class com.example.data.local.** { *; }
-keep class com.example.memory.data.** { *; }

# Preserve Moshi & Gson serialization data models
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class * {
    @com.squareup.moshi.Json(name=*) <fields>;
}
-keep class * implements com.google.gson.TypeAdapter { *; }
-keep class com.example.network.model.** { *; }
-keep class com.example.automation.agent.** { *; }
-keep class com.example.memory.universal.** { *; }

# Preserve Shizuku privileged IPC bindings
-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# Preserve Android Services, Receivers, and Accessibility
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.service.notification.NotificationListenerService
-keep public class * extends android.service.voice.VoiceInteractionService
-keep public class * extends android.speech.RecognitionService

# Preserve Jetpack Compose runtime and animation metadata
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.animation.** { *; }
