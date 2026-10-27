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
    public <init>(com.example.tp1.inference.ModelManager);
    public int getPhase();
    public boolean getTestPaused();
    public java.lang.Object load(com.example.tp1.inference.Model, kotlin.coroutines.Continuation);
    public static java.lang.Object load$default(com.example.tp1.inference.LlamaEngine, com.example.tp1.inference.Model, kotlin.coroutines.Continuation, int, java.lang.Object);
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
    public java.io.File provision(java.io.File, com.example.tp1.inference.ModelArtifact, java.io.InputStream, kotlin.jvm.functions.Function0);
}
-keep,allowoptimization enum com.example.tp1.inference.Model {
    public static com.example.tp1.inference.Model Test;
    public com.example.tp1.inference.ModelArtifact getArtifact$app();
}
-keep,allowoptimization class com.example.tp1.inference.ModelArtifact {
    public <init>(java.lang.String, java.lang.String, long, java.lang.String);
    public java.lang.String getName();
    public long getBytes();
}
-keep,allowoptimization class com.example.tp1.inference.ModelManager {
    public <init>(android.content.Context);
    public <init>(java.io.File, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function1);
    public <init>(java.io.File, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function1, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public java.io.File location$app(com.example.tp1.inference.Model);
    public java.lang.Object isAvailable(com.example.tp1.inference.Model, kotlin.coroutines.Continuation);
    public java.lang.Object download(com.example.tp1.inference.Model, kotlin.coroutines.Continuation);
}
-keep,allowoptimization class com.example.tp1.inference.InferenceSession {
    public static com.example.tp1.inference.InferenceSession$Companion Companion;
    public com.example.tp1.inference.Model getModel();
    public java.lang.Object infer(java.lang.String, kotlin.coroutines.Continuation);
    public void cancel();
    public java.lang.Object close(kotlin.coroutines.Continuation);
}
-keep,allowoptimization class com.example.tp1.inference.InferenceSession$Companion {
    public java.lang.Object open(com.example.tp1.inference.ModelManager, com.example.tp1.inference.Model, kotlin.coroutines.Continuation);
    public java.lang.Object open$app(com.example.tp1.inference.Model, com.example.tp1.inference.LlamaEngine, kotlin.coroutines.Continuation);
}
-keep,allowoptimization class com.example.tp1.inference.InferenceResponse {
    public java.lang.String getText();
    public com.example.tp1.inference.FinishReason getFinishReason();
    public int getGeneratedTokens();
    public int getIncompleteUtf8Bytes();
    public java.lang.String getDetail();
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
