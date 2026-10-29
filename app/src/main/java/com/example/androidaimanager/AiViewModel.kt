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

    // Статусы для UI (StateFlow)
    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable

    private val _isShizukuReady = MutableStateFlow(false)
    val isShizukuReady: StateFlow<Boolean> = _isShizukuReady

    private val userSkillsManager = UserSkillsManager(application)

    private var currentDir: String = "/storage/emulated/0"
    private val stopRequested = AtomicBoolean(false)
    private var generationJob: Job? = null
    private val termuxPython = "/data/data/com.termux/files/usr/bin/python"

    // Локальные переменные (переименованы, чтобы не конфликтовать с StateFlow)
    private var hasRoot = false
    private var shizukuReady = false

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001) {
            shizukuReady = grantResult == PackageManager.PERMISSION_GRANTED
            _isShizukuReady.value = shizukuReady
            addLog("[Shizuku] Permission result: $shizukuReady")
        }
    }

    init {
        try {
            System.loadLibrary("native-lib")
            if (!File(currentDir).canExecute()) {
                currentDir = getApplication<Application>().filesDir.absolutePath
            }
            hasRoot = checkRoot()
            _isRootAvailable.value = hasRoot
            addLog("[App] Root available: $hasRoot")
            initAiModel()
            initShizuku()
        } catch (e: Throwable) {
            _statusText.value = "Native library load failed"
            addLog("[App] FATAL: ${e.localizedMessage}")
        }
    }

    private fun checkRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine() ?: ""
            process.waitFor(2, TimeUnit.SECONDS)
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    private fun initShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                addLog("[Shizuku] Binder is alive")
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    shizukuReady = true
                    _isShizukuReady.value = true
                    addLog("[Shizuku] Permission already granted.")
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

    fun clearChat() {
        _chatMessages.value = emptyList()
        addLog("[App] Chat cleared")
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

                val privilegeText = when {
                    hasRoot -> "You have ROOT access. You can use ANY shell command."
                    shizukuReady -> "You have ADB privileges via Shizuku. You can use 'dumpsys', 'logcat', and 'pm list packages'."
                    else -> "CRITICAL: You DO NOT have root or Shizuku. You CANNOT use 'dumpsys', 'logcat', 'pm install'. " +
                            "For battery, use 'cat /sys/class/power_supply/battery/capacity'. " +
                            "For RAM, use 'cat /proc/meminfo'. " +
                            "For CPU, use 'cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq'."
                }

                val promptBuilder = StringBuilder()
                promptBuilder.append("<|im_start|>system\n")
                promptBuilder.append(
                    "You are PocketMind, an autonomous Android shell agent. $privilegeText " +
                            "CRITICAL RULE: Do NOT explain what commands to use. You MUST execute them yourself. " +
                            "If the user asks a question, respond ONLY with: EXEC: <command>. " +
                            "For example, if they ask for battery, respond 'EXEC: cat /sys/class/power_supply/battery/capacity'. " +
                            "If the user asks for something impossible (like installing an app), just say 'I cannot do that without root' and STOP. " +
                            "For normal conversation (greetings), reply normally.\n"
                )
                promptBuilder.append("<|im_end|>\n")

                for (msg in history) {
                    if (msg.text.length > 300 || msg.text.contains("Exception")) continue

                    val role = when (msg.role) {
                        ChatRole.USER -> "user"
                        ChatRole.BOT -> "assistant"
                        ChatRole.SYSTEM -> "system"
                    }
                    promptBuilder.append("<|im_start|>$role\n${msg.text}\n<|im_end|>\n")
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

                if (trimmed.contains("I'm sorry, but I can't assist with that", ignoreCase = true) ||
                    trimmed.contains("I cannot assist with that", ignoreCase = true)) {
                    addLog("[AI Engine] Detected safety refusal. Retrying with different prompt...")
                    val retryPrompt = "<|im_start|>system\nYou are a helpful Android assistant. $privilegeText Answer the user.\n<|im_end|>\n<|im_start|>user\n$userInput\n<|im_end|>\n<|im_start|>assistant\n"
                    val retryResult = generateNative(retryPrompt) { logMessage -> addLog(logMessage.toString()) }
                    trimmed = retryResult.trim()
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
        if (!hasRoot && cmd.contains("pm ") && !cmd.contains("pm list packages") && !cmd.contains("pm path")) {
            addLog("[AI Engine] Blocked invalid 'pm' command: $cmd")
            updateLastBotMessage("Я не могу выполнить эту команду. Установка и обновление пакетов требуют root/ADB. Пожалуйста, установите приложение вручную.")
            return
        }

        val requiresShizuku = cmd.startsWith("pm ") || cmd.startsWith("dumpsys ") || cmd.startsWith("settings ") || cmd.startsWith("logcat ")
        if (!hasRoot && requiresShizuku && !shizukuReady) {
            addLog("[AI Engine] Blocked Shizuku-required command: $cmd")
            val hint = if (cmd.contains("battery", ignoreCase = true)) {
                "Используй: cat /sys/class/power_supply/battery/capacity"
            } else if (cmd.contains("logcat", ignoreCase = true)) {
                "Используй: dumpsys activity events"
            } else {
                "Используй: cat /proc/... или getprop"
            }
            appendMessage(ChatRole.BOT, "Эта команда требует прав Shizuku или Root. $hint")
            return
        }

        val lastBotMessages = _chatMessages.value.filter { it.role == ChatRole.BOT }.takeLast(2)
        val isLoop = lastBotMessages.any { it.text.contains(cmd, ignoreCase = true) }

        if (isLoop) {
            addLog("[AI Engine] LOOP DETECTED: $cmd")
            updateLastBotMessage("Похоже, эта команда не работает. Давай попробуем другой подход?")
            return
        }

        updateLastBotMessage("Executing: $cmd")
        addLog("[AI Engine] Model requested EXEC: $cmd")

        val shellResult = executeShellCommandInternal(cmd)
        appendMessage(ChatRole.BOT, shellResult)

        val isError = shellResult.contains("[Err]") || shellResult.contains("Error:", ignoreCase = true)
        if (isError) {
            addLog("[AI Engine] Command failed. Adding short system feedback.")
            appendMessage(ChatRole.SYSTEM, "Command failed. Suggest a different approach to the user.")
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

            val process: Process = when {
                hasRoot -> {
                    addLog("[Shell] Executing via ROOT")
                    Runtime.getRuntime().exec(arrayOf("su", "-c", finalCmd))
                }
                shizukuReady && Shizuku.pingBinder() -> {
                    addLog("[Shell] Executing via Shizuku (ADB) via Reflection")
                    try {
                        val method = Shizuku::class.java.getDeclaredMethod(
                            "newProcess",
                            Array<String>::class.java,
                            Array<String>::class.java,
                            String::class.java
                        )
                        method.isAccessible = true
                        method.invoke(null, arrayOf("sh", "-c", finalCmd), null, null) as ShizukuRemoteProcess
                    } catch (e: Exception) {
                        addLog("[Shizuku] Reflection failed: ${e.message}. Falling back to normal shell.")
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", finalCmd))
                    }
                }
                else -> {
                    addLog("[Shell] Executing via normal shell")
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", finalCmd))
                }
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