plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.androidaimanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.androidaimanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Сборка только под 64-битный ARM и эмулятор x86_64
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                // Передаем CMake флаги для максимальной производительности (Release + O3)
                arguments(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_ARM_NEON=ON",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF"
                )
                cppFlags += listOf("-std=c++17", "-O3", "-funroll-loops")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Скачивание GGUF-модели через curl
tasks.register("downloadModel") {
    val modelDir = file("src/main/assets")
    val modelFile = file("src/main/assets/qwen2.5-0.5b-instruct-q4_k_m.gguf")
    val modelUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"

    outputs.file(modelFile)

    doLast {
        if (!modelFile.exists()) {
            modelDir.mkdirs()
            println("=== Downloading Qwen 2.5 0.5B GGUF Model... ===")
            exec {
                commandLine("curl", "-L", "-o", modelFile.absolutePath, modelUrl)
            }
            println("=== Model downloaded successfully! ===")
        } else {
            println("=== Model already present in assets ===")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadModel")
}