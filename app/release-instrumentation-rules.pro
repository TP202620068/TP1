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
-keepclassmembers,allowoptimization class com.example.tp1.extraction.AnyDocNative {
    public static com.example.tp1.extraction.AnyDocNative INSTANCE;
    public com.example.tp1.extraction.NativeConversion convert(java.lang.String, java.lang.String, java.lang.String, com.example.tp1.extraction.ExtractionLimits);
}
-keep,allowoptimization class com.example.tp1.extraction.ExtractionLimits { public <init>(...); }
-keep,allowoptimization class com.example.tp1.extraction.ExtractedDocument {
    public <init>(java.lang.String, java.lang.String, java.lang.String);
    public java.lang.String getMarkdown();
    public java.lang.String getDisplayName();
    public java.lang.String getInputSha256();
}
-keep,allowoptimization class com.example.tp1.extraction.ExtractionFailure {
    public <init>(java.lang.String, java.lang.String, java.lang.String, long[], long);
    public <init>(java.lang.String, java.lang.String, java.lang.String, long[], long, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public java.lang.String getCode();
    public long[] getOcrPages();
    public long getPageCount();
}
-keep,allowoptimization class com.example.tp1.extraction.ExtractionWork {
    public static com.example.tp1.extraction.ExtractionWork INSTANCE;
    public java.io.File operations(android.content.Context);
    public kotlinx.coroutines.sync.Mutex getPermit();
    public java.lang.Object getSourceLock();
    public void requireSavedRead(android.content.Context, com.example.tp1.sources.SourceRecord);
}
# The restart fixture resets only this process-local initialization guard.
-keepclassmembers class com.example.tp1.extraction.ExtractionWork { private static boolean initialized; }

# Separate test APK roots. Native registration has its own production rules.
-keep @interface com.example.tp1.inference.ManualInference
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep,allowoptimization class com.example.tp1.inference.LlamaEngine {
    public <init>(android.content.Context);
    public <init>(com.example.tp1.inference.ModelManager);
    public int getPhase();
    public boolean getTestPaused();
    public java.lang.Object load(com.example.tp1.inference.Model, kotlin.coroutines.Continuation);
    public java.lang.Object loadEmbedding(int, kotlin.coroutines.Continuation);
    public java.lang.Object embed(byte[], long, kotlin.coroutines.Continuation);
    public static java.lang.Object embed$default(com.example.tp1.inference.LlamaEngine, byte[], long, kotlin.coroutines.Continuation, int, java.lang.Object);
    public java.lang.Object countEmbeddingTokens(byte[], kotlin.coroutines.Continuation);
    public java.lang.Object withEmbeddingTokenCounter(kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
    public java.lang.Object testEmbeddingTokens(byte[], kotlin.coroutines.Continuation);
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
    public static com.example.tp1.inference.Model JinaV5NanoRetrieval;
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
    public com.example.tp1.inference.ModelProfile profile$app(com.example.tp1.inference.Model);
    public java.lang.Object isAvailable(com.example.tp1.inference.Model, kotlin.coroutines.Continuation);
    public java.lang.Object download(com.example.tp1.inference.Model, kotlin.coroutines.Continuation);
}
-keep,allowoptimization class com.example.tp1.inference.InferenceSession {
    public static com.example.tp1.inference.InferenceSession$Companion Companion;
    public com.example.tp1.inference.Model getModel();
    public java.lang.Object infer(java.lang.String, kotlin.coroutines.Continuation);
    public java.lang.Object prepare(java.lang.String, java.util.List, kotlin.coroutines.Continuation);
    public java.lang.Object countPassageTokens(java.lang.String, kotlin.coroutines.Continuation);
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
-keep,allowoptimization class com.example.tp1.inference.EmbeddingSession {
    public int getMaxInputTokens();
    public java.lang.Object embedDocument(java.lang.String, kotlin.coroutines.Continuation);
    public java.lang.Object embedQuery(java.lang.String, kotlin.coroutines.Continuation);
    public java.lang.Object countDocumentTokens(java.lang.String, kotlin.coroutines.Continuation);
    public java.lang.Object chunkDocument(java.lang.String, kotlin.coroutines.Continuation);
    public void cancel();
    public kotlinx.coroutines.Deferred closeAsync();
    public java.lang.Object close(kotlin.coroutines.Continuation);
    public static com.example.tp1.inference.EmbeddingSession$Companion Companion;
}
-keep,allowoptimization class com.example.tp1.inference.EmbeddingSession$Companion {
    public java.lang.Object open(com.example.tp1.inference.ModelManager, int, kotlin.coroutines.Continuation);
    public static java.lang.Object open$default(com.example.tp1.inference.EmbeddingSession$Companion, com.example.tp1.inference.ModelManager, int, kotlin.coroutines.Continuation, int, java.lang.Object);
    public java.lang.Object open$app(com.example.tp1.inference.LlamaEngine, int, kotlin.coroutines.Continuation);
    public static java.lang.Object open$app$default(com.example.tp1.inference.EmbeddingSession$Companion, com.example.tp1.inference.LlamaEngine, int, kotlin.coroutines.Continuation, int, java.lang.Object);
}
-keep,allowoptimization class com.example.tp1.inference.EmbeddingSessionKt {
    public static byte[] embeddingInput(java.lang.String, boolean);
}
-keep,allowoptimization class com.example.tp1.extraction.MarkdownChunk {
    public java.lang.String getText();
    public int getStartByte();
    public int getEndByte();
    public int getCoreStartByte();
    public int getCoreEndByte();
    public int getCoreCharacters();
    public int getInputTokens();
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
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function0, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function2);
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function0, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function2, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public kotlinx.coroutines.flow.StateFlow getState();
    public void editDraft(java.lang.String);
    public void setSelected(boolean);
    public void setForeground(boolean);
    public void prepare();
    public void send();
    public void stop();
    public void retry();
    public void openDocument(com.example.tp1.browser.DocumentReference, kotlin.jvm.functions.Function1);
}
-keep,allowoptimization class com.example.tp1.chat.ChatConnection {
    public <init>(kotlin.jvm.functions.Function3, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function0, kotlin.jvm.functions.Function0, kotlin.jvm.functions.Function2);
}
-keep,allowoptimization class com.example.tp1.chat.ChatState {
    public <init>(java.lang.String, java.util.List, com.example.tp1.chat.ChatPreparation, com.example.tp1.chat.ChatPhase, com.example.tp1.chat.ChatProblem, boolean, com.example.tp1.chat.ChatOpenProblem, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public static com.example.tp1.chat.ChatState copy$default(com.example.tp1.chat.ChatState, java.lang.String, java.util.List, com.example.tp1.chat.ChatPreparation, com.example.tp1.chat.ChatPhase, com.example.tp1.chat.ChatProblem, boolean, com.example.tp1.chat.ChatOpenProblem, int, java.lang.Object);
    # The separately compiled Compose test reads the compiler-generated stability field.
    public static final int $stable;
    public java.lang.String getDraft();
    public java.util.List getTurns();
    public com.example.tp1.chat.ChatPreparation getPreparation();
    public com.example.tp1.chat.ChatPhase getPhase();
    public com.example.tp1.chat.ChatProblem getProblem();
    public boolean getCanSend();
    public boolean getOpeningDocument();
    public com.example.tp1.chat.ChatOpenProblem getOpenProblem();
}
-keep,allowoptimization class com.example.tp1.chat.ChatTurn {
    public <init>(long, java.lang.String, java.lang.String, boolean, com.example.tp1.inference.FinishReason, boolean, java.util.List, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public static com.example.tp1.chat.ChatTurn copy$default(com.example.tp1.chat.ChatTurn, long, java.lang.String, java.lang.String, boolean, com.example.tp1.inference.FinishReason, boolean, java.util.List, int, java.lang.Object);
    public long getId();
    public java.lang.String getInput();
    public java.util.List getDocuments();
    public boolean getCanRetry();
    public java.lang.String getAnswer();
    public boolean getRunning();
    public boolean getFailed();
    public com.example.tp1.inference.FinishReason getFinish();
    public boolean getReusable();
}
-keep,allowoptimization class com.example.tp1.chat.ChatStateKt {
    public static java.util.List replayMessages(java.util.List);
}
-keep,allowoptimization enum com.example.tp1.chat.ChatPreparation { public static com.example.tp1.chat.ChatPreparation *; }
-keep,allowoptimization enum com.example.tp1.chat.ChatPhase { public static com.example.tp1.chat.ChatPhase *; }
-keep,allowoptimization enum com.example.tp1.chat.ChatProblem { public static com.example.tp1.chat.ChatProblem *; }

# The new indexing APIs are roots only for the separately compiled instrumentation APK.
-keep,allowoptimization class com.example.tp1.indexing.DocumentIndexer {
    public <init>(android.content.Context, com.example.tp1.inference.ModelManager, int, kotlin.jvm.functions.Function3, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function0, kotlin.jvm.functions.Function1);
    public <init>(android.content.Context, com.example.tp1.inference.ModelManager, int, kotlin.jvm.functions.Function3, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function0, kotlin.jvm.functions.Function1, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public java.lang.Object index(com.example.tp1.sources.SelectedSource, com.example.tp1.browser.BrowserEntry, boolean, kotlin.coroutines.Continuation);
    public static java.lang.Object index$default(com.example.tp1.indexing.DocumentIndexer, com.example.tp1.sources.SelectedSource, com.example.tp1.browser.BrowserEntry, boolean, kotlin.coroutines.Continuation, int, java.lang.Object);
}
-keep,allowoptimization class com.example.tp1.indexing.IndexEmbeddings { public <init>(...); }
-keep,allowoptimization interface com.example.tp1.indexing.IndexResult
-keep,allowoptimization class com.example.tp1.indexing.IndexResult$Complete {
    public <init>(long, java.lang.String, int, boolean);
    public long getDocumentId();
    public java.lang.String getGeneration();
    public int getChunks();
    public boolean getUnchanged();
}
-keep,allowoptimization class com.example.tp1.indexing.IndexStore {
    public <init>(...);
    public static com.example.tp1.indexing.IndexStore$Companion Companion;
    public com.example.tp1.indexing.IndexStore$Ticket request(com.example.tp1.sources.SelectedSource, com.example.tp1.browser.BrowserEntry);
    public void checkCurrent(com.example.tp1.indexing.IndexStore$Ticket);
    public com.example.tp1.indexing.IndexStore$Candidate stage(com.example.tp1.indexing.IndexStore$Ticket);
    public long append(com.example.tp1.indexing.IndexStore$Candidate, int, com.example.tp1.extraction.MarkdownChunk, float[], int);
    public void publish(com.example.tp1.indexing.IndexStore$Candidate, com.example.tp1.indexing.IndexIdentity, int, kotlin.coroutines.CoroutineContext);
    public int discard(com.example.tp1.indexing.IndexStore$Candidate);
    public com.example.tp1.indexing.IndexSnapshot read(com.example.tp1.sources.SelectedSource, com.example.tp1.browser.BrowserEntry);
    public void close();
}
-keep,allowoptimization class com.example.tp1.indexing.IndexStore$Companion {
    public java.io.File databaseFile(android.content.Context);
}
-keep,allowoptimization class com.example.tp1.indexing.IndexStore$Candidate {
    public long getDocumentId();
    public java.lang.String getGeneration();
}
-keep,allowoptimization class com.example.tp1.indexing.IndexStore$Ticket
-keep,allowoptimization class com.example.tp1.indexing.IndexSnapshot {
    public long getDocumentId();
    public java.lang.String getGeneration();
    public com.example.tp1.indexing.IndexIdentity getIdentity();
    public java.util.List getChunks();
}
-keep,allowoptimization class com.example.tp1.indexing.IndexIdentity {
    public <init>(java.lang.String, java.lang.String, java.lang.String, int);
    public java.lang.String getDisplayName();
    public java.lang.String getInputSha256();
    public java.lang.String getModelSha256();
    public int getMaxInputTokens();
}
-keep,allowoptimization class com.example.tp1.indexing.IndexedChunk {
    public int getOrdinal();
    public java.lang.String getText();
    public int getInputTokens();
    public float[] getVector();
    public int getStartByte();
    public int getEndByte();
}
-keep,allowoptimization class com.example.tp1.inference.NativeWorkHandoff {
    public static com.example.tp1.inference.NativeWorkHandoff INSTANCE;
    public boolean getBlocked();
    public kotlinx.coroutines.sync.Mutex getDocuments();
    public java.lang.Object acquire(java.lang.Object, boolean, kotlin.coroutines.Continuation);
    public static java.lang.Object acquire$default(com.example.tp1.inference.NativeWorkHandoff, java.lang.Object, boolean, kotlin.coroutines.Continuation, int, java.lang.Object);
    public java.lang.Object release(java.lang.Object, kotlin.coroutines.Continuation);
}
-keep,allowoptimization class com.example.tp1.extraction.MarkdownChunk {
    public <init>(java.lang.String, int, int, int, int, int, int);
    public static com.example.tp1.extraction.MarkdownChunk copy$default(com.example.tp1.extraction.MarkdownChunk, java.lang.String, int, int, int, int, int, int, int, java.lang.Object);
    public boolean equals(java.lang.Object);
}

# Retrieval entry points exercised by the separate test APK, not production JNI rules.
-keep,allowoptimization class com.example.tp1.indexing.DocumentRetriever {
    public <init>(...);
    public java.lang.Object retrieve(java.lang.String, int, kotlin.coroutines.Continuation);
    public java.lang.Object searchFiles(java.lang.String, double, kotlin.coroutines.Continuation);
    public java.lang.Object checkContext(java.util.List, kotlin.coroutines.Continuation);
    public static java.lang.Object retrieve$default(com.example.tp1.indexing.DocumentRetriever, java.lang.String, int, kotlin.coroutines.Continuation, int, java.lang.Object);
}
-keep,allowoptimization class com.example.tp1.indexing.RetrievalEmbeddings { public <init>(...); }
-keep,allowoptimization class com.example.tp1.indexing.RetrievalPolicyKt {
    public static java.util.List selectRagMatches(java.util.List);
}
-keep,allowoptimization interface com.example.tp1.indexing.RetrievalResult
-keep,allowoptimization class com.example.tp1.indexing.RetrievalResult$Complete {
    public <init>(java.util.List);
    public java.util.List getMatches();
}
-keep,allowoptimization class com.example.tp1.indexing.RetrievalResult$Busy { public static com.example.tp1.indexing.RetrievalResult$Busy INSTANCE; }
-keep,allowoptimization class com.example.tp1.indexing.RetrievalMatch {
    public <init>(java.lang.String, double, long, java.lang.String, int, java.lang.String, java.lang.String, java.lang.String, long, int, int);
    public <init>(java.lang.String, double, long, java.lang.String, int, java.lang.String, java.lang.String, java.lang.String, long, int, int, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public static com.example.tp1.indexing.RetrievalMatch copy$default(com.example.tp1.indexing.RetrievalMatch, java.lang.String, double, long, java.lang.String, int, java.lang.String, java.lang.String, java.lang.String, long, int, int, int, java.lang.Object);
    public java.lang.String getText();
    public double getScore();
    public long getDocumentId();
    public java.lang.String getGeneration();
    public int getOrdinal();
    public java.lang.String getDisplayName();
    public java.lang.String getSourceUri();
    public java.lang.String getDocumentUri();
    public int getStartByte();
    public int getEndByte();
    public boolean equals(java.lang.Object);
}
-keep,allowoptimization class com.example.tp1.browser.DocumentReference {
    public <init>(java.lang.String, java.lang.String, java.lang.String, java.util.List);
    public static com.example.tp1.browser.DocumentReference copy$default(com.example.tp1.browser.DocumentReference, java.lang.String, java.lang.String, java.lang.String, java.util.List, int, java.lang.Object);
    public java.lang.String getAuthority();
    public java.lang.String getProviderId();
    public java.lang.String getName();
    public java.util.List getAlternatives();
}
-keep,allowoptimization class com.example.tp1.chat.PreparedChatRequest {
    public <init>(java.util.List, java.util.List);
    public java.util.List getDocuments();
    public java.util.List getMessages();
    public java.util.List getIncluded();
}
-keep,allowoptimization class com.example.tp1.chat.ChatContextKt {
    public static java.lang.Object prepareChatRequest(java.lang.String, java.util.List, java.util.List, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function3, kotlin.coroutines.Continuation);
}
-keep,allowoptimization class com.example.tp1.browser.DocumentReferenceKt {
    public static java.lang.Object documentIntent(android.content.Context, com.example.tp1.browser.DocumentReference, kotlin.coroutines.Continuation);
}

# Search constructors, state and helpers called by the separate test APK.
-keep,allowoptimization class com.example.tp1.search.SearchViewModel {
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function2);
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function2, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public kotlinx.coroutines.flow.StateFlow getState();
    public void editDraft(java.lang.String);
    public void submit();
    public void retry();
    public void setSelected(boolean);
    public void setForeground(boolean);
    public void setSources(java.util.List, boolean);
    public void setIndexing(com.example.tp1.indexing.IndexingState);
    public void exitResults();
    public void clear();
    public void openDocument(com.example.tp1.browser.DocumentReference, kotlin.jvm.functions.Function1);
}
-keep,allowoptimization class com.example.tp1.search.SearchState {
    public <init>(java.lang.String, java.lang.String, long, com.example.tp1.search.SearchPhase, java.util.List, boolean, com.example.tp1.search.SearchProblem, boolean, com.example.tp1.browser.BrowserProblem, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public static com.example.tp1.search.SearchState copy$default(com.example.tp1.search.SearchState, java.lang.String, java.lang.String, long, com.example.tp1.search.SearchPhase, java.util.List, boolean, com.example.tp1.search.SearchProblem, boolean, com.example.tp1.browser.BrowserProblem, int, java.lang.Object);
    public static final int $stable;
    public java.lang.String getDraft();
    public java.lang.String getSubmitted();
    public com.example.tp1.search.SearchPhase getPhase();
    public java.util.List getRows();
    public boolean getInputInvalid();
    public com.example.tp1.search.SearchProblem getProblem();
    public boolean getOpening();
    public com.example.tp1.browser.BrowserProblem getOpenProblem();
    public boolean getShowingResults();
}
-keep,allowoptimization class com.example.tp1.search.SearchRow {
    public <init>(com.example.tp1.browser.DocumentReference, java.lang.String);
    public com.example.tp1.search.SearchRow copy(com.example.tp1.browser.DocumentReference, java.lang.String);
    public com.example.tp1.browser.DocumentReference getDocument();
    public java.lang.String getExcerpt();
    public boolean equals(java.lang.Object);
}
-keep,allowoptimization class com.example.tp1.search.SearchStateKt {
    public static java.util.List searchRows(java.util.List);
}
-keep,allowoptimization enum com.example.tp1.search.SearchPhase { public static com.example.tp1.search.SearchPhase *; }
-keep,allowoptimization enum com.example.tp1.search.SearchProblem {
    public static com.example.tp1.search.SearchProblem *;
    public static com.example.tp1.search.SearchProblem[] values();
    public static kotlin.enums.EnumEntries getEntries();
}
-keep,allowoptimization enum com.example.tp1.chat.ChatOpenProblem { public static com.example.tp1.chat.ChatOpenProblem *; }
-keep,allowoptimization class com.example.tp1.inference.ModelArtifact { public java.lang.String getSha256(); }

# Automatic indexing uses concrete seams; keep only members called by the separate APK.
-keep,allowoptimization class com.example.tp1.indexing.IndexingCoordinator {
    public static com.example.tp1.indexing.IndexingCoordinator$Companion Companion;
    public <init>(kotlinx.coroutines.CoroutineScope, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function3, kotlin.jvm.functions.Function4, kotlin.jvm.functions.Function1);
    public <init>(kotlinx.coroutines.CoroutineScope, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function3, kotlin.jvm.functions.Function4, kotlin.jvm.functions.Function1, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public kotlinx.coroutines.flow.StateFlow getState();
    public void restore(java.util.List);
    public void restoreError();
    public void admit(java.util.List);
    public void retry(java.lang.String);
    public void cancelSource(java.lang.String);
    public java.lang.Object close(kotlin.coroutines.Continuation);
}
-keep,allowoptimization class com.example.tp1.indexing.IndexingCoordinator$Companion {
    public com.example.tp1.indexing.IndexingCoordinator get(android.content.Context);
    public com.example.tp1.indexing.IndexingCoordinator replaceForTests(com.example.tp1.indexing.IndexingCoordinator);
}
-keep,allowoptimization class com.example.tp1.indexing.IndexingState {
    public static final int $stable;
    public <init>(java.util.Map, com.example.tp1.indexing.IndexingProblem);
    public <init>(java.util.Map, com.example.tp1.indexing.IndexingProblem, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public java.util.Map getSources();
    public com.example.tp1.indexing.IndexingProblem getBlocked();
}
-keep,allowoptimization class com.example.tp1.indexing.SourceIndexingState {
    public <init>(boolean, int, boolean, boolean, int, java.util.List, boolean, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public static com.example.tp1.indexing.SourceIndexingState copy$default(com.example.tp1.indexing.SourceIndexingState, boolean, int, boolean, boolean, int, java.util.List, boolean, int, java.lang.Object);
    public boolean getDiscovering();
    public int getPending();
    public boolean getRunning();
    public int getCompleted();
    public boolean getIncomplete();
    public boolean getWaitingForChat();
    public java.util.List getFailures();
}
-keep,allowoptimization class com.example.tp1.indexing.IndexingFailure {
    public <init>(java.lang.String, com.example.tp1.indexing.IndexingProblem);
    public com.example.tp1.indexing.IndexingProblem getProblem();
}
-keep,allowoptimization enum com.example.tp1.indexing.IndexingProblem { public static com.example.tp1.indexing.IndexingProblem *; }
-keep,allowoptimization class com.example.tp1.indexing.IndexModelFailure { public <init>(java.lang.Exception); }
-keep,allowoptimization class com.example.tp1.indexing.IndexResult$Busy { public static com.example.tp1.indexing.IndexResult$Busy INSTANCE; }
-keep,allowoptimization class com.example.tp1.inference.ModelManager {
    public java.io.File location$app(com.example.tp1.inference.Model);
}

# Process acquisition is replaced before Activity launch by offline instrumentation.
-keep,allowoptimization class com.example.tp1.inference.ModelAcquisition {
    public static com.example.tp1.inference.ModelAcquisition$Companion Companion;
    public <init>(kotlinx.coroutines.CoroutineScope, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function2);
    public <init>(kotlinx.coroutines.CoroutineScope, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function2, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public void start();
    public java.lang.Object acquire(com.example.tp1.inference.Model, kotlin.coroutines.Continuation);
    public java.lang.Object close(kotlin.coroutines.Continuation);
}
-keep,allowoptimization class com.example.tp1.inference.ModelAcquisition$Companion {
    public com.example.tp1.inference.ModelAcquisition get(android.content.Context);
    public com.example.tp1.inference.ModelAcquisition replaceForTests(com.example.tp1.inference.ModelAcquisition);
}
