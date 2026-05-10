# Bidet AI ProGuard / R8 rules.
#
# v0.3 (2026-05-10): keep sherpa-onnx Kotlin bindings + their JNI surface so the
# native methods can be looked up by the .so at runtime even when minify is enabled
# (currently disabled in the release build, but defensive — flipping isMinifyEnabled
# without these rules silently kills sherpa-onnx initialization).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    native <methods>;
    public <init>(...);
}

# LiteRT-LM (gemma flavor) similarly resolves native symbols by reflection.
-keep class com.google.ai.edge.litertlm.** { *; }
