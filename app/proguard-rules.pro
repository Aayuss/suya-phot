# Suya Phot Proguard / R8 rules

# Keep Room generated code
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep Media3 ExoPlayer classes
-keep class androidx.media3.** { *; }

# Keep CameraX
-keep class androidx.camera.** { *; }

# Keep Biometric
-keep class androidx.biometric.** { *; }

# Keep Coil
-keep class coil.** { *; }

# Do not log sensitive details in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
