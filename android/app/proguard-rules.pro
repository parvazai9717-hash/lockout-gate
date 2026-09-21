# Security & R8 Obfuscation Keep Rules for LockoutGate

# Keep Retrofit & Gson models
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Network Data Models
-keep class com.lockout.gate.network.** { *; }

# Keep Accessibility Service
-keep class com.lockout.gate.AppBlockAccessibilityService { *; }

# Strip Android debug log statements in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
