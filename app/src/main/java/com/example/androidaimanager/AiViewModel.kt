package com.example.androidaimanager

import android.app.Application
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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

enum class ChatRole { USER, BOT }

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

    // Текущая рабочая директория (сохраняется между командами)
    private var currentDir: String = "/storage/emulated/0"

    // Флаг остановки генерации
    private val stopRequested = AtomicBoolean(false)
    private var generationJob: Job? = null

    // Termux python
    private val termuxPython = "/data/data/com.termux/files/usr/bin/python"

    init {
        try {
            System.loadLibrary("native-lib")
            // Проверяем, что рабочая директория доступна
            if (!File(currentDir).canExecute()) {
                currentDir = getApplication<Application>().filesDir.absolutePath
                addLog("[App] /storage/emulated/0 недоступна, используем ${currentDir}")
            }
            initAiModel()
        } catch (e: Throwable) {
            _statusText.value = "Native library load failed"
            addLog("[App] FATAL: ${e.localizedMessage}")
        }
    }

    private fun initAiModel() {
        viewModelScope.launch(Dispatchers.IO) {
            val modelFile = File(getApplication<Application>().filesDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf")
            if (!modelFile.exists()) {
                _isAiReady.value = false
                _statusText.value = "Model file not found"
                addLog("[App] Model missing: ${modelFile.absolutePath}")
                return@launch
            }
            val success = try {
                initModelNative(modelFile.absolutePath)
            } catch (e: Throwable) {
                addLog("[App] initModelNative threw: ${e.localizedMessage}")
                false
            }
            _isAiReady.value = success
            _statusText.value = if (success) "Model Ready" else "Model Init Failed"
            addLog(if (success) "[App] Model initialized successfully" else "[App] Failed to initialize model")
        }
    }

    /**
     * Главная точка входа. Базовые команды выполняются мгновенно, без LLM.
     */
    fun processCommand(input: String) {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty()) return

        appendUserMessage(trimmedInput)

        when {
            trimmedInput.startsWith("cd ", ignoreCase = true) -> handleCd(trimmedInput.substring(3).trim())

            trimmedInput.equals("pwd", ignoreCase = true) -> {
                appendBotMessage(currentDir)
                addLog("[Shell] pwd -> $currentDir")
            }

            trimmedInput.equals("ls", ignoreCase = true) || trimmedInput.equals("dir", ignoreCase = true) ->
                handleShell("ls -la", "[Shell] ls")

            trimmedInput.startsWith("mkdir ", ignoreCase = true) ->
                handleShell("mkdir -p ${shellEscape(trimmedInput.substring(6).trim())}", "[Shell] mkdir")

            trimmedInput.startsWith("rm ", ignoreCase = true) ->
                handleShell("rm -rf ${shellEscape(trimmedInput.substring(3).trim())}", "[Shell] rm")

            trimmedInput.startsWith("find ", ignoreCase = true) ->
                handleShell("find . -name ${shellEscape("*" + trimmedInput.substring(5).trim() + "*")}", "[Shell] find")

            trimmedInput.equals("list skills", ignoreCase = true) ||
                    trimmedInput.equals("skills", ignoreCase = true) -> handleListSkills()

            trimmedInput.equals("clear skills", ignoreCase = true) -> handleClearSkills()

            trimmedInput.startsWith("exec ", ignoreCase = true) ->
                handleShell(trimmedInput.substring(5).trim(), "[Direct Exec]")

            trimmedInput.startsWith("sh ", ignoreCase = true) ->
                handleShell(trimmedInput.substring(3).trim(), "[Direct Exec]")

            trimmedInput.startsWith("run ", ignoreCase = true) -> handleRunScript(trimmedInput.substring(4).trim())

            trimmedInput.startsWith("cat ", ignoreCase = true) ->
                handleShell("cat ${shellEscape(trimmedInput.substring(4).trim())}", "[File Reader]")

            trimmedInput.startsWith("learn skill ", ignoreCase = true) ->
                handleLearnSkill(trimmedInput.substring(12).trim())

            trimmedInput.startsWith("use skill ", ignoreCase = true) ->
                handleUseSkill(trimmedInput.substring(10).trim())

            trimmedInput.equals("help", ignoreCase = true) -> handleHelp()

            else -> sendPromptToLlama(trimmedInput)
        }
    }

    // ---------------------- Command handlers ----------------------

    private fun handleCd(target: String) {
        if (target.isBlank()) {
            appendBotMessage("Usage: cd <folder>")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val candidate = File(currentDir, target).canonicalFile
            if (!candidate.isDirectory || !candidate.canExecute()) {
                appendBotMessage("Directory not found or access denied: $target")
                return@launch
            }
            currentDir = candidate.absolutePath
            appendBotMessage("Directory changed to: $currentDir")
            addLog("[Shell] cd -> $currentDir")
        }
    }

    private fun handleShell(command: String, logPrefix: String) {
        addLog("$logPrefix Command: $command")
        viewModelScope.launch(Dispatchers.IO) {
            val result = executeShellCommandInternal(command)
            appendBotMessage(result)
        }
    }

    private fun handleRunScript(scriptPath: String) {
        if (scriptPath.isBlank()) {
            appendBotMessage("Usage: run <file>")
            return
        }
        val command = when {
            scriptPath.endsWith(".py", ignoreCase = true) -> {
                if (!File(termuxPython).exists()) {
                    appendBotMessage("Python not found. Install Termux + python to run .py scripts.")
                    return
                }
                "$termuxPython ${shellEscape(scriptPath)}"
            }
            scriptPath.endsWith(".sh", ignoreCase = true) -> "sh ${shellEscape(scriptPath)}"
            else -> "sh ${shellEscape(scriptPath)}"
        }
        addLog("[Script Runner] Executing: $scriptPath")
        viewModelScope.launch(Dispatchers.IO) {
            val result = executeShellCommandInternal(command)
            appendBotMessage(result)
        }
    }

    private fun handleListSkills() {
        viewModelScope.launch {
            val skills = userSkillsManager.getAllSkills()
            val response = if (skills.isEmpty()) {
                "No skills learned yet. Use 'learn skill <name> = <command>' to add one."
            } else {
                "Saved skills:\n" + skills.joinToString("\n") { "• ${it.name}: ${it.description}" }
            }
            appendBotMessage(response)
            addLog("[Skill Engine] Listed all saved skills")
        }
    }

    private fun handleClearSkills() {
        viewModelScope.launch {
            userSkillsManager.clearAllSkills()
            appendBotMessage("All learned skills have been deleted.")
            addLog("[Skill Engine] Cleared all skills")
        }
    }

    private fun handleLearnSkill(content: String) {
        if (!content.contains("=")) {
            appendBotMessage("Format error. Use: learn skill <name> = <command>")
            return
        }
        val parts = content.split("=", limit = 2)
        val name = parts[0].trim()
        val command = parts[1].trim()
        if (name.isBlank() || command.isBlank()) {
            appendBotMessage("Format error. Use: learn skill <name> = <command>")
            return
        }

        val config = JSONObject().put("command", command)
        val newSkill = UserSkill(
            name = name,
            description = "Shell command skill",
            actionType = "shell",
            config = config
        )
        viewModelScope.launch {
            userSkillsManager.addSkill(newSkill)
            appendBotMessage("Learned skill '$name' -> '$command'")
            addLog("[Skill Engine] Saved skill '$name'")
        }
    }

    private fun handleUseSkill(fullSkillInput: String) {
        if (fullSkillInput.isBlank()) {
            appendBotMessage("Usage: use skill <name> [arg]")
            return
        }
        val parts = fullSkillInput.split(" ", limit = 2)
        val skillName = parts[0]
        val param = if (parts.size > 1) parts[1] else ""

        viewModelScope.launch {
            val skills = userSkillsManager.getAllSkills()
            val foundSkill = skills.find { it.name.equals(skillName, ignoreCase = true) }

            if (foundSkill == null || foundSkill.actionType != "shell") {
                appendBotMessage("Skill '$skillName' not found or not a shell skill.")
                return@launch
            }

            val rawCommand = foundSkill.config.optString("command", "")
            if (rawCommand.isBlank()) {
                appendBotMessage("Skill '$skillName' has empty command.")
                return@launch
            }

            val finalCommand = if (rawCommand.contains("{text}")) {
                rawCommand.replace("{text}", shellEscape(param))
            } else if (param.isNotEmpty()) {
                "$rawCommand ${shellEscape(param)}"
            } else {
                rawCommand
            }

            addLog("[Skill Engine] Executing skill: $skillName")
            val result = withContext(Dispatchers.IO) {
                executeShellCommandInternal(finalCommand)
            }
            appendBotMessage(result)
        }
    }

    private fun handleHelp() {
        val helpText = """
            Available Direct Commands:
            • cd <folder> — Change directory (remembered)
            • pwd — Show current working directory
            • ls (or dir) — List files in current directory
            • mkdir <name> — Create directory
            • rm <target> — Remove file or folder
            • find <query> — Search files
            • exec <cmd> — Run shell command in current dir
            • run <file> — Run .py or .sh script automatically
            • cat <file> — Read file content
            • list skills — Show all saved skills
            • learn skill <name> = <cmd> — Train a new skill
            • use skill <name> [arg] — Run a saved skill
            • clear skills — Delete all saved skills
            • help — Show this help message
        """.trimIndent()
        appendBotMessage(helpText)
    }

    // ---------------------- Shell execution ----------------------

    /**
     * Экранирование одинарных кавычек для безопасной вставки в sh -c '...'
     */
    private fun shellEscape(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun executeShellCommandInternal(cmd: String): String {
        return try {
            var formattedCmd = cmd

            // Подмена python на termux python, если команда начинается с python
            if (formattedCmd.startsWith("python ", true) || formattedCmd.startsWith("python3 ", true)) {
                if (File(termuxPython).exists()) {
                    formattedCmd = formattedCmd
                        .replaceFirst("python3 ", "$termuxPython ")
                        .replaceFirst("python ", "$termuxPython ")
                }
            }

            val finalCmd = "cd ${shellEscape(currentDir)} && $formattedCmd"

            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", finalCmd))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                output.append("[Err] ").append(line).append("\n")
            }

            val exitCode = process.waitFor()
            if (exitCode != 0 && output.isBlank()) {
                "Command failed (exit $exitCode)"
            } else if (output.isBlank()) {
                "Executed successfully with empty output."
            } else {
                output.toString().trim().take(3000)
            }
        } catch (e: Exception) {
            "Execution error: ${e.localizedMessage}"
        }
    }

    // ---------------------- LLM ----------------------

    private fun sendPromptToLlama(userInput: String) {
        _isGenerating.value = true
        stopRequested.set(false)

        // Добавляем placeholder, чтобы UI сразу отреагировал
        val placeholderId = appendBotMessage("...")

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Собираем историю диалога (последние 10 сообщений)
                val history = _chatMessages.value
                    .filter { it.id != placeholderId && it.text != "..." }
                    .takeLast(10)

                val promptBuilder = StringBuilder()
                promptBuilder.append("<|im_start|>system\n")
                promptBuilder.append(
                    "You are PocketMind, a precise offline AI assistant and shell automation tool on Android. " +
                            "Answer concisely. Never simulate user inputs. " +
                            "If the user asks to execute a shell command, reply with exactly: EXEC: <command>\n"
                )
                promptBuilder.append("<|im_end|>\n")

                for (msg in history) {
                    val role = if (msg.role == ChatRole.USER) "user" else "assistant"
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
                val trimmed = result.trim()

                // Обработка EXEC: директивы от модели
                if (trimmed.startsWith("EXEC:", ignoreCase = true)) {
                    val cmd = trimmed.substringAfter("EXEC:").trim()
                    updateLastBotMessage("Executing: $cmd")
                    addLog("[AI Engine] Model requested EXEC: $cmd")
                    val shellResult = executeShellCommandInternal(cmd)
                    appendBotMessage(shellResult)
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

    fun stopGeneration() {
        stopRequested.set(true)
        try {
            requestStopNative()
        } catch (_: Throwable) {}
        generationJob?.cancel()
        _isGenerating.value = false
        updateLastBotMessage("[Stopped]")
    }

    // ---------------------- Helpers ----------------------

    private fun appendUserMessage(text: String): String {
        val msg = ChatMessage(role = ChatRole.USER, text = text)
        _chatMessages.value = _chatMessages.value + msg
        return msg.id
    }

    @Synchronized
    private fun appendBotMessage(text: String): String {
        val msg = ChatMessage(role = ChatRole.BOT, text = text)
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
            appendBotMessage(text)
        }
    }

    @Synchronized
    private fun addLog(logText: String) {
        // Ограничиваем размер лога
        val current = _logList.value
        val updated = if (current.size > 500) current.takeLast(400) + logText else current + logText
        _logList.value = updated
    }

    // ---------------------- Native methods ----------------------

    external fun initModelNative(modelPath: String): Boolean
    external fun generateNative(prompt: String, logListener: (Any) -> Unit): String
    external fun requestStopNative()
    external fun freeModelNative()

    override fun onCleared() {
        super.onCleared()
        try {
            requestStopNative()
            freeModelNative()
        } catch (_: Throwable) {}
    }
}