#include <jni.h>
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <string>
#include <thread>
#include <type_traits>
#include <utility>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr int context_limit = 4096;
constexpr int input_limit = 3584;
constexpr int batch_limit = 128;
constexpr size_t text_limit = 64 * 1024;
constexpr size_t output_limit = 1024 * 1024;
constexpr int eog = 1, length = 2, cancelled = 3, deadline = 4;
struct PendingException {};

void check_java(JNIEnv * env) { if (env->ExceptionCheck()) throw PendingException{}; }

void throw_java(JNIEnv * env, const char * type, const char * message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls != nullptr) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}

template<class F> auto boundary(JNIEnv * env, F action) -> decltype(action()) {
    using Result = decltype(action());
    try { return action(); }
    catch (const PendingException &) { /* Preserve the original pending Java exception. */ }
    catch (const std::bad_alloc &) { throw_java(env, "java/lang/OutOfMemoryError", "Inference allocation failed"); }
    catch (const std::invalid_argument & error) { throw_java(env, "java/lang/IllegalArgumentException", error.what()); }
    catch (const std::exception & error) { throw_java(env, "java/io/IOException", error.what()); }
    catch (...) { throw_java(env, "java/io/IOException", "Unknown native inference failure"); }
    if constexpr (!std::is_void_v<Result>) return Result{};
}

std::string bytes(JNIEnv * env, jbyteArray array, size_t limit) {
    if (array == nullptr) throw std::invalid_argument("Missing byte array");
    const auto count = env->GetArrayLength(array);
    check_java(env);
    if (static_cast<size_t>(count) > limit) throw std::invalid_argument("JNI input byte limit exceeded");
    std::string value(static_cast<size_t>(count), '\0');
    if (count != 0) env->GetByteArrayRegion(array, 0, count, reinterpret_cast<jbyte *>(value.data()));
    check_java(env);
    if (value.find('\0') != std::string::npos) throw std::invalid_argument("NUL is not valid in a C-string input");
    return value;
}

jbyteArray byte_array(JNIEnv * env, const char * data, size_t count) {
    if (count > output_limit + 1) throw std::runtime_error("JNI output byte limit exceeded");
    auto array = env->NewByteArray(static_cast<jsize>(count));
    check_java(env);
    if (array == nullptr) throw std::bad_alloc();
    if (count != 0) env->SetByteArrayRegion(array, 0, static_cast<jsize>(count), reinterpret_cast<const jbyte *>(data));
    check_java(env);
    return array;
}

jintArray int_array(JNIEnv * env, const std::vector<jint> & values) {
    auto array = env->NewIntArray(static_cast<jsize>(values.size()));
    check_java(env);
    if (array == nullptr) throw std::bad_alloc();
    env->SetIntArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    check_java(env);
    return array;
}

using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;

struct Owner {
    // Only stop and phase are touched outside the Kotlin-owned worker.
    std::atomic<bool> stop{false};
    std::atomic<int> phase{0};
    Model model{nullptr, llama_model_free};
    Context context{nullptr, llama_free};
    Sampler sampler{nullptr, llama_sampler_free};
    Clock::time_point expires = Clock::time_point::max();
    int threads = 1;
    int generated = 0;
    int maximum = 0;
    int position = 0;
    size_t output_bytes = 0;
#ifdef TP1_LLAMA_TEST_HOOKS
    std::atomic<int> pause_phase{0};
    std::atomic<bool> paused{false};
    bool force_length = false;
    bool fail_decode = false;
#endif

    int stopped() const {
        if (stop.load(std::memory_order_relaxed)) return cancelled;
        return Clock::now() >= expires ? deadline : 0;
    }
    void reset() {
        sampler.reset();
        if (context) llama_memory_clear(llama_get_memory(context.get()), true);
        generated = maximum = position = 0;
        output_bytes = 0;
        phase.store(0);
    }
    void unload() {
        sampler.reset();
        context.reset();
        model.reset();
        phase.store(0);
    }
};

