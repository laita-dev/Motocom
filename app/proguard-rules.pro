# Names stay readable: the record from a phone far away shows exception and class names.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Crucial: com.motocom.shell is executed out-of-process via ADB 'app_process'
-keep class com.motocom.shell.** { *; }
-keepclassmembers class com.motocom.shell.ShellMain {
    public static void main(java.lang.String[]);
}

# AIDL interfaces and generated Binder stubs
-keep interface com.motocom.shell.IBikeDisplay { *; }
-keep class com.motocom.shell.IBikeDisplay$* { *; }
-keep interface com.motocom.shell.IShellLog { *; }
-keep class com.motocom.shell.IShellLog$* { *; }

# Security and Crypto libraries (BouncyCastle & Conscrypt use reflection for JCE providers)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# Pairing with ADB: native code bound by name, and Conscrypt looked up by name in libadb.
-keep class io.github.muntashirakon.** { *; }
-dontwarn io.github.muntashirakon.**

# ZXing QR scanning
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.journeyapps.barcodescanner.**
-dontwarn com.google.zxing.**

# Hidden / Internal Android framework APIs used via reflection in DisplayService & ShellMain
-dontwarn android.companion.virtual.**
-dontwarn android.hardware.display.**
-dontwarn android.app.**
