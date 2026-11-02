PocketMind: Local Offline AI Agent for Android

DESCRIPTION
PocketMind is a local AI agent that works completely offline based on llama.cpp. It is capable of holding a conversation, as well as executing shell commands on the device through natural language, utilizing user confirmation for actions.

TECH STACK
Languages: Kotlin, C++ (JNI)
UI: Jetpack Compose (Material 3)
Architecture: MVVM, StateFlow, Coroutines
AI Core: llama.cpp (built via CMake)
Model: Qwen2.5-0.5B-Instruct (GGUF format, Q4_K_M quantization)
Build System: Gradle (Kotlin DSL), CMake 3.22.1, NDK r27

MINIMUM REQUIREMENTS
Android: 8.0 (API 26) and above. Optimal: Android 10+ (where curl utility is built-in).
RAM: 3 GB or more (the model requires about 1.5 GB of free memory).
Architecture: arm64-v8a (most devices) and x86_64 (emulators).
Where it won't work: On 32-bit architecture devices (ARMv7) or older Android versions without file system access (at least to the /sdcard/ folder).

BUILD AND RUN
Building requires installed JDK 17, Android SDK (API 35), and Android NDK. CMake and Ninja are typically installed via SDK Manager in Android Studio. The model (about 500 MB) is downloaded automatically on the first build (downloadModel task in build.gradle.kts) and placed in the assets folder.

Windows / macOS / Linux (via terminal):

Clone the repository:
git clone https://github.com/chihlupik/PocketMind.git
cd PocketMind

Build the project (for Windows use gradlew.bat instead of ./gradlew):

./gradlew assembleDebug

The ready APK will be located at app/build/outputs/apk/debug/app-debug.apk.
If cmake or ndk are not found, check the ANDROID_HOME and ANDROID_NDK_HOME environment variables.

FEATURES AND USAGE
The agent operates in two modes:

Conversation - regular chat without executing commands.

Execution - if you ask it to do something, the AI will generate a command with the EXEC: prefix. Every command requires confirmation in a dialog window (Security Approval) before execution.

Direct Exec
You can enter commands directly (e.g., ls /sdcard/, curl google.com, cat /proc/cpuinfo). The app will intercept them and execute them without the neural network.

Skills
You can teach the AI custom commands.

Create a skill: learn skill [name] [command]

Example: learn skill flash echo "flashlight on"

Use a skill: use skill [name] [args]

Example: use skill flash

Privileges
The AI is aware of Root and Shizuku availability. If privileges are missing, it will refuse to execute system commands (dumpsys, pm, ps) and explain the reason. With Root, the agent gains full control over the device.

KNOWN LIMITATIONS
The Qwen 0.5B model is very small and prone to hallucinations. The system prompt contains strict rules (Cheat Sheet) to guide it.
Shell command output is limited to 3000 characters to prevent OOM (Out of Memory).
No support for streaming text from the AI - the response appears in full after generation is complete.