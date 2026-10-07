package com.example.androidaimanager

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class ChatMessage(val role: String, val text: String)

interface LogListener {
    fun onLog(message: String)
}

class MainActivity : ComponentActivity() {

    private lateinit var skillsManager: UserSkillsManager

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    private external fun loadModelNative(modelPath: String): Boolean
    private external fun generateNative(prompt: String, listener: LogListener): String
    private external fun unloadModelNative()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        skillsManager = UserSkillsManager(this)
        requestStoragePermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AiChatScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unloadModelNative()
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    101
                )
            }
        }
    }

    @Composable
    fun AiChatScreen() {
        var statusText by remember { mutableStateOf("Loading AI model...") }
        var isAiReady by remember { mutableStateOf(false) }
        var isGenerating by remember { mutableStateOf(false) }
        var userInput by remember { mutableStateOf("") }

        val chatMessages = remember { mutableStateListOf<ChatMessage>() }
        val logList = remember { mutableStateListOf<String>() }

        val logListState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()

        fun addLog(msg: String) {
            runOnUiThread {
                logList.add(msg)
                coroutineScope.launch {
                    if (logList.isNotEmpty()) {
                        logListState.animateScrollToItem(logList.size - 1)
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                try {
                    addLog("[App] Preparing system...")
                    val modelName = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
                    val file = copyAssetToFile(this@MainActivity, modelName)

                    val loaded = loadModelNative(file.absolutePath)

                    withContext(Dispatchers.Main) {
                        if (loaded) {
                            statusText = "Model Ready (${file.length() / 1024 / 1024} MB)"
                            isAiReady = true
                            addLog("[App] Autonomous Dynamic AI Agent Ready!")
                        } else {
                            statusText = "Failed to load model"
                            addLog("[App] ERROR: Model load failed.")
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = if (isAiReady) MaterialTheme.colorScheme.primary else Color.Red,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chatMessages) { message ->
                    MessageBubble(message)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = userInput,
                    onValueChange = { userInput = it },
                    placeholder = { Text("Command or teach (e.g. teach: send discord = POST http://webhook...)") },
                    modifier = Modifier.weight(1f),
                    enabled = isAiReady && !isGenerating
                )

                Button(
                    onClick = {
                        val promptText = userInput.trim()
                        if (promptText.isNotEmpty()) {
                            chatMessages.add(ChatMessage("user", promptText))
                            userInput = ""
                            isGenerating = true

                            coroutineScope.launch {
                                val assistantIndex = chatMessages.size
                                chatMessages.add(ChatMessage("assistant", "Executing action..."))

                                val responseText = processUserCommand(promptText) { log -> addLog(log) }

                                chatMessages[assistantIndex] = ChatMessage("assistant", responseText)
                                isGenerating = false
                            }
                        }
                    },
                    enabled = isAiReady && userInput.isNotBlank() && !isGenerating
                ) {
                    Text(if (isGenerating) "..." else "Send")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Android Execution Logs:", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Surface(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxWidth(),
                color = Color.Black,
                shape = RoundedCornerShape(8.dp)
            ) {
                LazyColumn(
                    state = logListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    items(logList) { log ->
                        Text(
                            text = log,
                            color = if (log.contains("ERROR") || log.contains("FAILED") || log.contains("[Err]")) Color.Red else Color.Green,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun MessageBubble(message: ChatMessage) {
        val isUser = message.role == "user"
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    private suspend fun processUserCommand(userText: String, onLog: (String) -> Unit): String = withContext(Dispatchers.Default) {
        val userSkills = skillsManager.getAllSkills()
        val customSkillsDesc = if (userSkills.isEmpty()) "None" else userSkills.joinToString("\n") {
            "- Name: ${it.name} | Description: ${it.description} | Action: ${it.actionType}"
        }

        val systemPrompt = """
            You are PocketMind Autonomous AI Manager. Map user requests to RAW JSON ONLY.

            1. LEARN NEW SKILL (When user asks to remember a new webhook, Discord, API, or command):
               {"action": "learn_skill", "name": "unique_skill_name", "description": "what it does", "type": "http|intent|shell", "config": {"url": "http://...", "method": "POST", "body_template": "{\"content\":\"{text}\"}", "package": "com.discord"}}

            2. EXECUTE KNOWN USER SKILLS:
               Known Skills:
               $customSkillsDesc
               To execute: {"action": "execute_custom_skill", "name": "skill_name", "params": {"text": "extracted_message_or_value"}}

            3. BUILT-IN SYSTEM TOOLS:
               - Read File: {"action": "read_file", "path": "/path"}
               - Write File: {"action": "write_file", "path": "/path", "content": "text"}
               - Delete File: {"action": "delete_file", "path": "/path"}
               - Move File: {"action": "move_file", "from": "/old", "to": "/new"}
               - Copy File: {"action": "copy_file", "from": "/old", "to": "/new"}
               - List Dir: {"action": "list_dir", "path": "/sdcard"}
               - HTTP Request: {"action": "http_request", "method": "GET|POST", "url": "https://...", "body": "{}"}
               - Shell Command: {"action": "execute_command", "cmd": "..."}
               - Open App: {"action": "open_app", "package": "com.package.name"}
               - Share Text to App: {"action": "share_text", "package": "com.discord", "text": "message"}
               - No action needed: {"action": "none"}

            RULES:
            - Output ONLY valid JSON without Markdown blocks.
            - Extract dynamic text into "params" -> "text" when executing custom skills.
        """.trimIndent()

        val promptBuilder = StringBuilder()
        promptBuilder.append("<|im_start|>system\n$systemPrompt<|im_end|>\n")
        promptBuilder.append("<|im_start|>user\n$userText<|im_end|>\n")
        promptBuilder.append("<|im_start|>assistant\n")

        onLog("[AI] Parsing intent...")

        val rawOutput = withTimeoutOrNull(15000L) {
            generateNative(promptBuilder.toString(), object : LogListener {
                override fun onLog(message: String) {
                    onLog(message)
                }
            })
        }?.trim() ?: return@withContext "Error: Execution timed out."

        onLog("[AI] Raw output: $rawOutput")

        if (rawOutput.contains("{") && rawOutput.contains("}")) {
            val jsonStart = rawOutput.indexOf("{")
            val jsonEnd = rawOutput.lastIndexOf("}") + 1
            val jsonString = rawOutput.substring(jsonStart, jsonEnd)

            return@withContext executeAndroidTool(jsonString, onLog)
        }

        return@withContext rawOutput
    }

    private fun executeAndroidTool(jsonStr: String, onLog: (String) -> Unit): String {
        return try {
            val json = JSONObject(jsonStr)
            val action = json.optString("action")

            when (action) {
                // --- ОБУЧЕНИЕ И ДИНАМИЧЕСКИЕ НАВЫКИ ---
                "learn_skill" -> {
                    val name = json.optString("name")
                    val desc = json.optString("description")
                    val type = json.optString("type", "http")
                    val config = json.optJSONObject("config") ?: JSONObject()

                    skillsManager.addSkill(UserSkill(name, desc, type, config))
                    onLog("[Skill Engine] Learned new skill: $name")
                    "Понял! Запомнил навык '$name' ($desc)."
                }
                "execute_custom_skill" -> {
                    val skillName = json.optString("name")
                    val params = json.optJSONObject("params") ?: JSONObject()
                    val userTextParam = params.optString("text", "")

                    val skill = skillsManager.getAllSkills().find { it.name == skillName }
                    if (skill != null) {
                        onLog("[Skill Engine] Executing skill: ${skill.name}")
                        when (skill.actionType) {
                            "http" -> {
                                val url = skill.config.optString("url")
                                val method = skill.config.optString("method", "POST")
                                val template = skill.config.optString("body_template", "{\"content\":\"{text}\"}")
                                val finalBody = template.replace("{text}", userTextParam)

                                makeHttpRequest(url, method, finalBody)
                            }
                            "intent" -> {
                                val pkg = skill.config.optString("package")
                                shareTextToApp(pkg, userTextParam)
                            }
                            "shell" -> {
                                val cmd = skill.config.optString("cmd").replace("{text}", userTextParam)
                                executeShellCommand(cmd)
                            }
                            else -> "Unknown skill type"
                        }
                    } else {
                        "Навык '$skillName' не найден."
                    }
                }

                // --- СИСТЕМНЫЕ ФУНКЦИИ ---
                "read_file" -> readFileContent(json.optString("path"))
                "write_file" -> writeFileContent(json.optString("path"), json.optString("content"))
                "delete_file" -> deleteFileOrFolder(json.optString("path"))
                "move_file" -> moveOrRenameFile(json.optString("from"), json.optString("to"))
                "copy_file" -> copyFile(json.optString("from"), json.optString("to"))
                "list_dir" -> listDirectory(json.optString("path"))
                "http_request" -> makeHttpRequest(json.optString("url"), json.optString("method", "GET"), json.optString("body", ""))
                "execute_command" -> executeShellCommand(json.optString("cmd"))
                "open_app" -> launchAppByPackage(json.optString("package"))
                "share_text" -> shareTextToApp(json.optString("package"), json.optString("text"))
                "get_system_info" -> "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), Model: ${Build.MODEL}"

                else -> "Command completed."
            }
        } catch (e: Exception) {
            onLog("[OS Tool Error] ${e.localizedMessage}")
            "Error: ${e.localizedMessage}"
        }
    }

    // --- ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ---

    private fun readFileContent(path: String): String {
        val file = File(path)
        if (!file.exists()) return "File not found at $path"
        return file.readText().take(2000)
    }

    private fun writeFileContent(path: String, content: String): String {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "File written: $path"
    }

    private fun deleteFileOrFolder(path: String): String {
        val file = File(path)
        if (!file.exists()) return "Path does not exist: $path"
        return if (file.deleteRecursively()) "Deleted: $path" else "Failed to delete."
    }

    private fun moveOrRenameFile(fromPath: String, toPath: String): String {
        val src = File(fromPath)
        val dst = File(toPath)
        if (!src.exists()) return "Source not found: $fromPath"
        dst.parentFile?.mkdirs()
        return if (src.renameTo(dst)) "Moved to: $toPath" else "Failed to move."
    }

    private fun copyFile(fromPath: String, toPath: String): String {
        val src = File(fromPath)
        val dst = File(toPath)
        if (!src.exists()) return "Source does not exist: $fromPath"
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        return "Copied to: $toPath"
    }

    private fun listDirectory(path: String): String {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return "Invalid directory: $path"
        val files = dir.listFiles() ?: return "Directory is empty."
        return files.take(30).joinToString("\n") {
            if (it.isDirectory) "[DIR] ${it.name}" else "[FILE] ${it.name} (${it.length() / 1024} KB)"
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
            "HTTP Request error: ${e.localizedMessage}"
        }
    }

    private fun shareTextToApp(packageName: String, text: String): String {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (packageName.isNotBlank()) setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            "Opened $packageName with pre-filled text."
        } catch (e: Exception) {
            "Failed to send text to app: ${e.localizedMessage}"
        }
    }

    private fun launchAppByPackage(packageName: String): String {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            startActivity(intent)
            "Launched app: $packageName"
        } else {
            "Package $packageName not found."
        }
    }

    private fun executeShellCommand(cmd: String): String {
        return try {
            val formattedCmd = if (cmd.startsWith("ping ") && !cmd.contains("-c")) {
                cmd.replace("ping ", "ping -c 4 ")
            } else {
                cmd
            }

            val process = Runtime.getRuntime().exec(formattedCmd)
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
            if (output.isBlank()) "Command executed with empty output." else output.toString().take(1000)
        } catch (e: Exception) {
            "Shell execution error: ${e.localizedMessage}"
        }
    }

    private fun copyAssetToFile(context: Context, assetName: String): File {
        val file = File(context.filesDir, assetName)
        if (!file.exists()) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file
    }
}