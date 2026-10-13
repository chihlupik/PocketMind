package com.example.androidaimanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class ChatMessage(
    val role: String,
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

    // Постоянная рабочая директория (сохраняет состояние между командами cd)
    private var currentDir = "/storage/emulated/0"

    init {
        System.loadLibrary("native-lib")
        initAiModel()
    }

    private fun initAiModel() {
        viewModelScope.launch(Dispatchers.IO) {
            val modelFile = File(getApplication<Application>().filesDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf")
            val success = initModelNative(modelFile.absolutePath)
            _isAiReady.value = success
            _statusText.value = if (success) "Model Ready" else "Model Init Failed"
            addLog(if (success) "[App] Model initialized successfully" else "[App] Failed to initialize model")
        }
    }

    /**
     * Главная точка входа. Все базовые команды обрабатываются мгновенно,
     * исключая любые галлюцинации LLM.
     */
    fun processCommand(input: String) {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty()) return

        appendUserMessage(trimmedInput)

        when {
            // 1. Смена директории (cd <folder>) с сохранением пути
            trimmedInput.startsWith("cd ", ignoreCase = true) -> {
                val target = trimmedInput.substring(3).trim()
                viewModelScope.launch(Dispatchers.IO) {
                    val testCmd = "cd '$currentDir' && cd '$target' && pwd"
                    val result = executeShellCommandInternal(testCmd)
                    if (!result.contains("[Err]") && !result.contains("error", true)) {
                        currentDir = result.trim()
                        appendBotMessage("Directory changed to: $currentDir")
                        addLog("[Shell] cd -> $currentDir")
                    } else {
                        appendBotMessage("Directory not found or access denied: $target")
                    }
                }
            }

            // 2. Текущая рабочая директория (pwd)
            trimmedInput.equals("pwd", ignoreCase = true) -> {
                appendBotMessage(currentDir)
            }

            // 3. Список файлов в текущей папке (ls / dir)
            trimmedInput.equals("ls", ignoreCase = true) || trimmedInput.equals("dir", ignoreCase = true) -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val result = executeShellCommandInternal("ls -la")
                    appendBotMessage(result)
                }
            }

            // 4. Создание папки (mkdir <name>)
            trimmedInput.startsWith("mkdir ", ignoreCase = true) -> {
                val dirName = trimmedInput.substring(6).trim()
                viewModelScope.launch(Dispatchers.IO) {
                    val result = executeShellCommandInternal("mkdir -p '$dirName'")
                    appendBotMessage("Directory '$dirName' processed.\n$result")
                    addLog("[Shell] mkdir $dirName")
                }
            }

            // 5. Удаление файла или папки (rm <name>)
            trimmedInput.startsWith("rm ", ignoreCase = true) -> {
                val target = trimmedInput.substring(3).trim()
                viewModelScope.launch(Dispatchers.IO) {
                    val result = executeShellCommandInternal("rm -rf '$target'")
                    appendBotMessage("Removed '$target'.\n$result")
                    addLog("[Shell] rm -rf $target")
                }
            }

            // 6. Поиск файлов (find <query>)
            trimmedInput.startsWith("find ", ignoreCase = true) -> {
                val query = trimmedInput.substring(5).trim()
                viewModelScope.launch(Dispatchers.IO) {
                    val result = executeShellCommandInternal("find . -name '*$query*'")
                    appendBotMessage(if (result.isBlank()) "Nothing found." else result)
                    addLog("[Shell] find $query")
                }
            }

            // 7. Просмотр списка сохраненных навыков
            trimmedInput.equals("list skills", ignoreCase = true) ||
                    trimmedInput.equals("skills", ignoreCase = true) -> {
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

            // 8. Очистка всех навыков
            trimmedInput.equals("clear skills", ignoreCase = true) -> {
                viewModelScope.launch {
                    userSkillsManager.clearAllSkills()
                    appendBotMessage("All learned skills have been deleted.")
                    addLog("[Skill Engine] Cleared all skills")
                }
            }

            // 9. Прямое исполнение shell-команд (exec / sh)
            trimmedInput.startsWith("exec ", ignoreCase = true) ||
                    trimmedInput.startsWith("sh ", ignoreCase = true) -> {
                val command = if (trimmedInput.startsWith("exec ", ignoreCase = true)) {
                    trimmedInput.substring(5)
                } else {
                    trimmedInput.substring(3)
                }.trim()

                addLog("[Direct Exec] Command: $command")
                viewModelScope.launch(Dispatchers.IO) {
                    val result = executeShellCommandInternal(command)
                    appendBotMessage(result)
                }
            }

            // 10. Умный запуск скрипта (run <file>)
            trimmedInput.startsWith("run ", ignoreCase = true) -> {
                val scriptPath = trimmedInput.substring(4).trim()
                val command = when {
                    scriptPath.endsWith(".py", ignoreCase = true) -> "python $scriptPath"
                    scriptPath.endsWith(".sh", ignoreCase = true) -> "sh $scriptPath"
                    else -> "sh $scriptPath"
                }

                addLog("[Script Runner] Executing script: $scriptPath")
                viewModelScope.launch(Dispatchers.IO) {
                    val result = executeShellCommandInternal(command)
                    appendBotMessage(result)
                }
            }

            // 11. Быстрое чтение файла (cat <file>)
            trimmedInput.startsWith("cat ", ignoreCase = true) -> {
                val fileName = trimmedInput.substring(4).trim()
                addLog("[File Reader] Reading file: $fileName")
                viewModelScope.launch(Dispatchers.IO) {
                    val result = executeShellCommandInternal("cat $fileName")
                    appendBotMessage(result)
                }
            }

            // 12. Обучение навыку (learn skill <name> = <cmd>)
            trimmedInput.startsWith("learn skill ", ignoreCase = true) -> {
                val content = trimmedInput.substring(12).trim()
                if (content.contains("=")) {
                    val parts = content.split("=", limit = 2)
                    val name = parts[0].trim()
                    val command = parts[1].trim()

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
                } else {
                    appendBotMessage("Format error. Use: learn skill <name> = <command>")
                }
            }

            // 13. Вызов навыка (use skill <name> [arg])
            trimmedInput.startsWith("use skill ", ignoreCase = true) -> {
                val fullSkillInput = trimmedInput.substring(10).trim()
                val parts = fullSkillInput.split(" ", limit = 2)
                val skillName = parts[0]
                val param = if (parts.size > 1) parts[1] else ""

                viewModelScope.launch {
                    val skills = userSkillsManager.getAllSkills()
                    val foundSkill = skills.find { it.name.equals(skillName, ignoreCase = true) }

                    if (foundSkill != null && foundSkill.actionType == "shell") {
                        val rawCommand = foundSkill.config.optString("command", "")
                        val finalCommand = if (rawCommand.contains("{text}")) {
                            rawCommand.replace("{text}", param)
                        } else if (param.isNotEmpty()) {
                            "$rawCommand $param"
                        } else {
                            rawCommand
                        }
                        addLog("[Skill Engine] Direct execution of skill: $skillName")
                        val result = executeShellCommandInternal(finalCommand)
                        appendBotMessage(result)
                    } else {
                        appendBotMessage("Skill '$skillName' not found or not a shell skill.")
                    }
                }
            }

            // 14. Справка (help)
            trimmedInput.equals("help", ignoreCase = true) -> {
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

            // 15. Остальные запросы уходят в локальную LLM
            else -> {
                addLog("[AI Engine] Parsing intent via LLM...")
                sendPromptToLlama(trimmedInput)
            }
        }
    }

    /**
     * Внутренний метод выполнения shell-команд с учетом текущей директории currentDir
     */
    private fun executeShellCommandInternal(cmd: String): String {
        return try {
            var formattedCmd = cmd

            if (formattedCmd.startsWith("python ", true) || formattedCmd.startsWith("python3 ", true)) {
                val termuxPython = "/data/data/com.termux/files/usr/bin/python"
                if (File(termuxPython).exists()) {
                    formattedCmd = formattedCmd
                        .replaceFirst("python3", termuxPython)
                        .replaceFirst("python", termuxPython)
                }
            }

            val finalCmd = "cd '$currentDir' && $formattedCmd"

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

            process.waitFor()
            if (output.isBlank()) "Executed successfully with empty output." else output.toString().trim().take(1500)
        } catch (e: Exception) {
            "Execution error: ${e.localizedMessage}"
        }
    }

    private fun sendPromptToLlama(prompt: String) {
        _isGenerating.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = generateNative(prompt) { logMessage ->
                    addLog(logMessage.toString())
                }
                appendBotMessage(result)
            } catch (e: Exception) {
                appendBotMessage("Generation error: ${e.localizedMessage}")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun appendUserMessage(text: String) {
        _chatMessages.value = _chatMessages.value + ChatMessage("User", text)
    }

    private fun appendBotMessage(text: String) {
        _chatMessages.value = _chatMessages.value + ChatMessage("Bot", text)
    }

    private fun addLog(logText: String) {
        _logList.value = _logList.value + logText
    }

    // Нативные методы JNI
    external fun initModelNative(modelPath: String): Boolean
    external fun generateNative(prompt: String, logListener: (Any) -> Unit): String
    external fun freeModelNative()

    override fun onCleared() {
        super.onCleared()
        freeModelNative()
    }
}