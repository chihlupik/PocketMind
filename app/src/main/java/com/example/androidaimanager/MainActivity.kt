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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    101
                )
            }
        }
    }
}

@Composable
fun AiChatScreen(viewModel: AiViewModel) {
    var userInput by rememberSaveable { mutableStateOf("") }
    var showLogs by rememberSaveable { mutableStateOf(true) }

    val chatListState = rememberLazyListState()
    val logListState = rememberLazyListState()

    val chatMessages by viewModel.chatMessages.collectAsState()
    val logList by viewModel.logList.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val isAiReady by viewModel.isAiReady.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()

    var fullScreenText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            chatListState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    LaunchedEffect(logList.size) {
        if (logList.isNotEmpty() && showLogs) {
            logListState.animateScrollToItem(logList.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = if (isAiReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            IconButton(
                onClick = { showLogs = !showLogs },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = "Toggle Logs",
                    tint = if (showLogs) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }

        LazyColumn(
            state = chatListState,
            modifier = Modifier
                .weight(if (showLogs) 0.6f else 1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = chatMessages,
                key = { msg -> msg.id }
            ) { message ->
                MessageBubble(
                    message = message,
                    onExpand = { fullScreenText = message.text }
                )
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

            if (isGenerating) {
                Button(onClick = { viewModel.stopGeneration() }) {
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = {
                        val text = userInput.trim()
                        if (text.isNotEmpty()) {
                            userInput = ""
                            viewModel.processCommand(text)
                        }
                    },
                    enabled = isAiReady && userInput.isNotBlank()
                ) {
                    Text("Send")
                }
            }
        }

        if (showLogs) {
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
                            color = when {
                                log.contains("ERROR") || log.contains("FAILED") || log.contains("[Err]") -> Color.Red
                                log.contains("[Perf]") -> Color.Cyan
                                else -> Color.Green
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    if (fullScreenText != null) {
        val clipboardManager = LocalClipboardManager.current

        Dialog(
            onDismissRequest = { fullScreenText = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Terminal Output",
                            color = Color.Gray,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row {
                            TextButton(onClick = {
                                clipboardManager.setText(AnnotatedString(fullScreenText ?: ""))
                            }) {
                                Text("Copy All", color = Color.Green)
                            }
                            TextButton(onClick = { fullScreenText = null }) {
                                Text("Close", color = Color.Red)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fullScreenText ?: "",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp, // Уменьшенный шрифт, как в Termux
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, onExpand: () -> Unit) {
    val isUser = message.role == ChatRole.USER

    val cleanText = message.text.replace("```shell", "").replace("```", "").trim()
    val lineCount = cleanText.lines().size
    val charCount = cleanText.length

    val isExecuting = message.text.startsWith("Executing:") || message.text.startsWith("Развернуть")
    val showExpandButton = !isExecuting && (lineCount > 8 || charCount > 200)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (showExpandButton) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = true
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = onExpand,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("Развернуть полностью", fontSize = 12.sp)
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            softWrap = true
                        )
                    }
                }
            }
        }
    }
}