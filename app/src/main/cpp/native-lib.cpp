#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <mutex>
#include <atomic>
#include <thread>
#include <algorithm> // Для std::min
#include <android/log.h>
#include <stdexcept>
#include "llama.cpp/include/llama.h"

#define LOG_TAG "LlamaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static std::mutex g_mutex;
static std::atomic<bool> g_stop{false};

// Безопасная отправка логов в Kotlin
void sendLogToKotlin(JNIEnv* env, jobject listener, const char* message) {
    if (!listener) return;
    jclass cls = env->GetObjectClass(listener);
    if (!cls) return;
    jmethodID invokeMethod = env->GetMethodID(cls, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (invokeMethod) {
        jstring jmsg = env->NewStringUTF(message);
        if (jmsg != nullptr) {
            env->CallObjectMethod(listener, invokeMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
    }
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

static int pickThreadCount() {
    return 2;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidaimanager_AiViewModel_initModelNative(
        JNIEnv* env, jobject, jstring model_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) return JNI_TRUE;

    try {
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
    } catch (...) {
        LOGI("Exception during model load");
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_androidaimanager_AiViewModel_generateNative(
        JNIEnv* env, jobject, jstring prompt, jobject log_listener) {

    std::lock_guard<std::mutex> lock(g_mutex);

    try {
        if (!g_model) return env->NewStringUTF("Error: Model not initialized.");

        g_stop.store(false);

        if (g_ctx) {
            llama_free(g_ctx);
            g_ctx = nullptr;
        }

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = 1024;
        ctx_params.n_batch = 1024;
        ctx_params.n_ubatch = 1024;

        ctx_params.n_threads = pickThreadCount();
        ctx_params.n_threads_batch = ctx_params.n_threads;
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

        g_ctx = llama_init_from_model(g_model, ctx_params);
        if (!g_ctx) return env->NewStringUTF("Error creating context (OOM).");

        const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
        if (prompt_str == nullptr) return env->NewStringUTF("Error: invalid prompt.");
        std::string prompt_text(prompt_str);
        env->ReleaseStringUTFChars(prompt, prompt_str);

        const auto* vocab = llama_model_get_vocab(g_model);

        std::vector<llama_token> tokens(prompt_text.size() + 64);
        int n_tokens = llama_tokenize(vocab, prompt_text.c_str(), (int)prompt_text.size(), tokens.data(), (int)tokens.size(), true, true);

        if (n_tokens < 0) {
            tokens.resize(-n_tokens + 8);
            n_tokens = llama_tokenize(vocab, prompt_text.c_str(), (int)prompt_text.size(), tokens.data(), (int)tokens.size(), true, true);
            if (n_tokens < 0) {
                llama_free(g_ctx); g_ctx = nullptr;
                return env->NewStringUTF("Error: prompt too long.");
            }
        }
        tokens.resize(n_tokens);

        if (tokens.empty() || tokens.size() > 980) {
            llama_free(g_ctx); g_ctx = nullptr;
            return env->NewStringUTF("Error: prompt too long/short.");
        }

        auto prefill_start = std::chrono::high_resolution_clock::now();

        // ФИКС: Обрабатываем префилл кусками по 128 токенов, чтобы можно было прервать
        int chunk_size = 128;
        for (int i = 0; i < (int)tokens.size(); i += chunk_size) {
            if (g_stop.load()) {
                llama_free(g_ctx); g_ctx = nullptr;
                return env->NewStringUTF("[Stopped]");
            }
            int n_eval = std::min(chunk_size, (int)tokens.size() - i);
            llama_batch batch = llama_batch_get_one(tokens.data() + i, n_eval);
            if (llama_decode(g_ctx, batch) != 0) {
                llama_free(g_ctx); g_ctx = nullptr;
                return env->NewStringUTF("Error decoding prompt.");
            }
        }

        if (g_stop.load()) {
            llama_free(g_ctx); g_ctx = nullptr;
            return env->NewStringUTF("[Stopped]");
        }

        auto prefill_end = std::chrono::high_resolution_clock::now();
        double prefill_time = std::chrono::duration<double>(prefill_end - prefill_start).count();

        auto sparams = llama_sampler_chain_default_params();
        llama_sampler* smpl = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.45f));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        std::string result_text;
        const int max_tokens = 150;
        int generated_count = 0;

        auto gen_start = std::chrono::high_resolution_clock::now();

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
            llama_batch batch = llama_batch_get_one(&new_token_id, 1);
            if (llama_decode(g_ctx, batch) != 0) break;
        }

        auto gen_end = std::chrono::high_resolution_clock::now();
        double gen_time = std::chrono::duration<double>(gen_end - gen_start).count();
        double speed = (gen_time > 0) ? (generated_count / gen_time) : 0;

        char perf_buf[256];
        snprintf(perf_buf, sizeof(perf_buf), "[Perf] prefill %d tok in %.2fs | gen %d tok in %.2fs (%.2f tok/s)",
                 (int)tokens.size(), prefill_time, generated_count, gen_time, speed);
        sendLogToKotlin(env, log_listener, perf_buf);
        llama_sampler_free(smpl);

        return env->NewStringUTF(result_text.c_str());

    } catch (const std::bad_alloc& e) {
        return env->NewStringUTF("Error: Out of memory (OOM) in native code.");
    } catch (const std::exception& e) {
        return env->NewStringUTF(e.what());
    } catch (...) {
        return env->NewStringUTF("Error: Unknown native exception.");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidaimanager_AiViewModel_requestStopNative(JNIEnv*, jobject) {
    g_stop.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidaimanager_AiViewModel_freeModelNative(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
}