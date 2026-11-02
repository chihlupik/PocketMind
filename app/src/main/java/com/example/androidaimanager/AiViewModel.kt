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

    private val chatHistoryManager = ChatHistoryManager(application)
    private val userSkillsManager = UserSkillsManager(application)
    private val logsFile = File(application.filesDir, "pocketmind_logs.txt")

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages

    private val _allChats = MutableStateFlow<List<ChatSession>>(emptyList())
    val allChats: StateFlow<List<ChatSession>> = _allChats

    private var currentSession: ChatSession = ChatSession()

    private val _pendingCommand = MutableStateFlow<String?>(null)
    val pendingCommand: StateFlow<String?> = _pendingCommand

    private val _logList = MutableStateFlow<List<String>>(emptyList())
    val logList: StateFlow<List<String>> = _logList

    private val _isAiReady = MutableStateFlow(false)
    val isAiReady: StateFlow<Boolean> = _isAiReady

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable

    private val _isShizukuReady = MutableStateFlow(false)
    val isShizukuReady: StateFlow<Boolean> = _isShizukuReady

    private var currentDir: String = "/storage/emulated/0"
    private val stopRequested = AtomicBoolean(false)
    private var generationJob: Job? = null

    // ID текущей генерации для защиты от Race Condition
    private var currentGenerationId = 0

    private var hasRoot = false
    private var shizukuReady = false

    private var lastCommand: String? = null
    private var sameCommandCount = 0

    init {
        try {
            loadLogsFromFile()
            addLog("[App] =============================")
            addLog("[App] PocketMind started")
            System.loadLibrary("native-lib")
            if (!File(currentDir).canExecute()) currentDir = getApplication<Application>().filesDir.absolutePath

            hasRoot = checkRoot()
            _isRootAvailable.value = hasRoot
            addLog("[App] Root available: ${if (hasRoot) "YES" else "NO"}")

            initAiModel()
            initShizuku()
            loadHistoryAndStartNewChat()
        } catch (e: Throwable) {
            addLog("[App] FATAL: ${e.localizedMessage}")
        }
    }

    private fun loadLogsFromFile() {
        if (logsFile.exists()) {
            try {
                _logList.value = logsFile.readLines().takeLast(500)
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun loadHistoryAndStartNewChat() {
        viewModelScope.launch {
            _allChats.value = chatHistoryManager.getAllSessions().filter { it.messages.isNotEmpty() }
            startNewChat()
        }
    }

    fun startNewChat() {
        stopGeneration()
        currentSession = ChatSession()
        _chatMessages.value = emptyList()
    }

    fun loadChat(sessionId: String) {
        stopGeneration()
        val session = _allChats.value.find { it.id == sessionId } ?: return
        currentSession = session
        _chatMessages.value = session.messages.toList()
        addLog("[UI] Loaded chat: ${session.title}")
    }

    fun deleteCurrentChat() {
        stopGeneration()
        viewModelScope.launch {
            chatHistoryManager.deleteSessions(listOf(currentSession.id))
            _allChats.value = _allChats.value.filter { it.id != currentSession.id }
            addLog("[UI] Chat deleted: ${currentSession.title}")
            startNewChat()
        }
    }

    fun deleteSelectedChats(ids: List<String>) {
        stopGeneration()
        viewModelScope.launch {
            chatHistoryManager.deleteSessions(ids)
            _allChats.value = _allChats.value.filter { it.id !in ids }
            addLog("[UI] Deleted ${ids.size} chats")
            if (currentSession.id in ids) startNewChat()
        }
    }

    fun deleteAllChats() {
        stopGeneration()
        viewModelScope.launch {
            chatHistoryManager.clearAllSessions()
            _allChats.value = emptyList()
            addLog("[UI] All chats deleted")
            startNewChat()
        }
    }

    fun clearLogs() {
        _logList.value = emptyList()
        if (logsFile.exists()) logsFile.delete()
        addLog("[App] Logs cleared by user")
    }

    fun confirmCommand() {
        val cmd = _pendingCommand.value ?: return
        _pendingCommand.value = null
        viewModelScope.launch(Dispatchers.IO) {
            addLog("[Shell] Executing approved command: $cmd")
            updateLastBotMessage("Executing: $cmd", currentSession.id)
            val result = executeShellCommandInternal(cmd)
            addLog("[Shell] Output:\n$result")
            appendMessage(ChatRole.BOT, result)
        }
    }

    fun cancelCommand() {
        addLog("[Shell] Command cancelled by user: ${_pendingCommand.value}")
        _pendingCommand.value = null
        updateLastBotMessage("Command execution cancelled by user.", currentSession.id)
    }

    private fun checkRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine() ?: ""
            process.waitFor(2, TimeUnit.SECONDS)
            output.contains("uid=0")
        } catch (e: Exception) { false }
    }

    private fun initShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    shizukuReady = true
                    _isShizukuReady.value = true
                    addLog("[App] Shizuku available: YES")
                } else {
                    addLog("[App] Shizuku available: NO (requesting permission)")
                    Shizuku.addRequestPermissionResultListener { req, grant ->
                        if (req == 1001) {
                            shizukuReady = grant == PackageManager.PERMISSION_GRANTED
                            _isShizukuReady.value = shizukuReady
                            addLog("[App] Shizuku permission result: $shizukuReady")
                        }
                    }
                    Shizuku.requestPermission(1001)
                }
            } else {
                addLog("[App] Shizuku available: NO (not running)")
            }
        } catch (e: Throwable) {
            addLog("[App] Shizuku init failed: ${e.localizedMessage}")
        }
    }

    private fun initAiModel() {
        viewModelScope.launch(Dispatchers.IO) {
            val modelName = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
            val modelFile = File(getApplication<Application>().filesDir, modelName)

            if (!modelFile.exists()) {
                addLog("[App] Model not found. Copying from assets...")
                try {
                    getApplication<Application>().assets.open(modelName).copyTo(modelFile.outputStream())
                    addLog("[App] Model copied successfully.")
                } catch (e: Exception) {
                    addLog("[App] FATAL: Model copy failed: ${e.localizedMessage}")
                    return@launch
                }
            }

            if (modelFile.length() == 0L) {
                addLog("[App] FATAL: Model file is empty (0 bytes)! Corrupted.")
                _isAiReady.value = false
                return@launch
            }

            val sizeMB = modelFile.length() / (1024 * 1024)
            addLog("[App] Loading Model: ${modelFile.name} ($sizeMB MB)")

            _isAiReady.value = try {
                val success = initModelNative(modelFile.absolutePath)
                if (success) addLog("[App] Model initialized successfully") else addLog("[App] Model init failed")
                success
            } catch (e: Throwable) {
                addLog("[App] Native init error: ${e.localizedMessage}")
                false
            }
        }
    }

    fun processCommand(input: String) {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty()) return

        // ЗАЩИТА: Если C++ еще не освободил мьютекс, не принимаем новые команды
        if (_isGenerating.value) {
            addLog("[UI] Ignored input: engine is still busy")
            return
        }

        addLog("[User] $trimmedInput")
        lastCommand = null; sameCommandCount = 0
        appendMessage(ChatRole.USER, trimmedInput)

        val directCommands = listOf("dumpsys", "logcat", "pm ", "am ", "settings ", "getprop", "cat ", "ls ", "find ", "grep ", "wc ", "head ", "tail ", "df ", "du ", "top ", "ps ", "netstat ", "ip ", "ping ", "exec ", "sh ", "curl ")
        val isDirectCommand = directCommands.any { trimmedInput.startsWith(it, ignoreCase = true) } || trimmedInput.startsWith("`") || trimmedInput.contains(" | ")

        when {
            isDirectCommand -> {
                val cleanCmd = trimmedInput.removePrefix("exec ").removePrefix("sh ").replace("`", "")
                handleShell(cleanCmd, "[Direct Exec]")
            }
            trimmedInput.startsWith("cd ", ignoreCase = true) -> handleCd(trimmedInput.substring(3).trim())
            trimmedInput.equals("pwd", ignoreCase = true) -> appendMessage(ChatRole.BOT, currentDir)
            trimmedInput.equals("list skills", ignoreCase = true) || trimmedInput.equals("skills", ignoreCase = true) -> handleListSkills()
            trimmedInput.equals("clear skills", ignoreCase = true) -> handleClearSkills()
            trimmedInput.startsWith("learn skill ", ignoreCase = true) -> handleLearnSkill(trimmedInput.substring(12).trim())
            trimmedInput.startsWith("use skill ", ignoreCase = true) -> handleUseSkill(trimmedInput.substring(10).trim())
            else -> sendPromptToLlama(trimmedInput)
        }
    }

    private suspend fun buildPrompt(userInput: String): String {
        val privilegeText = when {
            hasRoot -> "ROOT = YES, SHIZUKU = YES"
            shizukuReady -> "ROOT = NO, SHIZUKU = YES"
            else -> "ROOT = NO, SHIZUKU = NO"
        }
        val skills = userSkillsManager.getAllSkills()
        val skillsBlock = if (skills.isEmpty()) "User skills: none.\n" else "User skills: ${skills.joinToString(", ") { it.name }}.\nIMPORTANT: If user asks to use a skill, reply EXACTLY: EXEC: use skill <name> [args]\n"

        return "<|im_start|>system\n" +
                "You are PocketMind, an AI shell agent on ANDROID (NOT Linux).\nPrivileges: $privilegeText\n$skillsBlock\n" +
                "MODES:\n1) CONVERSATION: greetings, questions, explanations -> plain text. NEVER use EXEC:.\n" +
                "2) EXECUTION: user wants action -> reply EXACTLY ONE line: EXEC: <shell command>\n\n" +
                "RULES:\n- NO Linux tools: sudo, apt, systemctl, free, df, /home, /tmp.\n- For file paths use /sdcard/.\n" +
                "- To write text to a file use echo \"text\" > /sdcard/file.txt. NEVER use touch for text.\n" +
                "- Use curl for HTTP requests. NEVER use grep or sed after curl.\n" +
                "- To list files in a directory, use 'ls -la /sdcard/path'. NEVER use df to list files.\n" + // НОВОЕ ПРАВИЛО
                "- CRITICAL: If lacking privileges, DO NOT run (dumpsys, pm, am, ps, top). Explain in plain text.\n" +
                "- DO NOT use curl to fetch system information. Use local files.\n\n" +
                "ANDROID CHEAT SHEET:\n- Battery: dumpsys battery\n- RAM: cat /proc/meminfo | grep MemAvailable\n" +
                "- Storage: df -h /sdcard\n- Uptime: cat /proc/uptime\n- Local IP: ip addr show wlan0\n" +
                "- CPU Info: cat /proc/cpuinfo\n" +
                "- List files: ls -la /sdcard/Download/\n- Read file: cat /sdcard/Download/file.txt\n" +
                "- Write file: echo \"text\" > /sdcard/Download/file.txt\n- Network: curl -s <url>\n\n" +
                "Examples:\nuser: hi\nassistant: Hello! How can I help you today?\n" +
                "user: how much RAM is free?\nassistant: EXEC: cat /proc/meminfo | grep MemAvailable\n" +
                "user: show cpu info\nassistant: EXEC: cat /proc/cpuinfo\n" +
                "user: get my IP\nassistant: EXEC: curl -s https://api.ipify.org\n" +
                "user: create test.txt with \"Hello\"\nassistant: EXEC: echo \"Hello\" > /sdcard/Download/test.txt\n" +
                "user: show files in Downloads folder\nassistant: EXEC: ls -la /sdcard/Download/\n" + // ДОБАВЛЕН ПРИМЕР
                "user: show processes\nassistant: I cannot list system processes because that requires Root or Shizuku privileges.\n" +
                "<|im_end|>\n<|im_start|>user\n$userInput\n<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun sendPromptToLlama(userInput: String) {
        if (!_isAiReady.value || _isGenerating.value) return
        _isGenerating.value = true
        stopRequested.set(false)

        currentGenerationId++
        val myGenerationId = currentGenerationId
        val targetSessionId = currentSession.id

        addLog("[AI] Generating response (ID: $myGenerationId)...")
        appendMessage(ChatRole.BOT, "...")

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val formattedPrompt = buildPrompt(userInput)
                coroutineContext.ensureActive()

                // Ждем ответа от C++
                val result = generateNative(formattedPrompt) { addLog(it.toString()) }

                coroutineContext.ensureActive()

                // Если пока мы ждали, была запущена другая генерация, игнорируем этот ответ
                if (myGenerationId != currentGenerationId) {
                    addLog("[AI] Generation $myGenerationId ignored (superseded by $currentGenerationId)")
                    return@launch
                }

                var trimmed = result.trim()
                if (trimmed.startsWith("```")) {
                    val firstLineEnd = trimmed.indexOf("\n"); val lastTicks = trimmed.lastIndexOf("```")
                    if (firstLineEnd != -1 && lastTicks > firstLineEnd) trimmed = trimmed.substring(firstLineEnd, lastTicks).trim()
                }

                addLog("[AI Response] $trimmed")

                if (trimmed.lowercase().startsWith("exec:")) {
                    val cmd = trimmed.substring(5).trim().lines().first().trim()
                    if (cmd.isNotEmpty()) executeCommandWithLoopProtection(cmd, targetSessionId) else updateLastBotMessage("(empty command)", targetSessionId)
                } else {
                    updateLastBotMessage(trimmed.ifBlank { "(empty response)" }, targetSessionId)
                }
            } catch (e: Exception) {
                if (myGenerationId == currentGenerationId) {
                    addLog("[AI] Generation error: ${e.localizedMessage}")
                    updateLastBotMessage("[Error]", targetSessionId)
                }
            } finally {
                // Обновляем флаги только если это была ПОСЛЕДНЯЯ (актуальная) генерация
                if (myGenerationId == currentGenerationId) {
                    _isGenerating.value = false
                    generationJob = null
                    if (stopRequested.get()) {
                        updateLastBotMessage("[Stopped by user]", targetSessionId)
                        addLog("[AI] Generation stopped by user")
                    }
                }
            }
        }
    }

    fun stopGeneration() {
        if (!_isGenerating.value) return
        stopRequested.set(true)
        try { requestStopNative() } catch (_: Throwable) {}

        // Мгновенно показываем пользователю, что мы останавливаемся
        updateLastBotMessage("Stopping...", currentSession.id)
        addLog("[AI] Stop requested. Waiting for engine to halt...")
        // Флаг _isGenerating НЕ сбрасываем здесь, чтобы не запустить новый промпт раньше времени
    }

    private fun executeCommandWithLoopProtection(cmd: String, targetSessionId: String) {
        val privilegedCommands = listOf("dumpsys", "pm ", "am ", "ps ", "top")
        if (privilegedCommands.any { cmd.contains(it, ignoreCase = true) } && !hasRoot && !shizukuReady) {
            addLog("[Security] Blocked privileged command: $cmd")
            updateLastBotMessage("Access Denied: `$cmd` requires Root or Shizuku.", targetSessionId); return
        }
        if (cmd.contains("sudo ") || (cmd.contains("chmod ") && cmd.contains("/sys"))) {
            addLog("[Security] Blocked Linux command: $cmd")
            updateLastBotMessage("`$cmd` won't work on Android.", targetSessionId); return
        }
        if (cmd == lastCommand) {
            sameCommandCount++
            if (sameCommandCount >= 2) {
                addLog("[Security] Blocked loop command: $cmd")
                updateLastBotMessage("`$cmd` already ran.", targetSessionId); return
            }
        } else { lastCommand = cmd; sameCommandCount = 1 }

        addLog("[AI] Proposed command: $cmd")
        updateLastBotMessage("Waiting for approval to execute:\n`$cmd`", targetSessionId)

        if (currentSession.id == targetSessionId) {
            _pendingCommand.value = cmd
        }
    }

    private fun handleCd(target: String) {
        val candidate = File(currentDir, target).canonicalFile
        if (!candidate.isDirectory || !candidate.canExecute()) {
            appendMessage(ChatRole.BOT, "Access denied: $target"); return
        }
        currentDir = candidate.absolutePath
        addLog("[Shell] Changed dir to: $currentDir")
        appendMessage(ChatRole.BOT, "Directory changed to: $currentDir")
    }

    private fun handleShell(command: String, logPrefix: String) {
        addLog("$logPrefix Proposed: $command")
        updateLastBotMessage("Waiting for approval to execute:\n`$command`", currentSession.id); _pendingCommand.value = command
    }

    private fun handleListSkills() {
        viewModelScope.launch {
            val skills = userSkillsManager.getAllSkills()
            addLog("[Skills] Listed ${skills.size} skills")
            appendMessage(ChatRole.BOT, if (skills.isEmpty()) "No skills learned yet." else "Skills:\n" + skills.joinToString("\n") { "• ${it.name}" })
        }
    }

    private fun handleClearSkills() {
        viewModelScope.launch {
            userSkillsManager.clearAllSkills()
            addLog("[Skills] Cleared all skills")
            appendMessage(ChatRole.BOT, "All skills deleted.")
        }
    }

    private fun handleLearnSkill(content: String) {
        val parts = content.split(" ", limit = 2)
        if (parts.size < 2) {
            appendMessage(ChatRole.BOT, "Format: learn skill <name> <cmd>"); return
        }
        viewModelScope.launch {
            userSkillsManager.addSkill(UserSkill(parts[0], "Shell command skill", "shell", JSONObject().put("command", parts[1])))
            addLog("[Skills] Learned new skill: ${parts[0]}")
            appendMessage(ChatRole.BOT, "Learned skill '${parts[0]}'")
        }
    }

    private fun handleUseSkill(input: String) {
        val parts = input.split(" ", limit = 2)
        viewModelScope.launch {
            val found = userSkillsManager.getAllSkills().find { it.name.equals(parts[0], ignoreCase = true) }
            if (found == null) {
                appendMessage(ChatRole.BOT, "Skill not found."); return@launch
            }
            val finalCmd = if (parts.size > 1) "${found.config.optString("command", "")} ${parts[1]}" else found.config.optString("command", "")
            addLog("[Skills] Proposed skill execution: $finalCmd")
            updateLastBotMessage("Waiting for approval to execute skill:\n`$finalCmd`", currentSession.id); _pendingCommand.value = finalCmd
        }
    }

    private fun executeShellCommandInternal(cmd: String): String {
        return try {
            val finalCmd = "cd '$currentDir' && $cmd"
            val process = when {
                hasRoot -> Runtime.getRuntime().exec(arrayOf("su", "-c", finalCmd))
                shizukuReady && Shizuku.pingBinder() -> {
                    try {
                        val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                        method.isAccessible = true
                        method.invoke(null, arrayOf("sh", "-c", finalCmd), null, null) as ShizukuRemoteProcess
                    } catch (e: Exception) { Runtime.getRuntime().exec(arrayOf("sh", "-c", finalCmd)) }
                }
                else -> Runtime.getRuntime().exec(arrayOf("sh", "-c", finalCmd))
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = StringBuilder()
            var errCount = 0
            var totalChars = 0

            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                reader.close(); errorReader.close()
                addLog("[Shell] Command timed out: $cmd")
                return "Command timed out after 5 seconds."
            }

            var line: String? = reader.readLine()
            while (line != null) {
                if (totalChars > 3000) break
                output.append(line).append("\n")
                totalChars += line.length
                line = reader.readLine()
            }

            var errLine: String? = errorReader.readLine()
            while (errLine != null) {
                if (errCount < 10) {
                    output.append("[Err] ").append(errLine).append("\n")
                    errCount++
                }
                errLine = errorReader.readLine()
            }

            val exitCode = process.exitValue()
            when {
                exitCode != 0 && output.isBlank() -> "Command failed (exit $exitCode)"
                output.isBlank() -> "Executed successfully with empty output."
                else -> {
                    val res = output.toString().trim()
                    if (totalChars > 3000) "$res\n\n...[Output truncated at 3000 chars to prevent OOM]" else res
                }
            }
        } catch (e: Exception) {
            addLog("[Shell] Execution error: ${e.localizedMessage}")
            "Execution error: ${e.localizedMessage}"
        }
    }

    private fun appendMessage(role: ChatRole, text: String): String {
        val msg = ChatMessage(role = role, text = text)
        _chatMessages.value = _chatMessages.value + msg
        currentSession.messages.add(msg)

        if (currentSession.messages.size == 1 && role == ChatRole.USER) {
            currentSession.title = text.take(30) + if (text.length > 30) "..." else ""
            _allChats.value = listOf(currentSession) + _allChats.value.filter { it.id != currentSession.id }
            viewModelScope.launch { chatHistoryManager.saveSession(currentSession) }
        } else {
            viewModelScope.launch { chatHistoryManager.saveSession(currentSession) }
        }
        return msg.id
    }

    private fun updateLastBotMessage(text: String, targetSessionId: String) {
        if (currentSession.id != targetSessionId) return

        val list = _chatMessages.value.toMutableList()
        val idx = list.indexOfLast { it.role == ChatRole.BOT }
        if (idx >= 0) {
            list[idx] = list[idx].copy(text = text)
            _chatMessages.value = list
            currentSession.messages.clear(); currentSession.messages.addAll(list)
            viewModelScope.launch { chatHistoryManager.saveSession(currentSession) }
        }
    }

    @Synchronized
    private fun addLog(logText: String) {
        val current = _logList.value
        val updated = if (current.size > 500) current.takeLast(400) + logText else current + logText
        _logList.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            try {
                logsFile.writeText(updated.joinToString("\n"))
            } catch (e: Exception) { /* ignore */ }
        }
    }

    external fun initModelNative(modelPath: String): Boolean
    external fun generateNative(prompt: String, logListener: (Any) -> Unit): String
    external fun requestStopNative()
    external fun freeModelNative()
}