package com.example.androidaimanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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

class MainActivity : ComponentActivity() {

    private val viewModel: AiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AiChatScreen(viewModel)
                }
            }
        }
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            }
        }
    }

    private fun checkNotificationPermission() {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (enabledListeners == null || !enabledListeners.contains(packageName)) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }
    }
}

@Composable
fun AiChatScreen(viewModel: AiViewModel) {
    var userInput by remember { mutableStateOf("") }
    val logListState = rememberLazyListState()

    // Подписываемся на StateFlow через collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val logList by viewModel.logList.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val isAiReady by viewModel.isAiReady.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()

    LaunchedEffect(logList.size) {
        if (logList.isNotEmpty()) {
            logListState.animateScrollToItem(logList.size - 1)
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
                placeholder = { Text("learn skill, run python, read files...") },
                modifier = Modifier.weight(1f),
                enabled = isAiReady && !isGenerating
            )

            Button(
                onClick = {
                    val text = userInput.trim()
                    if (text.isNotEmpty()) {
                        userInput = ""
                        viewModel.processCommand(text)
                    }
                },
                enabled = isAiReady && userInput.isNotBlank() && !isGenerating
            ) {
                Text(if (isGenerating) "..." else "Send")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text("Execution Logs:", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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
    val isUser = message.role.equals("user", ignoreCase = true)
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