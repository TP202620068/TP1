# Name-based JNI entry point and the one constructor invoked from Rust.
-keep class com.example.tp1.extraction.AnyDocNative {
    private native com.example.tp1.extraction.NativeConversion convertNative(java.lang.String, java.lang.String, java.lang.String, long, long);
}
-keep class com.example.tp1.extraction.NativeConversion {
    public <init>(java.lang.String, java.lang.String, java.lang.String, long[], long);
}
