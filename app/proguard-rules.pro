# iTantra Proguard Rules
-keep class com.itantra.protocol.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
# JNI adapter holders: method names are the JNI symbol (nnTranslate et al.),
# must survive minification or the native calls fail with UnsatisfiedLinkError.
-keep class com.itantra.translation.OpusMtTranslationEngine { <methods>; }
