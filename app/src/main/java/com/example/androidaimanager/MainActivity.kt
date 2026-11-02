package com.example.androidaimanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
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
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private val viewModel: AiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AiChatScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun getLogColor(log: String): Color {
    return when {
        log.startsWith("[Err]") || log.contains("FATAL") || log.contains("Error", ignoreCase = true) -> Color.Red
        log.startsWith("[Perf]") -> Color.Cyan
        log.startsWith("[App]") || log.startsWith("[UI]") -> Color.White
        log.startsWith("[User]") -> Color(0xFF81C784)
        log.startsWith("[AI]") || log.startsWith("[AI Response]") -> Color(0xFF64B5F6)
        log.startsWith("[Shell]") || log.startsWith("[Direct Exec]") -> Color(0xFFFFB74D)
        log.startsWith("[Security]") -> Color.Yellow
        log.startsWith("[Skills]") -> Color(0xFFCE93D8)
        else -> Color.Green
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiChatScreen(viewModel: AiViewModel) {
    var userInput by rememberSaveable { mutableStateOf("") }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showFullScreenLogs by remember { mutableStateOf(false) }
    var fullScreenText by remember { mutableStateOf<String?>(null) }

    val chatListState = rememberLazyListState()
    val logListState = rememberLazyListState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val logList by viewModel.logList.collectAsState()
    val isAiReady by viewModel.isAiReady.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val allChats by viewModel.allChats.collectAsState()
    val pendingCommand by viewModel.pendingCommand.collectAsState()
    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    val isShizukuReady by viewModel.isShizukuReady.collectAsState()

    val isImeVisible = WindowInsets.isImeVisible

    // --- УМНАЯ ПРОКРУТКА ДЛЯ МАЛЕНЬКОГО ОКНА ЛОГОВ ---
    var wasAtBottomSmall by remember { mutableStateOf(true) }
    LaunchedEffect(logListState.layoutInfo) {
        if (!logListState.isScrollInProgress) {
            val lastVisible = logListState.layoutInfo.visibleItemsInfo.lastOrNull()
            wasAtBottomSmall = lastVisible == null || lastVisible.index >= logList.size - 2
        }
    }
    LaunchedEffect(logList.size) {
        if (wasAtBottomSmall && showLogs) {
            logListState.animateScrollToItem(logList.size - 1)
        }
    }

    // Автоскролл чата вниз при новых сообщениях
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) chatListState.animateScrollToItem(chatMessages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {

        // 1. ВЕРХНЯЯ ПАНЕЛЬ
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isAiReady) "Model" else "Model (Init)", style = MaterialTheme.typography.labelMedium, color = if (isAiReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text("Root", style = MaterialTheme.typography.labelMedium, color = if (isRootAvailable) Color(0xFF4CAF50) else Color.Gray)
                Text("Shizuku", style = MaterialTheme.typography.labelMedium, color = if (isShizukuReady) Color(0xFF2196F3) else Color.Gray)
            }
            Row {
                IconButton(onClick = { showHistory = true }) { Icon(Icons.Default.Menu, contentDescription = "History") }
                IconButton(onClick = { showLogs = !showLogs }) { Icon(Icons.Default.List, contentDescription = "Logs", tint = if (showLogs) MaterialTheme.colorScheme.primary else Color.Gray) }
                IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete Chat", tint = Color.Red) }
            }
        }

        // 2. ЧАТ (С ИСПРАВЛЕННЫМИ ОТСТУПАМИ)
        LazyColumn(
            state = chatListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(chatMessages, key = { it.id }) { msg ->
                MessageBubble(message = msg, onExpand = { fullScreenText = it })
            }
        }

        // 3. ПОЛЕ ВВОДА И КНОПКА STOP/SEND
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = userInput, onValueChange = { userInput = it },
                placeholder = { Text("Type a command or question...") },
                modifier = Modifier.weight(1f), enabled = isAiReady && !isGenerating
            )
            Spacer(Modifier.width(8.dp))

            if (isGenerating) {
                Button(
                    onClick = { viewModel.stopGeneration() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = { viewModel.processCommand(userInput.trim()); userInput = "" },
                    enabled = isAiReady && userInput.isNotBlank()
                ) {
                    Text("Send")
                }
            }
        }

        // 4. МАЛЕНЬКОЕ ОКНО ЛОГОВ
        if (showLogs && !isImeVisible) {
            Surface(
                modifier = Modifier
                    .height(160.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 8.dp)
                    .navigationBarsPadding()
                    .clickable { showFullScreenLogs = true },
                color = Color.Black,
                shape = RoundedCornerShape(8.dp)
            ) {
                if (logList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No logs yet", color = Color.Gray, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(state = logListState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        items(logList) { log ->
                            Text(text = log, color = getLogColor(log), fontSize = 11.sp, fontFamily = FontFamily.Monospace, softWrap = true)
                        }
                    }
                }
            }
        } else if (!showLogs) {
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    // --- ПОЛНОЭКРАННЫЙ ПРОСМОТР ЛОГОВ ---
    if (showFullScreenLogs) {
        val clipboardManager = LocalClipboardManager.current
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()

        var wasAtBottomFull by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            // ИСПОЛЬЗУЕМ ПРАВИЛЬНЫЙ snapshotFlow ИЗ КОМПОУЗА
            snapshotFlow { vScroll.maxValue }.first { it > 0 }
            vScroll.scrollTo(vScroll.maxValue)
            wasAtBottomFull = true
        }

        LaunchedEffect(vScroll.value) {
            if (!vScroll.isScrollInProgress) {
                wasAtBottomFull = vScroll.value >= vScroll.maxValue - 100
            }
        }
        LaunchedEffect(logList.size) {
            if (wasAtBottomFull) {
                vScroll.animateScrollTo(vScroll.maxValue)
            }
        }

        Dialog(onDismissRequest = { showFullScreenLogs = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp).statusBarsPadding()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("System Logs", color = Color.Gray, style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = { clipboardManager.setText(AnnotatedString(logList.joinToString("\n"))) }) {
                                Text("Copy All", color = Color.Green)
                            }
                            TextButton(onClick = { viewModel.clearLogs() }) {
                                Text("Clear", color = Color.Yellow)
                            }
                            TextButton(onClick = { showFullScreenLogs = false }) {
                                Text("Close", color = Color.Red)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) {
                            logList.forEach { log ->
                                Text(
                                    text = log,
                                    color = getLogColor(log),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    softWrap = false,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- ПОЛНОЭКРАННЫЙ ПРОСМОТР СООБЩЕНИЯ ---
    if (fullScreenText != null) {
        val clipboardManager = LocalClipboardManager.current
        Dialog(onDismissRequest = { fullScreenText = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp).statusBarsPadding()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Message Output", color = Color.Gray, style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = { clipboardManager.setText(AnnotatedString(fullScreenText ?: "")) }) {
                                Text("Copy", color = Color.Green)
                            }
                            TextButton(onClick = { fullScreenText = null }) {
                                Text("Close", color = Color.Red)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fullScreenText ?: "", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp, softWrap = false,
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }

    // --- ОСТАЛЬНЫЕ ДИАЛОГИ ---
    if (pendingCommand != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelCommand() },
            title = { Text("Security Approval") },
            text = { Text("AI wants to execute the following command:\n\n${pendingCommand}") },
            confirmButton = { TextButton(onClick = { viewModel.confirmCommand() }) { Text("Allow", color = Color.Green) } },
            dismissButton = { TextButton(onClick = { viewModel.cancelCommand() }) { Text("Deny", color = Color.Red) } }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Chat") },
            text = { Text("Are you sure you want to permanently delete this chat history?") },
            confirmButton = { TextButton(onClick = { viewModel.deleteCurrentChat(); showDeleteConfirm = false }) { Text("Delete", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showHistory) {
        var isSelectionMode by remember { mutableStateOf(false) }
        var selectedChatIds by remember { mutableStateOf(setOf<String>()) }

        LaunchedEffect(showHistory) {
            if (!showHistory) { isSelectionMode = false; selectedChatIds = emptySet() }
        }

        Dialog(onDismissRequest = { showHistory = false }) {
            Surface(modifier = Modifier.fillMaxSize(0.95f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isSelectionMode) "Select to Delete" else "Chat History", style = MaterialTheme.typography.titleLarge)
                        Row {
                            if (isSelectionMode) {
                                IconButton(onClick = { isSelectionMode = false; selectedChatIds = emptySet() }) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                            } else {
                                IconButton(onClick = { viewModel.startNewChat(); showHistory = false }) { Icon(Icons.Default.Add, contentDescription = "New Chat", tint = MaterialTheme.colorScheme.primary) }
                                IconButton(onClick = { isSelectionMode = true }) { Icon(Icons.Default.Delete, contentDescription = "Select", tint = Color.Red) }
                                IconButton(onClick = { showHistory = false }) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray) }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(allChats) { chat ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (isSelectionMode) {
                                        selectedChatIds = if (chat.id in selectedChatIds) selectedChatIds - chat.id else selectedChatIds + chat.id
                                    } else {
                                        viewModel.loadChat(chat.id); showHistory = false
                                    }
                                }.padding(vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelectionMode) {
                                    Checkbox(
                                        checked = chat.id in selectedChatIds,
                                        onCheckedChange = { checked ->
                                            selectedChatIds = if (checked) selectedChatIds + chat.id else selectedChatIds - chat.id
                                        }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(text = chat.title, style = MaterialTheme.typography.bodyLarge)
                            }
                            Divider(color = Color.LightGray)
                        }
                    }

                    if (isSelectionMode) {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Button(onClick = { viewModel.deleteAllChats(); showHistory = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Delete All") }
                            Button(onClick = { viewModel.deleteSelectedChats(selectedChatIds.toList()); isSelectionMode = false; selectedChatIds = emptySet() }, enabled = selectedChatIds.isNotEmpty()) { Text("Delete (${selectedChatIds.size})") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, onExpand: (String) -> Unit) {
    val isUser = message.role == ChatRole.USER
    val cleanText = message.text.replace("```shell", "").replace("```", "").trim()
    val lineCount = cleanText.lines().size
    val charCount = cleanText.length
    val showExpandButton = lineCount > 6 || charCount > 150 || message.text.contains("Exception")

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (showExpandButton) {
                    SelectionContainer {
                        Text(text = message.text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = { onExpand(message.text) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                        Text("Expand", fontSize = 12.sp)
                    }
                } else {
                    Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}