# Keep ONNX Runtime (not currently used directly, but harmless) and the JNI bridge.
-keep class ai.onnxruntime.** { *; }
-keep class com.deepfaceswap.android.NativeBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
