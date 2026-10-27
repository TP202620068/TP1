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
    public java.lang.Object formatPrompt(java.lang.String, java.util.List, kotlin.coroutines.Continuation);
    public java.lang.Object tokenCount(java.lang.String, java.lang.String, kotlin.coroutines.Continuation);
    public java.lang.Object tokenCount(java.lang.String, java.util.List, kotlin.coroutines.Continuation);
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
    public com.example.tp1.inference.ModelProfile getProfile$app();
}
-keep,allowoptimization class com.example.tp1.inference.ModelProfile {
    public <init>(com.example.tp1.inference.ModelArtifact, com.example.tp1.inference.ModelSampling, boolean);
    public com.example.tp1.inference.ModelArtifact getArtifact();
    public com.example.tp1.inference.ModelSampling getSampling();
    public static com.example.tp1.inference.ModelProfile copy$default(com.example.tp1.inference.ModelProfile, com.example.tp1.inference.ModelArtifact, com.example.tp1.inference.ModelSampling, boolean, int, java.lang.Object);
}
-keep,allowoptimization class com.example.tp1.inference.ModelSampling {
    public <init>(float, int, float, int, float, float);
    public static com.example.tp1.inference.ModelSampling copy$default(com.example.tp1.inference.ModelSampling, float, int, float, int, float, float, int, java.lang.Object);
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
    public java.lang.Object prepare(java.lang.String, java.util.List, kotlin.coroutines.Continuation);
    public static java.lang.Object prepare$default(com.example.tp1.inference.InferenceSession, java.lang.String, java.util.List, kotlin.coroutines.Continuation, int, java.lang.Object);
    public kotlinx.coroutines.flow.Flow stream(java.util.List);
    public void cancel();
    public kotlinx.coroutines.Deferred closeAsync();
    public java.lang.Object close(kotlin.coroutines.Continuation);
}
-keep,allowoptimization class com.example.tp1.inference.InferenceSession$Companion {
    public java.lang.Object open(com.example.tp1.inference.ModelManager, com.example.tp1.inference.Model, java.lang.String, kotlin.coroutines.Continuation);
    public static java.lang.Object open$default(com.example.tp1.inference.InferenceSession$Companion, com.example.tp1.inference.ModelManager, com.example.tp1.inference.Model, java.lang.String, kotlin.coroutines.Continuation, int, java.lang.Object);
    public java.lang.Object open$app(com.example.tp1.inference.Model, com.example.tp1.inference.LlamaEngine, java.lang.String, kotlin.coroutines.Continuation);
    public static java.lang.Object open$app$default(com.example.tp1.inference.InferenceSession$Companion, com.example.tp1.inference.Model, com.example.tp1.inference.LlamaEngine, java.lang.String, kotlin.coroutines.Continuation, int, java.lang.Object);
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
    public <init>(java.lang.String);
    public java.lang.String getValue();
}
-keep,allowoptimization class com.example.tp1.inference.GenerationEvent$Finished {
    public <init>(...);
    public com.example.tp1.inference.FinishReason getReason();
    public int getGeneratedTokens();
    public int getIncompleteUtf8Bytes();
    public java.lang.String getDetail();
}
-keep,allowoptimization enum com.example.tp1.inference.FinishReason {
    public static com.example.tp1.inference.FinishReason *;
}

-keep,allowoptimization class com.example.tp1.inference.ChatMessage {
    public <init>(com.example.tp1.inference.ChatRole, java.lang.String);
    public java.lang.String getText();
    public boolean equals(java.lang.Object);
}
-keep,allowoptimization enum com.example.tp1.inference.ChatRole {
    public static com.example.tp1.inference.ChatRole User;
    public static com.example.tp1.inference.ChatRole Assistant;
}
-keep,allowoptimization class com.example.tp1.inference.ChatMessageKt {
    public static java.lang.Object budgetChatMessages(java.lang.String, java.util.List, java.lang.String, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}
-keep,allowoptimization class com.example.tp1.chat.ChatViewModel {
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function1);
    public kotlinx.coroutines.flow.StateFlow getState();
    public void editDraft(java.lang.String);
    public void setSelected(boolean);
    public void setForeground(boolean);
    public void prepare();
    public void send();
    public void stop();
    public void retry();
}
-keep,allowoptimization class com.example.tp1.chat.ChatConnection {
    public <init>(kotlin.jvm.functions.Function3, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function0, kotlin.jvm.functions.Function0);
}
-keep,allowoptimization class com.example.tp1.chat.ChatState {
    # The separately compiled Compose test reads the compiler-generated stability field.
    public static final int $stable;
    public java.lang.String getDraft();
    public java.util.List getTurns();
    public com.example.tp1.chat.ChatPreparation getPreparation();
    public com.example.tp1.chat.ChatPhase getPhase();
    public com.example.tp1.chat.ChatProblem getProblem();
}
-keep,allowoptimization class com.example.tp1.chat.ChatTurn {
    public java.lang.String getAnswer();
    public boolean getRunning();
    public boolean getFailed();
    public com.example.tp1.inference.FinishReason getFinish();
}
-keep,allowoptimization class com.example.tp1.chat.ChatStateKt {
    public static java.util.List replayMessages(java.util.List);
}
-keep,allowoptimization enum com.example.tp1.chat.ChatPreparation { public static com.example.tp1.chat.ChatPreparation *; }
-keep,allowoptimization enum com.example.tp1.chat.ChatPhase { public static com.example.tp1.chat.ChatPhase *; }
-keep,allowoptimization enum com.example.tp1.chat.ChatProblem { public static com.example.tp1.chat.ChatProblem *; }
