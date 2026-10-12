package com.example.androidaimanager

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(val role: String, val text: String)

class AiViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    private external fun initModelNative(modelPath: String): Boolean
    private external fun generateNative(prompt: String, logCallback: (String) -> Unit): String
    private external fun freeModelNative()

    val chatMessages = mutableStateListOf<ChatMessage>()
    val logList = mutableStateListOf<String>()

    var statusText by mutableStateOf("Loading AI model...")
    var isAiReady by mutableStateOf(false)
    var isGenerating by mutableStateOf(false)

    private val skillsManager = UserSkillsManager(application)

    init {
        initializeAi()
    }

    fun addLog(msg: String) {
        viewModelScope.launch(Dispatchers.Main) {
            logList.add(msg)
        }
    }

    private fun initializeAi() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                addLog("[App] Preparing model from assets...")
                val modelName = "qwen2.5-0.5b-instruct-q4_k_m.gguf"

                val file = copyAssetToFile(getApplication(), modelName)

                addLog("[App] Initializing native model...")
                val success = initModelNative(file.absolutePath)

                withContext(Dispatchers.Main) {
                    if (success) {
                        statusText = "Model Ready (${file.length() / 1024 / 1024} MB)"
                        isAiReady = true
                        addLog("[App] PocketMind Engine Initialized Successfully!")
                    } else {
                        statusText = "Failed to initialize model"
                        addLog("[App] ERROR: Native model init failed.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText = "Error: ${e.localizedMessage}"
                    addLog("[App] Exception: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun copyAssetToFile(context: Context, assetName: String): File {
        val file = File(context.filesDir, assetName)
        if (!file.exists()) {
            addLog("[App] Extracting model (first launch may take a few seconds)...")
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            addLog("[App] Model extracted successfully.")
        }
        return file
    }

    fun processCommand(userText: String) {
        if (userText.isBlank() || isGenerating) return

        chatMessages.add(ChatMessage("user", userText))
        isGenerating = true

        viewModelScope.launch(Dispatchers.Default) {
            val assistantIndex = chatMessages.size
            chatMessages.add(ChatMessage("assistant", "Executing..."))

            val responseText = executeUserIntent(userText) { log -> addLog(log) }

            withContext(Dispatchers.Main) {
                chatMessages[assistantIndex] = ChatMessage("assistant", responseText)
                isGenerating = false
            }
        }
    }

    private suspend fun executeUserIntent(userText: String, onLog: (String) -> Unit): String = withContext(Dispatchers.Default) {
        val textLower = userText.lowercase().trim()

        // 1. Очистка всех навыков
        if (textLower == "delete all skills" || textLower == "clear skills") {
            skillsManager.clearAllSkills()
            onLog("[Skill Engine] All skills cleared.")
            return@withContext "Все навыки успешно удалены!"
        }

        // 2. Вывод списка сохраненных навыков
        if (textLower == "list skills" || textLower == "skills") {
            val skills = skillsManager.getAllSkills()
            if (skills.isEmpty()) return@withContext "У вас пока нет сохраненных навыков. Используйте 'learn skill Имя = Команда'."
            return@withContext "Сохраненные навыки:\n" + skills.joinToString("\n") { "- **${it.name}** (${it.actionType}): ${it.description}" }
        }

        // 3. Запуск навыка: use skill <name> или run skill <name>
        if (textLower.startsWith("use skill ") || textLower.startsWith("run skill ")) {
            val parts = userText.substringAfter("skill ").trim().split(" ", limit = 2)
            val skillName = parts[0].trim()
            val paramValue = if (parts.size > 1) parts[1].trim() else ""

            val skills = skillsManager.getAllSkills()
            val skill = skills.find { it.name.equals(skillName, ignoreCase = true) }

            if (skill != null) {
                onLog("[Skill Engine] Direct execution of skill: ${skill.name}")
                return@withContext executeSkillInstance(skill, paramValue, onLog)
            } else {
                return@withContext "Навык '$skillName' не найден. Сначала создайте его через 'learn skill'."
            }
        }

        // 4. Обучение навыку: learn skill <name> = <cmd or url>
        if (textLower.startsWith("learn skill ") || textLower.startsWith("learn:")) {
            try {
                val cleanText = userText.substringAfter("learn skill").substringAfter("learn:").trim()
                val skillName = cleanText.substringBefore("=").trim()
                val rest = cleanText.substringAfter("=").trim()

                val actionType = if (rest.startsWith("http", true) || rest.startsWith("https", true)) "http" else "shell"
                val config = JSONObject()

                if (actionType == "shell") {
                    config.put("cmd", rest)
                } else {
                    config.put("url", rest)
                    config.put("method", "GET")
                }

                val finalName = if (skillName.isBlank()) "custom_skill" else skillName
                skillsManager.addSkill(UserSkill(finalName, "User Learned Skill", actionType, config))
                onLog("[Skill Engine] Hardcoded Learned skill '$finalName' ($actionType)")
                return@withContext "Понял! Запомнил навык '$finalName' типа $actionType."
            } catch (e: Exception) {
                onLog("[Skill Engine Error] Parse failed: ${e.localizedMessage}")
                return@withContext "Ошибка при сохранении навыка: ${e.localizedMessage}"
            }
        }

        // 5. Прямой запуск shell-скрипта
        if (textLower.startsWith("execute script ") || textLower.startsWith("run script ")) {
            val scriptCmd = userText.substringAfter("script").trim()
            onLog("[Script Runner] Executing: $scriptCmd")
            return@withContext executeShellCommand(scriptCmd)
        }

        // --- Если это свободный запрос, отдаем его локальной LLM ---
        val userSkills = skillsManager.getAllSkills()
        val customSkillsDesc = if (userSkills.isEmpty()) "None" else userSkills.joinToString("\n") {
            "- Name: ${it.name} | Type: ${it.actionType}"
        }

        val systemPrompt = "You are PocketMind Assistant. Output RAW JSON ONLY. No markdown.\n" +
                "Available skills: $customSkillsDesc\n" +
                "To execute shell command, output:\n" +
                "{\"action\": \"execute_command\", \"cmd\": \"ls -la\"}"

        val promptBuilder = StringBuilder()
        promptBuilder.append("<|im_start|>system\n$systemPrompt<|im_end|>\n")
        promptBuilder.append("<|im_start|>user\n$userText<|im_end|>\n")
        promptBuilder.append("<|im_start|>assistant\n")

        onLog("[AI] Parsing intent...")
        val rawOutput = withTimeoutOrNull(15000L) {
            generateNative(promptBuilder.toString(), onLog)
        }?.trim() ?: return@withContext "Error: Execution timed out."

        val jsonStr = extractJson(rawOutput)
        if (jsonStr != null) {
            return@withContext executeAndroidTool(jsonStr, onLog)
        }

        return@withContext rawOutput
    }

    private fun executeSkillInstance(skill: UserSkill, param: String, onLog: (String) -> Unit): String {
        return when {
            skill.actionType.contains("http", true) -> {
                var url = skill.config.optString("url")
                if (param.isNotBlank()) {
                    url = url.replace("{text}", param)
                }
                makeHttpRequest(url, skill.config.optString("method", "GET"), "")
            }
            skill.actionType.contains("shell", true) -> {
                var cmd = skill.config.optString("cmd")
                if (param.isNotBlank()) {
                    cmd = cmd.replace("{text}", param)
                }
                executeShellCommand(cmd)
            }
            else -> "Неподдерживаемый тип действия."
        }
    }

    private fun extractJson(output: String): String? {
        val start = output.indexOf("{")
        if (start == -1) return null
        val end = output.lastIndexOf("}")
        return if (end != -1 && end > start) {
            output.substring(start, end + 1)
        } else {
            val trimmed = output.substring(start).trim()
            if (trimmed.endsWith("}")) trimmed else "$trimmed}"
        }
    }

    private fun executeAndroidTool(jsonStr: String, onLog: (String) -> Unit): String {
        var processedJson = jsonStr.trim()
        return try {
            val openBraces = processedJson.count { it == '{' }
            val closeBraces = processedJson.count { it == '}' }
            if (openBraces > closeBraces) {
                processedJson += "}".repeat(openBraces - closeBraces)
            }

            val json = JSONObject(processedJson)
            val action = json.optString("action")

            when (action) {
                "execute_command" -> executeShellCommand(json.optString("cmd"))
                "http_request" -> makeHttpRequest(json.optString("url"), json.optString("method", "GET"), json.optString("body", ""))
                else -> "Command completed."
            }
        } catch (e: Exception) {
            if (!processedJson.contains("learn_skill") && (processedJson.contains("execute_command") || processedJson.contains("cmd"))) {
                val cmdMatch = Regex("\"cmd\"\\s*:\\s*\"([^\"]+)").find(processedJson)
                if (cmdMatch != null) {
                    var recoveredCmd = cmdMatch.groupValues[1].trim()
                    if (recoveredCmd.endsWith("}")) recoveredCmd = recoveredCmd.removeSuffix("}").trim()
                    if (recoveredCmd.endsWith("\"")) recoveredCmd = recoveredCmd.removeSuffix("\"").trim()
                    if (recoveredCmd.count { it == '\'' } % 2 != 0) recoveredCmd += "'"
                    if (recoveredCmd.count { it == '"' } % 2 != 0) recoveredCmd += "\""

                    onLog("[Tool Fallback] Auto-balanced Command: $recoveredCmd")
                    return executeShellCommand(recoveredCmd)
                }
            }
            onLog("[Tool Error] ${e.localizedMessage}")
            "Error: ${e.localizedMessage}"
        }
    }

    private fun makeHttpRequest(urlString: String, method: String, body: String): String {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = method.uppercase()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (method.equals("POST", true) || method.equals("PUT", true)) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(connection.outputStream).use { it.write(body) }
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: "No response body"
            "HTTP $responseCode Response:\n" + response.take(1000)
        } catch (e: Exception) {
            "HTTP Error: ${e.localizedMessage}"
        }
    }

    private fun executeShellCommand(cmd: String): String {
        return try {
            var formattedCmd = cmd
            if (formattedCmd.startsWith("python ", true) || formattedCmd.startsWith("python3 ", true)) {
                val termuxPython = "/data/data/com.termux/files/usr/bin/python"
                if (File(termuxPython).exists()) {
                    formattedCmd = formattedCmd.replaceFirst("python3", termuxPython).replaceFirst("python", termuxPython)
                }
            }

            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", formattedCmd))
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
            if (output.isBlank()) "Executed successfully with empty output." else output.toString().take(1500)
        } catch (e: Exception) {
            "Execution error: ${e.localizedMessage}"
        }
    }

    override fun onCleared() {
        super.onCleared()
        freeModelNative()
    }
}