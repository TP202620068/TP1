# Name-based JNI entry point and the one constructor invoked from Rust.
-keep class com.example.tp1.extraction.AnyDocNative {
    private native com.example.tp1.extraction.NativeConversion convertNative(java.lang.String, java.lang.String, java.lang.String, long, long);
}
-keep class com.example.tp1.extraction.NativeConversion {
    public <init>(java.lang.String, java.lang.String, java.lang.String, long[], long);
}

# RegisterNatives finds this exact class and its private instance native methods.
-keep,allowoptimization class com.example.tp1.inference.LlamaEngine {
    private native <methods>;
}

# SavedStateViewModelFactory invokes this constructor reflectively.
-keepclassmembers,allowoptimization class com.example.tp1.chat.ChatViewModel {
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}
