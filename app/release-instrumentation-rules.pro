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
    public kotlinx.coroutines.sync.Mutex getPermit();
}

# Separate test APK roots. Native registration has its own production rules.
-keep @interface com.example.tp1.inference.ManualInference
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep,allowoptimization class com.example.tp1.inference.LlamaEngine {
    public <init>(android.content.Context);
    public int getPhase();
    public boolean getTestPaused();
    public java.lang.Object load(kotlin.coroutines.Continuation);
    public kotlinx.coroutines.flow.Flow generate(java.lang.String, java.lang.String, int, int, long);
    public static kotlinx.coroutines.flow.Flow generate$default(com.example.tp1.inference.LlamaEngine, java.lang.String, java.lang.String, int, int, long, int, java.lang.Object);
    public java.lang.Object formatPrompt(java.lang.String, java.lang.String, kotlin.coroutines.Continuation);
    public java.lang.Object tokenCount(java.lang.String, java.lang.String, kotlin.coroutines.Continuation);
    public void cancel();
    public void testControl(int, boolean, boolean);
    public static void testControl$default(com.example.tp1.inference.LlamaEngine, int, boolean, boolean, int, java.lang.Object);
    public java.lang.Object reset(kotlin.coroutines.Continuation);
    public java.lang.Object unload(kotlin.coroutines.Continuation);
    public kotlinx.coroutines.Deferred closeAsync();
}
-keep,allowoptimization class com.example.tp1.inference.ModelFile {
    public static com.example.tp1.inference.ModelFile INSTANCE;
    public java.io.File location(android.content.Context);
    public java.io.File provision(android.content.Context, java.io.InputStream);
}
-keep,allowoptimization interface com.example.tp1.inference.GenerationEvent
-keep,allowoptimization class com.example.tp1.inference.GenerationEvent$Started {
    public int getInputTokens();
    public int getBosCount();
    public int getContextTokens();
    public int getBatchTokens();
    public int getMicroBatchTokens();
    public int getThreads();
}
-keep,allowoptimization class com.example.tp1.inference.GenerationEvent$Text {
    public java.lang.String getValue();
}
-keep,allowoptimization class com.example.tp1.inference.GenerationEvent$Finished {
    public com.example.tp1.inference.FinishReason getReason();
    public int getGeneratedTokens();
    public int getIncompleteUtf8Bytes();
    public java.lang.String getDetail();
}
-keep,allowoptimization enum com.example.tp1.inference.FinishReason {
    public static com.example.tp1.inference.FinishReason *;
}
