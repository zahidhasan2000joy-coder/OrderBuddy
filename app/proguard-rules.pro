# ProGuard and R8 rules for Paircart
-keepattributes *Annotation*,Signature
-repackageclasses 'p'
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute P

# Strip verbose debug/info/warning logs from release binaries
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# Preserve all @Keep annotated classes and constructors
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Keep Firestore data models serialized from/to cloud via toObject()
-keep class com.example.data.model.** { *; }
-keepclassmembers class com.example.data.model.** { *; }

# Keep only Manifest-registered Android entry points (not internal helpers/managers)
-keep class com.example.PaircartApplication
-keep class com.example.MainActivity
-keep class com.example.service.NotificationJobService
-keep class com.example.service.NotificationPollReceiver
-keep class com.example.service.BootReceiver
-keep class com.example.service.OrderLifecycleAlarmReceiver
-keep class com.example.util.ShoppingReminderReceiver

# Firebase Auth, Firestore, App Check, Credentials, GoogleID, and Coroutines
# ship their own consumer ProGuard rules in their AARs; avoid wildcard -keep
# rules so R8 can tree-shake unused SDK classes and shrink/obfuscate classes.dex.

# Dontwarn suppressions for missing build-time annotations / references
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
-dontwarn androidx.credentials.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn java.lang.instrument.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn com.google.common.**
-dontwarn sun.misc.Unsafe
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
