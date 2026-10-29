package com.example.androidaimanager

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

enum class ChatRole { USER, BOT, SYSTEM }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String
)

class AiViewModel(application: Application) : AndroidViewModel(application) {

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages

    private val _logList = MutableStateFlow<List<String>>(emptyList())
    val logList: StateFlow<List<String>> = _logList

    private val _statusText = MutableStateFlow("Initializing model...")
    val statusText: StateFlow<String> = _statusText

    private val _isAiReady = MutableStateFlow(false)
    val isAiReady: StateFlow<Boolean> = _isAiReady

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val userSkillsManager = UserSkillsManager(application)

    private var currentDir: String = "/storage/emulated/0"
    private val stopRequested = AtomicBoolean(false)
    private var generationJob: Job? = null
    private val termuxPython = "/data/data/com.termux/files/usr/bin/python"

    // Shizuku state
    private var shizukuReady = false

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001) {
            shizukuReady = grantResult == PackageManager.PERMISSION_GRANTED
            addLog("[Shizuku] Permission result: $shizukuReady")
        }
    }

    init {
        try {
            System.loadLibrary("native-lib")
            if (!File(currentDir).canExecute()) {
                currentDir = getApplication<Application>().filesDir.absolutePath
            }
            initAiModel()
            initShizuku()
        } catch (e: Throwable) {
            _statusText.value = "Native library load failed"
            addLog("[App] FATAL: ${e.localizedMessage}")
        }
    }

    private fun initShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                addLog("[Shizuku] Binder is alive")
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    shizukuReady = true
                    addLog("[Shizuku] Permission already granted. Shell commands will use ADB privileges.")
                } else {
                    addLog("[Shizuku] Permission not granted. Requesting...")
                    Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
                    Shizuku.requestPermission(1001)
                }
            } else {
                addLog("[Shizuku] Binder not running. Start Shizuku app first.")
            }
        } catch (e: Throwable) {
            addLog("[Shizuku] Init failed: ${e.localizedMessage}")
        }
    }

    private fun initAiModel() {
        viewModelScope.launch(Dispatchers.IO) {
            val modelFile = File(getApplication<Application>().filesDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf")

            // Если модели нет в filesDir, копируем её из assets
            if (!modelFile.exists()) {
                addLog("[App] Model not found in filesDir. Copying from assets...")
                try {
                    val inputStream: java.io.InputStream = getApplication<Application>().assets.open("qwen2.5-0.5b-instruct-q4_k_m.gguf")
                    val outputStream = modelFile.outputStream()
                    inputStream.copyTo(outputStream)
                    inputStream.close()
                    outputStream.close()
                    addLog("[App] Model copied successfully.")
                } catch (e: Exception) {
                    addLog("[App] Failed to copy model from assets: ${e.localizedMessage}")
                    _isAiReady.value = false
                    _statusText.value = "Model copy failed"
                    return@launch
                }
            }

            val success = try { initModelNative(modelFile.absolutePath) } catch (e: Throwable) {
                addLog("[App] initModelNative threw: ${e.localizedMessage}")
                false
            }
            _isAiReady.value = success
            _statusText.value = if (success) "Model Ready" else "Model Init Failed"
            addLog(if (success) "[App] Model initialized successfully" else "[App] Failed to initialize model")
        }
    }

    fun processCommand(input: String) {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty()) return

        appendMessage(ChatRole.USER, trimmedInput)

        val directCommands = listOf("dumpsys", "logcat", "pm ", "am ", "settings ", "getprop", "cat ", "ls ", "find ", "grep ", "wc ", "head ", "tail ", "df ", "du ", "top ", "ps ", "netstat ", "ip ", "ping ", "exec ", "sh ")
        val isDirectCommand = directCommands.any { trimmedInput.startsWith(it, ignoreCase = true) } || trimmedInput.startsWith("`") || trimmedInput.contains(" | ")

        when {
            isDirectCommand -> {
                val cleanCmd = trimmedInput.removePrefix("exec ").removePrefix("sh ").replace("`", "")
                handleShell(cleanCmd, "[Direct Exec]")
            }
            trimmedInput.startsWith("cd ", ignoreCase = true) -> handleCd(trimmedInput.substring(3).trim())
            trimmedInput.equals("pwd", ignoreCase = true) -> appendMessage(ChatRole.BOT, currentDir)
            trimmedInput.equals("ls", ignoreCase = true) || trimmedInput.equals("dir", ignoreCase = true) -> handleShell("ls -la", "[Shell] ls")
            trimmedInput.startsWith("mkdir ", ignoreCase = true) -> handleShell("mkdir -p '${trimmedInput.substring(6).trim()}'", "[Shell] mkdir")
            trimmedInput.startsWith("rm ", ignoreCase = true) -> handleShell("rm -rf '${trimmedInput.substring(3).trim()}'", "[Shell] rm")
            trimmedInput.startsWith("find ", ignoreCase = true) -> handleShell("find . -name '*${trimmedInput.substring(5).trim()}*'", "[Shell] find")
            trimmedInput.equals("list skills", ignoreCase = true) || trimmedInput.equals("skills", ignoreCase = true) -> handleListSkills()
            trimmedInput.equals("clear skills", ignoreCase = true) -> handleClearSkills()
            trimmedInput.startsWith("run ", ignoreCase = true) -> handleRunScript(trimmedInput.substring(4).trim())
            trimmedInput.startsWith("cat ", ignoreCase = true) -> handleShell("cat '${trimmedInput.substring(4).trim()}'", "[File Reader]")
            trimmedInput.startsWith("learn skill ", ignoreCase = true) -> handleLearnSkill(trimmedInput.substring(12).trim())
            trimmedInput.startsWith("use skill ", ignoreCase = true) -> handleUseSkill(trimmedInput.substring(10).trim())
            trimmedInput.equals("help", ignoreCase = true) -> handleHelp()
            else -> sendPromptToLlama(trimmedInput)
        }
    }

    private fun sendPromptToLlama(userInput: String) {
        _isGenerating.value = true
        stopRequested.set(false)
        val placeholderId = appendMessage(ChatRole.BOT, "...")

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val history = _chatMessages.value
                    .filter { it.id != placeholderId && it.text != "..." }
                    .takeLast(6)

                val promptBuilder = StringBuilder()
                promptBuilder.append("<|im_start|>system\n")
                promptBuilder.append(
                    "You are PocketMind, an expert Android shell automation assistant. " +
                            "CRITICAL: You are running on ANDROID. " +
                            "NEVER use 'pm' for anything except packages (e.g., 'pm list packages'). " +
                            "For system logs, use 'logcat' (e.g., 'logcat -d -t 50'). " +
                            "For network devices/hotspot clients, use 'ip neigh' or 'dumpsys connectivity'. " +
                            "For battery, use 'cat /sys/class/power_supply/battery/capacity'. " +
                            "For general device info, use 'getprop' or 'dumpsys'. " +
                            "If the user asks for system info, respond ONLY with: EXEC: <command>. " +
                            "If a command fails or is blocked, you will receive a SYSTEM message. " +
                            "Read the SYSTEM message carefully and suggest a DIFFERENT command. Do not repeat the failed command. " +
                            "For normal conversation, reply normally.\n"
                )
                promptBuilder.append("<|im_end|>\n")

                for (msg in history) {
                    val role = when (msg.role) {
                        ChatRole.USER -> "user"
                        ChatRole.BOT -> "assistant"
                        ChatRole.SYSTEM -> "system"
                    }
                    val content = if (msg.text.length > 200) msg.text.take(200) + "..." else msg.text
                    promptBuilder.append("<|im_start|>$role\n$content\n<|im_end|>\n")
                }
                promptBuilder.append("<|im_start|>user\n$userInput\n<|im_end|>\n")
                promptBuilder.append("<|im_start|>assistant\n")

                val formattedPrompt = promptBuilder.toString()

                coroutineContext.ensureActive()
                val result = generateNative(formattedPrompt) { logMessage ->
                    addLog(logMessage.toString())
                }

                coroutineContext.ensureActive()
                var trimmed = result.trim()

                if (trimmed.startsWith("```")) {
                    val firstLineEnd = trimmed.indexOf("\n")
                    val lastTicks = trimmed.lastIndexOf("```")
                    if (firstLineEnd != -1 && lastTicks > firstLineEnd) {
                        trimmed = trimmed.substring(firstLineEnd, lastTicks).trim()
                    }
                }

                val execRegex = Regex("(?i)(?:EXEC:|выполни:|команда:)\\s*([^\\n`]+)")
                val match = execRegex.find(trimmed)

                if (match != null) {
                    val cmd = match.groupValues[1].trim()
                    executeCommandWithLoopProtection(cmd)
                } else if (trimmed.isNotEmpty() && (trimmed.startsWith("pm ") || trimmed.startsWith("dumpsys") || trimmed.startsWith("getprop") || trimmed.startsWith("ls ") || trimmed.startsWith("logcat") || trimmed.startsWith("cat "))) {
                    executeCommandWithLoopProtection(trimmed)
                } else {
                    updateLastBotMessage(trimmed.ifBlank { "(empty response)" })
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateLastBotMessage("[Cancelled]")
            } catch (e: Exception) {
                updateLastBotMessage("Generation error: ${e.localizedMessage}")
            } finally {
                _isGenerating.value = false
                generationJob = null
            }
        }
    }

    private fun executeCommandWithLoopProtection(cmd: String) {
        // 1. Жесткая блокировка pm для всего, кроме пакетов
        if (cmd.contains("pm ") && !cmd.contains("pm list packages") && !cmd.contains("pm path")) {
            addLog("[AI Engine] Blocked invalid 'pm' command: $cmd")

            // Принудительно подсказываем модели правильный путь
            val hint = if (cmd.contains("battery", ignoreCase = true)) {
                "For battery level, use: cat /sys/class/power_supply/battery/capacity. For temperature: cat /sys/class/power_supply/battery/temp."
            } else {
                "Do not use 'pm' for this. Use 'cat /proc/...' or 'dumpsys'."
            }

            appendMessage(ChatRole.SYSTEM, "Ошибка: команда '$cmd' неверна. $hint")
            updateLastBotMessage("ИИ ошиблась. Отправляю подсказку...")
            sendPromptToLlama("The previous command was invalid. $hint")
            return
        }

        // 2. Защита от зацикливания
        val lastBotMessages = _chatMessages.value.filter { it.role == ChatRole.BOT }.takeLast(2)
        val isLoop = lastBotMessages.any { it.text.contains(cmd, ignoreCase = true) }

        if (isLoop) {
            addLog("[AI Engine] LOOP DETECTED: $cmd")
            updateLastBotMessage("Похоже, эта команда не работает. Давай попробуем другой подход?")
            appendMessage(ChatRole.SYSTEM, "Ошибка: команда '$cmd' уже выполнялась и не сработала. Предложи другую.")
            return
        }

        updateLastBotMessage("Executing: $cmd")
        addLog("[AI Engine] Model requested EXEC: $cmd")

        val shellResult = executeShellCommandInternal(cmd)
        appendMessage(ChatRole.BOT, shellResult)

        // 3. Проверка на ошибки
        val isError = shellResult.contains("[Err]") ||
                shellResult.contains("Permission denied", ignoreCase = true) ||
                shellResult.contains("not found", ignoreCase = true) ||
                shellResult.contains("timed out", ignoreCase = true) ||
                shellResult.contains("Unknown command", ignoreCase = true) ||
                shellResult.contains("Error:", ignoreCase = true) ||
                shellResult.contains("Exception", ignoreCase = true) ||
                shellResult.contains("failed", ignoreCase = true)

        if (isError) {
            addLog("[AI Engine] Command failed. Adding system feedback.")
            appendMessage(ChatRole.SYSTEM, "Команда '$cmd' вернула ошибку: $shellResult. Пожалуйста, предложи альтернативную команду (например, из /sys/ или /proc/).")
        }
    }

    private fun handleCd(target: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val candidate = File(currentDir, target).canonicalFile
            if (!candidate.isDirectory || !candidate.canExecute()) {
                appendMessage(ChatRole.BOT, "Directory not found or access denied: $target")
                return@launch
            }
            currentDir = candidate.absolutePath
            appendMessage(ChatRole.BOT, "Directory changed to: $currentDir")
        }
    }

    private fun handleShell(command: String, logPrefix: String) {
        addLog("$logPrefix Command: $command")
        viewModelScope.launch(Dispatchers.IO) {
            val result = executeShellCommandInternal(command)
            appendMessage(ChatRole.BOT, result)
        }
    }

    private fun handleRunScript(scriptPath: String) {
        val command = when {
            scriptPath.endsWith(".py", ignoreCase = true) -> {
                if (!File(termuxPython).exists()) { appendMessage(ChatRole.BOT, "Python not found."); return }
                "$termuxPython '$scriptPath'"
            }
            else -> "sh '$scriptPath'"
        }
        handleShell(command, "[Script Runner]")
    }

    private fun handleListSkills() {
        viewModelScope.launch {
            val skills = userSkillsManager.getAllSkills()
            val response = if (skills.isEmpty()) "No skills learned yet." else "Saved skills:\n" + skills.joinToString("\n") { "• ${it.name}: ${it.description}" }
            appendMessage(ChatRole.BOT, response)
        }
    }

    private fun handleClearSkills() {
        viewModelScope.launch {
            userSkillsManager.clearAllSkills()
            appendMessage(ChatRole.BOT, "All learned skills have been deleted.")
        }
    }

    private fun handleLearnSkill(content: String) {
        if (!content.contains("=")) { appendMessage(ChatRole.BOT, "Format: learn skill <name> = <command>"); return }
        val parts = content.split("=", limit = 2)
        val name = parts[0].trim(); val command = parts[1].trim()
        val newSkill = UserSkill(name, "Shell command skill", "shell", JSONObject().put("command", command))
        viewModelScope.launch {
            userSkillsManager.addSkill(newSkill)
            appendMessage(ChatRole.BOT, "Learned skill '$name' -> '$command'")
        }
    }

    private fun handleUseSkill(fullSkillInput: String) {
        val parts = fullSkillInput.split(" ", limit = 2)
        val skillName = parts[0]
        val param = if (parts.size > 1) parts[1] else ""
        viewModelScope.launch {
            val skills = userSkillsManager.getAllSkills()
            val foundSkill = skills.find { it.name.equals(skillName, ignoreCase = true) }
            if (foundSkill == null) { appendMessage(ChatRole.BOT, "Skill '$skillName' not found."); return@launch }
            val rawCommand = foundSkill.config.optString("command", "")
            val finalCommand = if (rawCommand.contains("{text}")) rawCommand.replace("{text}", param) else if (param.isNotEmpty()) "$rawCommand $param" else rawCommand
            val result = withContext(Dispatchers.IO) { executeShellCommandInternal(finalCommand) }
            appendMessage(ChatRole.BOT, result)
        }
    }

    private fun handleHelp() {
        val helpText = """
            • cd <folder>, pwd, ls, mkdir, rm, find, cat
            • exec <cmd> — Run shell command
            • run <file> — Run .py or .sh
            • list/clear skills
            • learn skill <name> = <cmd>
            • use skill <name> [arg]
        """.trimIndent()
        appendMessage(ChatRole.BOT, helpText)
    }

    private fun executeShellCommandInternal(cmd: String): String {
        return try {
            var formattedCmd = cmd
            if (formattedCmd.startsWith("python ", true) && File(termuxPython).exists()) {
                formattedCmd = formattedCmd.replaceFirst("python ", "$termuxPython ")
            }
            val finalCmd = "cd '$currentDir' && $formattedCmd"

            val process: Process = if (shizukuReady && Shizuku.pingBinder()) {
                addLog("[Shell] Executing via Shizuku (ADB) via Reflection")
                try {
                    val method = Shizuku::class.java.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java
                    )
                    method.isAccessible = true
                    val remoteProcess = method.invoke(
                        null,
                        arrayOf("sh", "-c", finalCmd),
                        null,
                        null
                    ) as ShizukuRemoteProcess
                    remoteProcess
                } catch (e: Exception) {
                    addLog("[Shizuku] Reflection failed: ${e.message}. Falling back to normal shell.")
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", finalCmd))
                }
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", finalCmd))
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = StringBuilder()
            var line: String?
            var errCount = 0

            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "Command timed out after 5 seconds."
            }

            while (reader.readLine().also { line = it } != null) output.append(line).append("\n")
            while (errorReader.readLine().also { line = it } != null) {
                if (errCount < 10) { output.append("[Err] ").append(line).append("\n"); errCount++ }
            }

            val exitCode = process.exitValue()
            if (exitCode != 0 && output.isBlank()) "Command failed (exit $exitCode)"
            else if (output.isBlank()) "Executed successfully with empty output."
            else output.toString().trim().take(3000)
        } catch (e: Exception) {
            "Execution error: ${e.localizedMessage}"
        }
    }

    fun stopGeneration() {
        stopRequested.set(true)
        try { requestStopNative() } catch (_: Throwable) {}
        generationJob?.cancel()
        _isGenerating.value = false
        updateLastBotMessage("[Stopped]")
    }

    private fun appendMessage(role: ChatRole, text: String): String {
        val msg = ChatMessage(role = role, text = text)
        _chatMessages.value = _chatMessages.value + msg
        return msg.id
    }

    private fun updateLastBotMessage(text: String) {
        val list = _chatMessages.value.toMutableList()
        val idx = list.indexOfLast { it.role == ChatRole.BOT }
        if (idx >= 0) {
            list[idx] = list[idx].copy(text = text)
            _chatMessages.value = list
        } else {
            appendMessage(ChatRole.BOT, text)
        }
    }

    @Synchronized
    private fun addLog(logText: String) {
        val current = _logList.value
        val updated = if (current.size > 500) current.takeLast(400) + logText else current + logText
        _logList.value = updated
    }

    external fun initModelNative(modelPath: String): Boolean
    external fun generateNative(prompt: String, logListener: (Any) -> Unit): String
    external fun requestStopNative()
    external fun freeModelNative()

    override fun onCleared() {
        super.onCleared()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            requestStopNative()
            freeModelNative()
        } catch (_: Throwable) {}
    }
}