# Keep UniFFI/JNA bindings
-keep class uniffi.alphamessenger.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# ── Gson (reflective (de)serialization must survive R8) ──────────────────
# R8 obfuscated MediaRefLite → Gson threw "Abstract classes can't be
# instantiated … Class name: ka.s5" → gson.fromJson(mediaRef) failed → EVERY
# media DOWNLOAD returned null (received photos/videos/files/voice never opened).
# Gson matches JSON keys to FIELD NAMES by reflection, so the model classes and
# their field names must NOT be renamed/removed.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# App models (de)serialized by Gson via reflection — keep name + fields + ctor.
-keep class com.alphamessenger.data.MediaRefLite { *; }

# Strip verbose logging from release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# In-app Tor: JNI entry points are looked up by name.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }

# Video player: the page calls these by name.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
