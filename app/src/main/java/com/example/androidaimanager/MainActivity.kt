package com.example.androidaimanager

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val role: String,
    val text: String
)

class MainActivity : ComponentActivity() {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    private external fun loadModelNative(modelPath: String): Boolean
    private external fun generateNative(prompt: String): String
    private external fun unloadModelNative()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    @Composable
    fun AiChatScreen() {
        var statusText by remember { mutableStateOf("Loading AI model...") }
        var isAiReady by remember { mutableStateOf(false) }
        var isGenerating by remember { mutableStateOf(false) }
        var userInput by remember { mutableStateOf("") }

        val chatMessages = remember { mutableStateListOf<ChatMessage>() }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                try {
                    val modelName = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
                    val file = copyAssetToFile(this@MainActivity, modelName)

                    val loaded = loadModelNative(file.absolutePath)

                    withContext(Dispatchers.Main) {
                        if (loaded) {
                            statusText = "Model Ready (${file.length() / 1024 / 1024} MB)"
                            isAiReady = true
                        } else {
                            statusText = "Failed to load model"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusText = "Error: ${e.localizedMessage}"
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Debug Status Header
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = if (isAiReady) MaterialTheme.colorScheme.primary else Color.Red,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Chat Messages List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chatMessages) { message ->
                    MessageBubble(message)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // User Input Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = userInput,
                    onValueChange = { userInput = it },
                    placeholder = { Text("Ask something in English...") },
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
                                chatMessages.add(ChatMessage("assistant", "Thinking..."))

                                val responseText = generateResponse(chatMessages.toList())
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
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    private suspend fun generateResponse(history: List<ChatMessage>): String = withContext(Dispatchers.Default) {
        // Оптимальный системный промпт для 0.5B модели на английском
        val systemInstruction = "You are a concise, helpful AI assistant. Always respond clearly and strictly in English."

        val fullPrompt = StringBuilder()
        fullPrompt.append("<|im_start|>system\n$systemInstruction<|im_end|>\n")

        for (msg in history.takeLast(6)) {
            if (msg.role != "system" && msg.text != "Thinking...") {
                fullPrompt.append("<|im_start|>${msg.role}\n${msg.text}<|im_end|>\n")
            }
        }
        fullPrompt.append("<|im_start|>assistant\n")

        return@withContext generateNative(fullPrompt.toString())
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