#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <mutex>
#include <atomic>
#include <android/log.h>
#include "llama.cpp/include/llama.h"

#define LOG_TAG "LlamaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static std::mutex g_mutex;
static std::atomic<bool> g_stop{false};

// Кэш JNI-идентификаторов
static jclass g_function1Class = nullptr;
static jmethodID g_invokeMethod = nullptr;

void sendLogToKotlin(JNIEnv* env, jobject listener, const char* message) {
    if (!listener) return;

    if (g_function1Class == nullptr) {
        jclass localCls = env->FindClass("kotlin/jvm/functions/Function1");
        if (localCls == nullptr) {
            env->ExceptionClear();
            localCls = env->GetObjectClass(listener);
        }
        g_function1Class = (jclass) env->NewGlobalRef(localCls);
        if (localCls) env->DeleteLocalRef(localCls);
        if (g_function1Class) {
            g_invokeMethod = env->GetMethodID(g_function1Class, "invoke",
                                              "(Ljava/lang/Object;)Ljava/lang/Object;");
        }
    }

    if (!g_function1Class || !g_invokeMethod) return;

    jstring jmsg = env->NewStringUTF(message);
    env->CallObjectMethod(listener, g_invokeMethod, jmsg);
    env->DeleteLocalRef(jmsg);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidaimanager_AiViewModel_initModelNative(
        JNIEnv* env,
        jobject /* this */,
        jstring model_path) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) return JNI_TRUE;

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return JNI_FALSE;

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (g_model) {
        LOGI("Model loaded successfully");
        return JNI_TRUE;
    }
    LOGI("Model load failed");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_androidaimanager_AiViewModel_generateNative(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt,
        jobject log_listener) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model) return env->NewStringUTF("Error: Model not initialized.");

    g_stop.store(false);

    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;
    g_ctx = llama_init_from_model(g_model, ctx_params);

    if (!g_ctx) return env->NewStringUTF("Error creating context.");

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_str == nullptr) return env->NewStringUTF("Error: invalid prompt.");
    std::string prompt_text(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    const auto* vocab = llama_model_get_vocab(g_model);

    std::vector<llama_token> tokens(prompt_text.size() + 64);
    int n_tokens = llama_tokenize(vocab, prompt_text.c_str(),
                                  (int) prompt_text.size(),
                                  tokens.data(), (int) tokens.size(),
                                  true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens + 8);
        n_tokens = llama_tokenize(vocab, prompt_text.c_str(),
                                  (int) prompt_text.size(),
                                  tokens.data(), (int) tokens.size(),
                                  true, true);
        if (n_tokens < 0) {
            return env->NewStringUTF("Error: prompt too long.");
        }
    }
    tokens.resize(n_tokens);

    if (tokens.empty()) {
        return env->NewStringUTF("Error: empty prompt.");
    }

    auto start_time = std::chrono::high_resolution_clock::now();

    llama_batch batch = llama_batch_get_one(tokens.data(), (int) tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        return env->NewStringUTF("Error decoding prompt.");
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result_text;
    int max_tokens = 512; // <--- Увеличено с 128 до 512
    int generated_count = 0;

    env->PushLocalFrame(16);
    for (int i = 0; i < max_tokens; ++i) {
        if (g_stop.load()) break;

        llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, new_token_id);

        if (llama_vocab_is_eog(vocab, new_token_id)) break;

        char buf[64];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result_text.append(buf, n);
            generated_count++;
        }

        batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
    }
    env->PopLocalFrame(nullptr);

    auto end_time = std::chrono::high_resolution_clock::now();
    double duration = std::chrono::duration<double>(end_time - start_time).count();
    double speed = (duration > 0) ? (generated_count / duration) : 0;

    char perf_buf[128];
    snprintf(perf_buf, sizeof(perf_buf),
             "[Perf] Tokens: %d | Time: %.2fs (%.2f tok/s)",
             generated_count, duration, speed);
    sendLogToKotlin(env, log_listener, perf_buf);

    llama_sampler_free(smpl);

    return env->NewStringUTF(result_text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidaimanager_AiViewModel_requestStopNative(JNIEnv*, jobject) {
    g_stop.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidaimanager_AiViewModel_freeModelNative(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();

    if (g_function1Class) {
        env->DeleteGlobalRef(g_function1Class);
        g_function1Class = nullptr;
        g_invokeMethod = nullptr;
    }
}