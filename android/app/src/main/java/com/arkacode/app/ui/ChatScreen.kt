package com.arkacode.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkacode.app.net.ChatItem
import com.arkacode.app.net.ModelRef
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.PermissionReq
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Layar chat F1: pesan dari server (poll 2 dtk saat sibuk), kirim async,
 * abort, approval persetujuan, salin pesan, picker model per sesi.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    sessionTitle: String,
    port: Int,
    password: String,
    initialProviderID: String?,
    initialModelID: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(port, password) { OpenCodeClient(port = port, password = password) }
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf(listOf<ChatItem>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(listOf<PermissionReq>()) }
    var pinnedModel by remember { mutableStateOf<ModelRef?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    var waitJob by remember { mutableStateOf<Job?>(null) }
    var showFilesTab by remember { mutableStateOf(false) }

    suspend fun refreshAll() {
        messages = runCatching { client.listMessages(sessionId) }.getOrDefault(messages)
        pending = runCatching { client.listPermissions(sessionId) }.getOrDefault(pending)
    }

    fun stopAll() {
        pollJob?.cancel()
        waitJob?.cancel()
        scope.launch {
            client.interrupt(sessionId)
            refreshAll()
            busy = false
        }
        busy = false
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy) return
        error = null
        busy = true
        input = ""
        pollJob?.cancel()
        waitJob?.cancel()
        pollJob = scope.launch {
            try {
                pinnedModel?.let { m ->
                    runCatching { client.switchModel(sessionId, m.providerID, m.id) }
                }
                client.sendPrompt(sessionId, trimmed)
                while (true) {
                    messages = client.listMessages(sessionId)
                    pending = client.listPermissions(sessionId)
                    delay(2000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "gagal mengirim"
                busy = false
            }
        }
        waitJob = scope.launch {
            try {
                client.waitIdle(sessionId)
                pollJob?.cancel()
                refreshAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // dibiarkan: poll loop yang menampilkan error/progress
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(sessionId) {
        refreshAll()
        val pin = if (initialProviderID != null && initialModelID != null) {
            ModelRef(initialProviderID, initialModelID)
        } else null
        pinnedModel = pin
    }
    LaunchedEffect(messages.size) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.scrollToItem(count - 1)
    }
    LaunchedEffect(showFilesTab) {
        if (!showFilesTab) {
            messages = runCatching { client.listMessages(sessionId) }.getOrDefault(messages)
        }
    }
    // Event server sebagai trigger refresh live; polling 2 dtk tetap jaring pengaman.
    LaunchedEffect(busy, sessionId) {
        if (!busy) return@LaunchedEffect
        try {
            client.serverEvents().collect {
                messages = runCatching { client.listMessages(sessionId) }.getOrDefault(messages)
                pending = runCatching { client.listPermissions(sessionId) }.getOrDefault(pending)
            }
        } catch (e: Exception) {
        }
    }

    Scaffold(
        topBar = {
            ChatBar(
                title = sessionTitle.ifBlank { "Sesi" },
                modelLabel = pinnedModel?.let { "${it.providerID}/${it.id}" } ?: "Model server",
                busy = busy,
                onBack = onBack,
                onOpenPicker = { showPicker = true },
                onStop = { stopAll() },
            )
        },
        bottomBar = {
            if (!showFilesTab) {
                ChatInput(
                input = input,
                onInputChange = { input = it },
                busy = busy,
                onSend = { send(input) },
                onStop = { stopAll() },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            error?.let {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { error = null }) { Text("Tutup") }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = !showFilesTab,
                    onClick = { showFilesTab = false },
                    label = { Text("Chat") },
                )
                FilterChip(
                    selected = showFilesTab,
                    onClick = { showFilesTab = true },
                    label = { Text("Files") },
                )
            }
            if (showFilesTab) {
                FilesPane(port = port, password = password, modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = {
                    when (it) {
                        is ChatItem.User -> "u-" + it.id
                        is ChatItem.Assistant -> "a-" + it.id
                        is ChatItem.Note -> "n-" + it.id
                    }
                }) { item ->
                    when (item) {
                        is ChatItem.User -> UserBubble(item)
                        is ChatItem.Assistant -> AssistantBubble(item)
                        is ChatItem.Note -> NoteRow(item)
                    }
                }
                if (busy) {
                    item("typing") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(8.dp))
                            Text("bekerja…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            }
        }
    }

    if (pending.isNotEmpty()) {
        ApprovalSheet(
            pending = pending,
            onReply = { req, reply ->
                scope.launch {
                    runCatching { client.replyPermission(sessionId, req.id, reply) }
                    pending = runCatching { client.listPermissions(sessionId) }.getOrDefault(pending)
                }
            },
            onDismiss = { /* tetap tampil selama masih pending */ },
        )
    }

    if (showPicker) {
        ModelPickerSheet(
            client = client,
            currentProviderID = pinnedModel?.providerID,
            currentModelID = pinnedModel?.id,
            onSelect = { providerID, modelID ->
                pinnedModel = ModelRef(providerID, modelID)
                scope.launch { runCatching { client.switchModel(sessionId, providerID, modelID) } }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ChatBar(
    title: String,
    modelLabel: String,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenPicker: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                TextButton(onClick = onOpenPicker) {
                    Text(
                        modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            if (busy) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            }
        }
    }
}

@Composable
private fun ChatInput(
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text("Tulis pesan…") },
            singleLine = false,
            minLines = 1,
            maxLines = 5,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (busy) {
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "Stop")
            }
        } else {
            IconButton(onClick = onSend, enabled = input.isNotBlank()) {
                Icon(Icons.Default.Send, contentDescription = "Kirim")
            }
        }
    }
}

@Composable
private fun UserBubble(item: ChatItem.User) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)),
        ) {
            Column(Modifier.padding(12.dp, 8.dp)) {
                Text(item.text, style = MaterialTheme.typography.bodyMedium)
                if (item.fileCount > 0) {
                    Text(
                        "${item.fileCount} lampiran",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(item: ChatItem.Assistant) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
            modifier = Modifier.fillMaxWidth(0.95f),
        ) {
            Column(Modifier.padding(12.dp, 8.dp)) {
                item.texts.forEach { t ->
                    MarkdownText(
                        markdown = t,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (item.reasonings.isNotEmpty()) {
                    var showThinking by remember { mutableStateOf(false) }
                    TextButton(onClick = { showThinking = !showThinking }) {
                        Text(
                            if (showThinking) "Sembunyikan pemikiran" else "Lihat pemikiran (${item.reasonings.size})",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    AnimatedVisibility(visible = showThinking) {
                        Column {
                            item.reasonings.forEach { r ->
                                Text(
                                    r,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                item.tools.forEach { tool ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tool.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                tool.status.ifBlank { "…" },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("chat", item.texts.joinToString("\n\n")))
                        copied = true
                    }) {
                        Text(
                            if (copied) "Disalin ✓" else "Salin",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(item: ChatItem.Note) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            item.text.take(300),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(
    pending: List<PermissionReq>,
    onReply: (PermissionReq, String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Persetujuan tool (${pending.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            pending.take(5).forEach { req ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            req.summary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onReply(req, "reject") }) {
                                Text("Tolak", color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(onClick = { onReply(req, "once") }) { Text("Izinkan") }
                            TextButton(onClick = { onReply(req, "always") }) { Text("Selalu") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerSheet(
    client: OpenCodeClient,
    currentProviderID: String?,
    currentModelID: String?,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModelPickerContent(client, currentProviderID, currentModelID, onSelect, onDismiss)
}
