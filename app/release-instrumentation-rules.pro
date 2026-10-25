# Test-APK calls are roots only for the opt-in release instrumentation build.
# Keep those Kotlin entry points callable while still allowing body optimization.
# Production JNI names/constructors are preserved independently in proguard-rules.pro.
# Error Prone's compile-time annotation references this JDK-only enum.
-dontwarn javax.lang.model.element.Modifier
-keep,allowoptimization class com.example.tp1.extraction.DocumentExtractor {
    public <init>(...);
    public java.lang.Object extract(com.example.tp1.sources.SelectedSource, com.example.tp1.browser.BrowserEntry, kotlin.coroutines.Continuation);
}
-keep,allowoptimization class com.example.tp1.extraction.DocumentExtractorKt {
    public static java.lang.String hex(byte[]);
}
-keep,allowoptimization class com.example.tp1.extraction.ExtractionLimits { public <init>(...); }
-keep,allowoptimization class com.example.tp1.extraction.ExtractionArtifact {
    public java.io.File getMarkdown();
    public java.io.File getProvenance();
    public boolean equals(java.lang.Object);
}
-keep,allowoptimization class com.example.tp1.extraction.ExtractionFailure {
    public java.lang.String getCode();
    public long[] getOcrPages();
    public long getPageCount();
}
-keep,allowoptimization class com.example.tp1.extraction.ExtractionArtifacts {
    public static com.example.tp1.extraction.ExtractionArtifacts INSTANCE;
    public java.io.File operations(android.content.Context);
    public com.example.tp1.extraction.ExtractionArtifact current(android.content.Context, com.example.tp1.sources.SelectedSource, com.example.tp1.browser.BrowserEntry);
    public void removed(android.content.Context, java.lang.String, kotlin.jvm.functions.Function1);
}
