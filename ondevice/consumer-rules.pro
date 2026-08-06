# :ondevice consumer ProGuard 规则——随 library 分发给消费方（:app）。
# MNN JNI 按名字绑定（Java_包名_类名_方法名），且 cpp 靠 GetMethodID("onToken") 反射回调，
# release minify 必须保留这些类与方法，否则 UnsatisfiedLinkError / 回调失效。
-keep class com.matrix.agent.ondevice.mnn.MNNLlmNative { *; }
-keep class com.matrix.agent.ondevice.mnn.MNNLlmNative$GenerationCallback { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