Owner & owner(jlong handle) {
    if (handle == 0) throw std::invalid_argument("Closed native owner");
    // The private Kotlin handle is destroyed only after its single worker has stopped using it.
    return *reinterpret_cast<Owner *>(static_cast<intptr_t>(handle));
}

struct PhaseReset {
    Owner & value;
    ~PhaseReset() { value.phase.store(0); }
};

void checkpoint(Owner & value) {
#ifdef TP1_LLAMA_TEST_HOOKS
    const int current = value.phase.load();
    if (value.pause_phase.load() == current && current != 0) {
        value.paused.store(true);
        while (value.pause_phase.load() == current && value.stopped() == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        value.pause_phase.store(0);
    }
#else
    (void) value;
#endif
}
bool load_progress(float, void * data) {
    auto & value = *static_cast<Owner *>(data);
    checkpoint(value);
    return !value.stop.load();
}
bool abort_decode(void * data) {
    auto & value = *static_cast<Owner *>(data);
    checkpoint(value);
    return value.stopped() != 0;
}

std::string format(Owner & value, const std::string & system, const std::string & user) {
    if (!value.model) throw std::runtime_error("No model loaded");
    if (system.size() + user.size() > text_limit || user.empty()) throw std::invalid_argument("Invalid request size");
    const char * tmpl = llama_model_chat_template(value.model.get(), nullptr);
    if (tmpl == nullptr) throw std::runtime_error("Model has no chat template");
    std::vector<llama_chat_message> messages;
    if (!system.empty()) messages.push_back({"system", system.c_str()});
    messages.push_back({"user", user.c_str()});
    const int needed = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
    if (needed <= 0 || needed > static_cast<int>(text_limit + 4096)) {
        throw std::runtime_error("Unsupported or oversized chat template output");
    }
    std::vector<char> result(static_cast<size_t>(needed) + 1);
    const int count = llama_chat_apply_template(
        tmpl, messages.data(), messages.size(), true, result.data(), static_cast<int>(result.size())
    );
    if (count != needed) throw std::runtime_error("Chat template size changed");
    return std::string(result.data(), static_cast<size_t>(count));
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & prompt) {
    const int needed = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (needed == std::numeric_limits<int32_t>::min()) throw std::invalid_argument("Tokenization overflow");
    if (needed >= 0 || -needed > static_cast<int>(text_limit + 4096)) throw std::invalid_argument("Invalid tokenization capacity");
    std::vector<llama_token> tokens(static_cast<size_t>(-needed));
    const int count = llama_tokenize(
        vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true
    );
    if (count <= 0 || count > static_cast<int>(tokens.size())) throw std::runtime_error("Tokenization retry failed");
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

struct Batch {
    llama_batch value = llama_batch_init(batch_limit, 0, 1);
    ~Batch() { llama_batch_free(value); }
    Batch() = default;
    Batch(const Batch &) = delete;
    Batch & operator=(const Batch &) = delete;
    void token(int index, llama_token id, int position, bool logits) {
        value.token[index] = id;
        value.pos[index] = position;
        value.n_seq_id[index] = 1;
        value.seq_id[index][0] = 0;
        value.logits[index] = logits ? 1 : 0;
    }
};

int decode(Owner & value, llama_batch batch) {
    if (const int status = value.stopped()) return status;
    int result;
#ifdef TP1_LLAMA_TEST_HOOKS
    if (value.fail_decode) {
        value.fail_decode = false;
        result = -2;
    } else
#endif
    { result = llama_decode(value.context.get(), batch); }
    if (result != 0) {
        if (const int status = value.stopped()) return status;
        // A warning such as no KV slot must not lead to sampling stale logits either.
        throw std::runtime_error("llama_decode failed or aborted without a requested cancellation");
    }
    return value.stopped();
}

jlong create(JNIEnv * env, jobject) {
    return boundary(env, [] { return static_cast<jlong>(reinterpret_cast<intptr_t>(new Owner())); });
}
void destroy(JNIEnv * env, jobject, jlong handle) {
    boundary(env, [&] { delete &owner(handle); });
}

jboolean load(JNIEnv * env, jobject, jlong handle, jbyteArray path, jint threads) {
    return boundary(env, [&]() -> jboolean {
        auto & value = owner(handle);
        if (value.model || value.context) throw std::runtime_error("Model already loaded");
        if (threads < 1 || threads > 4) throw std::invalid_argument("Invalid inference thread count");
        const auto name = bytes(env, path, 4096);
        value.phase.store(1);
        value.expires = Clock::time_point::max();
        PhaseReset phase{value};
        if (value.stop.load()) return JNI_FALSE;
        static std::once_flag initialized;
        std::call_once(initialized, llama_backend_init);
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        params.progress_callback = load_progress;
        params.progress_callback_user_data = &value;
        Model model(llama_model_load_from_file(name.c_str(), params), llama_model_free);
        if (value.stop.load()) return JNI_FALSE;
        if (!model) throw std::runtime_error("GGUF model load failed");
        auto ctx_params = llama_context_default_params();
        ctx_params.n_ctx = context_limit;
        ctx_params.n_batch = ctx_params.n_ubatch = batch_limit;
        ctx_params.n_seq_max = 1;
        ctx_params.n_threads = ctx_params.n_threads_batch = threads;
        ctx_params.offload_kqv = false;
        ctx_params.op_offload = false;
        ctx_params.abort_callback = abort_decode;
        ctx_params.abort_callback_data = &value;
        Context context(llama_init_from_model(model.get(), ctx_params), llama_free);
        if (value.stop.load()) return JNI_FALSE;
        if (!context) throw std::runtime_error("Model context initialization failed");
        value.model = std::move(model);
        value.context = std::move(context);
        value.threads = threads;
        return JNI_TRUE;
    });
}

void unload(JNIEnv * env, jobject, jlong handle) { boundary(env, [&] { owner(handle).unload(); }); }
void reset(JNIEnv * env, jobject, jlong handle) { boundary(env, [&] { owner(handle).reset(); }); }
void cancel(JNIEnv * env, jobject, jlong handle) { boundary(env, [&] { owner(handle).stop.store(true); }); }
void clear_cancel(JNIEnv * env, jobject, jlong handle) { boundary(env, [&] { owner(handle).stop.store(false); }); }
jboolean is_cancelled(JNIEnv * env, jobject, jlong handle) {
    return boundary(env, [&]() -> jboolean { return owner(handle).stop.load() ? JNI_TRUE : JNI_FALSE; });
}
jint phase(JNIEnv * env, jobject, jlong handle) { return boundary(env, [&]() -> jint { return owner(handle).phase.load(); }); }
jint generated(JNIEnv * env, jobject, jlong handle) { return boundary(env, [&]() -> jint { return owner(handle).generated; }); }
void test_control(JNIEnv * env, jobject, jlong handle, jint pause, jboolean force, jboolean fail) {
    boundary(env, [&] {
#ifdef TP1_LLAMA_TEST_HOOKS
        if (pause < 0 || pause > 3) throw std::invalid_argument("Invalid test phase");
        auto & value = owner(handle);
        value.pause_phase.store(pause);
        value.paused.store(false);
        value.force_length = force;
        value.fail_decode = fail;
#else
        (void) handle; (void) pause; (void) force; (void) fail;
        throw std::runtime_error("Native test controls are disabled in ordinary release");
#endif
    });
}
jboolean test_paused(JNIEnv * env, jobject, jlong handle) {
    return boundary(env, [&]() -> jboolean {
#ifdef TP1_LLAMA_TEST_HOOKS
        return owner(handle).paused.load() ? JNI_TRUE : JNI_FALSE;
#else
        (void) handle;
        return JNI_FALSE;
#endif
    });
}

jbyteArray format_prompt(JNIEnv * env, jobject, jlong handle, jbyteArray system, jbyteArray user) {
    return boundary(env, [&] {
        const auto prompt = format(owner(handle), bytes(env, system, text_limit), bytes(env, user, text_limit));
        return byte_array(env, prompt.data(), prompt.size());
    });
}

jint token_count(JNIEnv * env, jobject, jlong handle, jbyteArray system, jbyteArray user) {
    return boundary(env, [&]() -> jint {
        auto & value = owner(handle);
        const auto prompt = format(value, bytes(env, system, text_limit), bytes(env, user, text_limit));
        return static_cast<jint>(tokenize(llama_model_get_vocab(value.model.get()), prompt).size());
    });
}

jintArray begin(JNIEnv * env, jobject, jlong handle, jbyteArray system, jbyteArray user,
                jint maximum, jint seed, jlong milliseconds) {
    return boundary(env, [&] {
        auto & value = owner(handle);
        if (!value.context) throw std::runtime_error("No model context loaded");
        if (maximum < 1 || maximum > 512 || milliseconds < 1 || milliseconds > 120000) {
            throw std::invalid_argument("Invalid generation limit or deadline");
        }
        value.reset();
        value.expires = Clock::now() + std::chrono::milliseconds(milliseconds);
        if (const int status = value.stopped()) return int_array(env, {status});
        const auto prompt = format(value, bytes(env, system, text_limit), bytes(env, user, text_limit));
        const auto * vocab = llama_model_get_vocab(value.model.get());
        auto tokens = tokenize(vocab, prompt);
        if (tokens.size() > input_limit) throw std::invalid_argument("Formatted input exceeds 3584 tokens");
        const int bos_count = static_cast<int>(std::count(tokens.begin(), tokens.end(), llama_vocab_bos(vocab)));
        if (bos_count != 1 || tokens.front() != llama_vocab_bos(vocab)) {
            throw std::runtime_error("Expected exactly one leading BOS for the pinned model");
        }
        const auto capacity = llama_n_ctx_seq(value.context.get());
        if (tokens.size() + static_cast<size_t>(maximum) > capacity) {
            throw std::invalid_argument("Input and requested output exceed the actual context");
        }
        value.sampler.reset(llama_sampler_chain_init(llama_sampler_chain_default_params()));
        if (!value.sampler) throw std::bad_alloc();
#ifdef TP1_LLAMA_TEST_HOOKS
        // Exercise exact caps with real inference. Suppress EOG only in this explicit test mode.
        if (value.force_length) {
            std::vector<llama_logit_bias> biases;
            for (llama_token token = 0; token < llama_vocab_n_tokens(vocab); ++token) {
                if (llama_vocab_is_eog(vocab, token)) biases.push_back({token, -std::numeric_limits<float>::infinity()});
            }
            llama_sampler_chain_add(value.sampler.get(), llama_sampler_init_logit_bias(
                llama_vocab_n_tokens(vocab), static_cast<int32_t>(biases.size()), biases.data()));
        }
#endif
        llama_sampler_chain_add(value.sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0.0f, 0.0f));
        llama_sampler_chain_add(value.sampler.get(), llama_sampler_init_top_k(50));
        llama_sampler_chain_add(value.sampler.get(), llama_sampler_init_temp(0.1f));
        llama_sampler_chain_add(value.sampler.get(), llama_sampler_init_dist(static_cast<uint32_t>(seed)));
        for (const auto token : tokens) llama_sampler_accept(value.sampler.get(), token);
        value.maximum = maximum;
        value.phase.store(2);
        Batch batch;
        const auto chunk = std::min<uint32_t>(batch_limit, llama_n_batch(value.context.get()));
        if (chunk == 0) throw std::runtime_error("Invalid native batch capacity");
        for (size_t start = 0; start < tokens.size(); start += chunk) {
            const auto count = std::min<size_t>(chunk, tokens.size() - start);
            batch.value.n_tokens = static_cast<int32_t>(count);
            for (size_t index = 0; index < count; ++index) {
                batch.token(static_cast<int>(index), tokens[start + index], static_cast<int>(start + index),
                            start + index + 1 == tokens.size());
            }
            if (const int status = decode(value, batch.value)) return int_array(env, {status});
        }
        value.position = static_cast<int>(tokens.size());
        value.phase.store(3);
        return int_array(env, {0, static_cast<jint>(tokens.size()), bos_count, static_cast<jint>(capacity),
            static_cast<jint>(llama_n_batch(value.context.get())), static_cast<jint>(llama_n_ubatch(value.context.get())), value.threads});
    });
}

jbyteArray step(JNIEnv * env, jobject, jlong handle) {
    return boundary(env, [&] {
        auto & value = owner(handle);
        if (!value.sampler || !value.context || value.maximum == 0) throw std::runtime_error("No active generation");
        const auto terminal = [&](int status) {
            const char code = static_cast<char>(status);
            return byte_array(env, &code, 1);
        };
        if (const int status = value.stopped()) return terminal(status);
        if (value.generated == value.maximum) return terminal(length);
        const auto * vocab = llama_model_get_vocab(value.model.get());
        const auto token = llama_sampler_sample(value.sampler.get(), value.context.get(), -1);
        // sample() already accepts this token. Never accept it a second time.
        if (llama_vocab_is_eog(vocab, token)) return terminal(eog);
        std::vector<char> piece(256);
        int count = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (count == std::numeric_limits<int32_t>::min()) throw std::runtime_error("Token piece overflow");
        if (count < 0) {
            if (static_cast<size_t>(-count) > output_limit - value.output_bytes) throw std::runtime_error("Output exceeds 1 MiB");
            piece.resize(static_cast<size_t>(-count));
            count = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        }
        if (count < 0 || count > static_cast<int>(piece.size())) throw std::runtime_error("Token piece retry failed");
        if (static_cast<size_t>(count) > output_limit - value.output_bytes) throw std::runtime_error("Output exceeds 1 MiB");
        ++value.generated;
        value.output_bytes += static_cast<size_t>(count);
        if (value.generated < value.maximum) {
            Batch batch;
            batch.value.n_tokens = 1;
            batch.token(0, token, value.position++, true);
            if (const int status = decode(value, batch.value)) return terminal(status);
        }
        std::string packet(1, '\0');
        packet.append(piece.data(), static_cast<size_t>(count));
        return byte_array(env, packet.data(), packet.size());
    });
}

// Exact private instance descriptors. R8 preserves this class and its native members only.
#define METHOD(name, signature, function) {const_cast<char *>(name), const_cast<char *>(signature), reinterpret_cast<void *>(function)}
JNINativeMethod methods[] = {
    METHOD("createNative", "()J", create),
    METHOD("destroyNative", "(J)V", destroy),
    METHOD("loadNative", "(J[BI)Z", load),
    METHOD("unloadNative", "(J)V", unload),
    METHOD("formatNative", "(J[B[B)[B", format_prompt),
    METHOD("tokenCountNative", "(J[B[B)I", token_count),
    METHOD("beginNative", "(J[B[BIIJ)[I", begin),
    METHOD("stepNative", "(J)[B", step),
    METHOD("resetNative", "(J)V", reset),
    METHOD("cancelNative", "(J)V", cancel),
    METHOD("clearCancelNative", "(J)V", clear_cancel),
    METHOD("cancelledNative", "(J)Z", is_cancelled),
    METHOD("phaseNative", "(J)I", phase),
    METHOD("generatedNative", "(J)I", generated),
    METHOD("testControlNative", "(JIZZ)V", test_control),
    METHOD("testPausedNative", "(J)Z", test_paused),
};
#undef METHOD
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    auto cls = env->FindClass("com/example/tp1/inference/LlamaEngine");
    if (cls == nullptr) return JNI_ERR;
    const auto result = env->RegisterNatives(cls, methods, static_cast<jint>(sizeof(methods) / sizeof(methods[0])));
    env->DeleteLocalRef(cls);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
