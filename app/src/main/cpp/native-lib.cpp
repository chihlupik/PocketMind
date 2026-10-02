#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.cpp/include/llama.h"

#define LOG_TAG "LlamaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidaimanager_MainActivity_loadModelNative(
        JNIEnv* env,
        jobject /* this */,
        jstring model_path) {

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model from: %s", path);

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    g_model = llama_model_load_from_file(path, model_params);

    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load model from file!");
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = 4;

    g_ctx = llama_init_from_model(g_model, ctx_params);

    if (!g_ctx) {
        LOGE("Failed to create context!");
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully into memory!");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_androidaimanager_MainActivity_generateNative(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt) {

    if (!g_ctx || !g_model) {
        LOGE("Error: Model or Context is null");
        return env->NewStringUTF("Error: Model not loaded.");
    }

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_text(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    LOGI("Received prompt: %s", prompt_text.c_str());

    // ИЗМЕНЕНИЕ: Получение n_vocab через актуальный API llama_vocab_n_tokens
    const auto* vocab = llama_model_get_vocab(g_model);
    int n_vocab = llama_vocab_n_tokens(vocab);

    std::vector<llama_token> tokens(prompt_text.size() + 32);
    int n_tokens = llama_tokenize(vocab, prompt_text.c_str(), prompt_text.size(), tokens.data(), tokens.size(), true, true);

    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt_text.c_str(), prompt_text.size(), tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(n_tokens);

    LOGI("Tokenized into %d tokens", n_tokens);

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Decode failed on initial prompt!");
        return env->NewStringUTF("Error decoding prompt.");
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);

    // ИЗМЕНЕНИЕ: Вызов llama_sampler_init_penalties с 5 аргументами (n_vocab первым параметром)
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
        n_vocab,
        64,      // penalty_last_n
        1.18f,   // penalty_repeat
        0.0f,    // penalty_freq
        0.0f     // penalty_present
    ));

    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result_text = "";
    int max_tokens = 256;

    for (int i = 0; i < max_tokens; ++i) {
        llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, new_token_id);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            LOGI("End of generation reached");
            break;
        }

        char buf[128];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result_text.append(buf, n);
        }

        batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Decode failed during generation!");
            break;
        }
    }

    llama_sampler_free(smpl);

    LOGI("Generated response: %s", result_text.c_str());
    return env->NewStringUTF(result_text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidaimanager_MainActivity_unloadModelNative(
        JNIEnv* env,
        jobject /* this */) {

    LOGI("Unloading model...");
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
}
