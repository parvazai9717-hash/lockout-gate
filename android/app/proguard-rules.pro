# Security & R8 Obfuscation Keep Rules for LockoutGate

# Keep Accessibility Service
-keep class com.lockout.gate.AppBlockAccessibilityService { *; }

# Strip Android debug log statements in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
