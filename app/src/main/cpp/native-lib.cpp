#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <android/log.h>
#include "llama.cpp/include/llama.h"

#define LOG_TAG "LlamaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;

void sendLogToKotlin(JNIEnv* env, jobject listener, const char* message) {
    if (!listener) return;
    jclass clazz = env->GetObjectClass(listener);
    jmethodID method = env->GetMethodID(clazz, "onLog", "(Ljava/lang/String;)V");
    if (method) {
        jstring jmsg = env->NewStringUTF(message);
        env->CallVoidMethod(listener, method, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidaimanager_MainActivity_loadModelNative(
        JNIEnv* env,
        jobject /* this */,
        jstring model_path) {

    const char* path = env->GetStringUTFChars(model_path, nullptr);

    llama_backend_init();

    // В новых версиях llama.cpp use_mmap уже включен по умолчанию в default_params
    llama_model_params model_params = llama_model_default_params();

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    return g_model != nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_androidaimanager_MainActivity_generateNative(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt,
        jobject log_listener) {

    if (!g_model) return env->NewStringUTF("Error: Model not loaded.");

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 1024; // Короткий контекст для скорости

    int available_threads = 4;
    ctx_params.n_threads = available_threads;
    ctx_params.n_threads_batch = available_threads;

    llama_context* ctx = llama_init_from_model(g_model, ctx_params);
    if (!ctx) return env->NewStringUTF("Error creating context.");

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_text(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    const auto* vocab = llama_model_get_vocab(g_model);
    int n_vocab = llama_vocab_n_tokens(vocab);

    std::vector<llama_token> tokens(prompt_text.size() + 32);
    int n_tokens = llama_tokenize(vocab, prompt_text.c_str(), prompt_text.size(), tokens.data(), tokens.size(), true, true);
    tokens.resize(n_tokens);

    auto start_time = std::chrono::high_resolution_clock::now();

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(ctx, batch) != 0) {
        llama_free(ctx);
        return env->NewStringUTF("Error decoding prompt.");
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f)); // Низкая температура для четкого JSON
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result_text = "";
    int max_tokens = 64; // Ограничение для мгновенной генерации
    int generated_count = 0;

    for (int i = 0; i < max_tokens; ++i) {
        llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
        llama_sampler_accept(smpl, new_token_id);

        if (llama_vocab_is_eog(vocab, new_token_id)) break;

        char buf[64];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result_text.append(buf, n);
            generated_count++;

            // Если сформировалась закрывающая скобка JSON — останавливаемся сразу
            if (result_text.find("}") != std::string::npos ||
                result_text.find("<|im_end|>") != std::string::npos) {
                break;
            }
        }

        batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(ctx, batch) != 0) break;
    }

    auto end_time = std::chrono::high_resolution_clock::now();
    double duration = std::chrono::duration<double>(end_time - start_time).count();
    double speed = (duration > 0) ? (generated_count / duration) : 0;

    std::string perf_msg = "[Perf] Tokens: " + std::to_string(generated_count) +
                           " | Time: " + std::to_string(duration).substr(0, 4) + "s" +
                           " (" + std::to_string(speed).substr(0, 4) + " tok/s)";
    sendLogToKotlin(env, log_listener, perf_msg.c_str());

    llama_sampler_free(smpl);
    llama_free(ctx);

    return env->NewStringUTF(result_text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidaimanager_MainActivity_unloadModelNative(JNIEnv* env, jobject) {
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
}