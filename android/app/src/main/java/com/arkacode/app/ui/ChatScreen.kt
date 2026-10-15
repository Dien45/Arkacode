package com.arkacode.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.arkacode.app.net.AgentInfo
import com.arkacode.app.net.ChatItem
import com.arkacode.app.net.CommandInfo
import com.arkacode.app.net.ModelRef
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.PermissionReq
import com.arkacode.app.net.RevertState
import com.arkacode.app.net.ServerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Layar chat F1+: pesan dari server (poll 2 dtk saat sibuk), kirim async,
 * abort, approval persetujuan, salin pesan, picker model per sesi, pemilih
 * agent, perintah (slash), ringkas (compact), dan revert/diff berkas.
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
    var agents by remember { mutableStateOf(listOf<AgentInfo>()) }
    var agentId by remember { mutableStateOf<String?>(null) }
    var commands by remember { mutableStateOf(listOf<CommandInfo>()) }
    var showAgents by remember { mutableStateOf(false) }
    var showCommands by remember { mutableStateOf(false) }
    var revert by remember { mutableStateOf<RevertState?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var liveText by remember { mutableStateOf("") }
    var liveReasoning by remember { mutableStateOf("") }
    var toolLabel by remember { mutableStateOf<String?>(null) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var justSent by remember { mutableStateOf<String?>(null) }

    suspend fun refreshAll() {
        messages = runCatching { client.listMessages(sessionId) }.getOrDefault(messages)
        pending = runCatching { client.listPermissions(sessionId) }.getOrDefault(pending)
    }

    fun scheduleRefresh(delayMs: Long = 350L) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(delayMs)
            refreshAll()
        }
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
        liveText = ""
        liveReasoning = ""
        toolLabel = null
        justSent = null
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy) return
        error = null
        busy = true
        input = ""
        justSent = trimmed
        liveText = ""
        liveReasoning = ""
        toolLabel = null
        pollJob?.cancel()
        waitJob?.cancel()
        val preCount = messages.size
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
                error = e.message ?: I18n.s.sendFail
                busy = false
            }
        }
        waitJob = scope.launch {
            try {
                // Kalau `wait` balik sebelum turn benar-benar mulai (race),
                // ulangi sampai ada pesan baru — jangan berhenti menonton dini.
                var attempts = 0
                while (attempts < 3) {
                    client.waitIdle(sessionId)
                    val grew = messages.size > preCount ||
                        messages.any { it is ChatItem.User && it.text == trimmed }
                    if (grew) break
                    attempts++
                    delay(800)
                }
                pollJob?.cancel()
                refreshAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // dibiarkan: poll loop yang menampilkan error/progress
            } finally {
                pollJob?.cancel()
                busy = false
                justSent = null
            }
        }
    }

    fun openRevert(messageId: String) {
        scope.launch {
            revert = runCatching { client.stageRevert(sessionId, messageId) }.getOrNull()
                ?: RevertState(messageId, emptyList())
        }
    }

    LaunchedEffect(sessionId) {
        refreshAll()
        val pin = if (initialProviderID != null && initialModelID != null) {
            ModelRef(initialProviderID, initialModelID)
        } else null
        pinnedModel = pin
        runCatching { client.listAgents() }.getOrNull()?.let { agents = it.filter { a -> !a.hidden } }
        runCatching { client.listCommands() }.getOrNull()?.let { commands = it }
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
    // Event server (SSE semantik) untuk update live; polling 2 dtk tetap jaring pengaman.
    LaunchedEffect(busy, sessionId) {
        if (!busy) return@LaunchedEffect
        try {
            client.serverEvents().collect { ev ->
                if (ev.sessionID != null && ev.sessionID != sessionId) return@collect
                when (ev) {
                    is ServerEvent.Connected -> {}
                    is ServerEvent.Status -> when (ev.status) {
                        "idle" -> {
                            busy = false
                            liveText = ""
                            liveReasoning = ""
                            toolLabel = null
                            refreshAll()
                        }
                        "retry" -> error = ev.message
                        else -> {}
                    }
                    is ServerEvent.TextDelta -> {
                        liveText += ev.delta
                        scheduleRefresh(600L)
                    }
                    is ServerEvent.TextEnded -> {
                        if (ev.text.isNotBlank()) liveText = ev.text
                    }
                    is ServerEvent.ReasoningDelta -> liveReasoning += ev.delta
                    is ServerEvent.Tool -> {
                        if (ev.label.isNotBlank()) toolLabel = ev.label
                        scheduleRefresh(400L)
                    }
                    is ServerEvent.Permission -> {
                        pending = runCatching { client.listPermissions(sessionId) }.getOrDefault(pending)
                    }
                    is ServerEvent.Error -> error = ev.message
                    is ServerEvent.Other -> scheduleRefresh(400L)
                }
            }
        } catch (e: Exception) {
        }
    }

    val lastUser = messages.filterIsInstance<ChatItem.User>().lastOrNull()

    Scaffold(
        topBar = {
            ChatBar(
                title = sessionTitle.ifBlank { I18n.s.titleFallback },
                modelLabel = pinnedModel?.let { "${it.providerID}/${it.id}" } ?: I18n.s.modelServer,
                agentLabel = agentId ?: I18n.s.agentFallback,
                busy = busy,
                menuOpen = menuOpen,
                canRevert = lastUser != null,
                onBack = onBack,
                onOpenPicker = { showPicker = true },
                onStop = { stopAll() },
                onToggleMenu = { menuOpen = !menuOpen },
                onOpenAgents = { menuOpen = false; showAgents = true },
                onOpenCommands = { menuOpen = false; showCommands = true },
                onCompact = {
                    menuOpen = false
                    scope.launch {
                        error = null
                        runCatching { client.compactSession(sessionId) }
                            .onFailure { error = it.message ?: I18n.s.compactFail }
                        refreshAll()
                    }
                },
                onRevert = {
                    menuOpen = false
                    lastUser?.let { openRevert(it.id) }
                },
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
                    TextButton(onClick = { error = null }) { Text(I18n.s.close) }
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
                    label = { Text(I18n.s.chatTab) },
                )
                FilterChip(
                    selected = showFilesTab,
                    onClick = { showFilesTab = true },
                    label = { Text(I18n.s.filesTab) },
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
                        is ChatItem.User -> UserBubble(item, onRevert = { openRevert(item.id) })
                        is ChatItem.Assistant -> AssistantBubble(item)
                        is ChatItem.Note -> NoteRow(item)
                    }
                }
                val echo = justSent
                if (busy && echo != null && messages.none { it is ChatItem.User && it.text == echo }) {
                    item("echo") {
                        UserBubble(ChatItem.User("__echo__", echo, 0), onRevert = {})
                    }
                }
                if (busy) {
                    item("typing") {
                        val lastAssistant = messages.filterIsInstance<ChatItem.Assistant>().lastOrNull()
                        val shownByMessages = liveText.isNotBlank() &&
                            lastAssistant?.texts?.joinToString("\n")?.trimEnd()?.endsWith(liveText.trim()) == true
                        if (liveText.isNotBlank() && !shownByMessages) {
                            AssistantBubble(
                                ChatItem.Assistant(
                                    id = "__live__",
                                    texts = listOf(liveText),
                                    reasonings = listOf(liveReasoning).filter { it.isNotBlank() },
                                    tools = emptyList(),
                                ),
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.padding(8.dp))
                                Text(
                                    toolLabel?.let { I18n.s.runningTool(it) } ?: I18n.s.working,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
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

    if (showAgents) {
        AgentSheet(
            agents = agents,
            current = agentId,
            onSelect = { id ->
                agentId = id
                scope.launch { runCatching { client.switchAgent(sessionId, id) } }
                showAgents = false
            },
            onDismiss = { showAgents = false },
        )
    }

    if (showCommands) {
        CommandSheet(
            commands = commands,
            onPick = { name ->
                input = "/$name "
                showCommands = false
            },
            onDismiss = { showCommands = false },
        )
    }

    revert?.let { state ->
        RevertSheet(
            state = state,
            onApply = {
                scope.launch {
                    runCatching { client.commitRevert(sessionId) }
                        .onFailure { error = it.message ?: I18n.s.revertFail }
                    revert = null
                    refreshAll()
                }
            },
            onCancel = {
                scope.launch {
                    runCatching { client.clearRevert(sessionId) }
                    revert = null
                }
            },
            onDismiss = { revert = null },
        )
    }
}

@Composable
private fun ChatBar(
    title: String,
    modelLabel: String,
    agentLabel: String,
    busy: Boolean,
    menuOpen: Boolean,
    canRevert: Boolean,
    onBack: () -> Unit,
    onOpenPicker: () -> Unit,
    onStop: () -> Unit,
    onToggleMenu: () -> Unit,
    onOpenAgents: () -> Unit,
    onOpenCommands: () -> Unit,
    onCompact: () -> Unit,
    onRevert: () -> Unit,
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = I18n.s.back)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onOpenPicker) {
                        Text(
                            modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = onOpenAgents) {
                        Text(
                            agentLabel,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (busy) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = I18n.s.stop)
                }
            }
            Box {
                IconButton(onClick = onToggleMenu) {
                    Icon(Icons.Default.MoreVert, contentDescription = I18n.s.menu)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onToggleMenu) {
                    DropdownMenuItem(text = { Text(I18n.s.menuAgent) }, onClick = onOpenAgents)
                    DropdownMenuItem(text = { Text(I18n.s.menuCommands) }, onClick = onOpenCommands)
                    DropdownMenuItem(text = { Text(I18n.s.menuCompact) }, onClick = onCompact)
                    DropdownMenuItem(
                        text = { Text(I18n.s.menuRevertLast) },
                        onClick = onRevert,
                        enabled = canRevert,
                    )
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
            placeholder = { Text(I18n.s.writeMessage) },
            singleLine = false,
            minLines = 1,
            maxLines = 5,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (busy) {
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = I18n.s.stop)
            }
        } else {
            IconButton(onClick = onSend, enabled = input.isNotBlank()) {
                Icon(Icons.Default.Send, contentDescription = I18n.s.send)
            }
        }
    }
}

@Composable
private fun UserBubble(item: ChatItem.User, onRevert: () -> Unit) {
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
                        I18n.s.attachments(item.fileCount),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onRevert) {
                    Text(I18n.s.revertHere, style = MaterialTheme.typography.labelSmall)
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
                            if (showThinking) I18n.s.showThinking else I18n.s.thinkingCount(item.reasonings.size),
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
                    ClayCard(contentPadding = PaddingValues(0.dp)) {
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
                            if (copied) I18n.s.copied else I18n.s.copy,
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
                I18n.s.approvalTitle(pending.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            pending.take(5).forEach { req ->
                ClayCard(contentPadding = PaddingValues(0.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            req.summary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onReply(req, "reject") }) {
                                Text(I18n.s.reject, color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(onClick = { onReply(req, "once") }) { Text(I18n.s.allow) }
                            TextButton(onClick = { onReply(req, "always") }) { Text(I18n.s.always) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSheet(
    agents: List<AgentInfo>,
    current: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                I18n.s.agentTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (agents.isEmpty()) {
                Text(I18n.s.noAgents, style = MaterialTheme.typography.bodyMedium)
            }
            agents.forEach { a ->
                ClayCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.id, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            Text(
                                a.description.ifBlank { I18n.s.modeFmt(a.mode) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (a.id == current) {
                            Text(I18n.s.active, style = MaterialTheme.typography.labelSmall)
                        } else {
                            TextButton(onClick = { onSelect(a.id) }) { Text(I18n.s.pick) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandSheet(
    commands: List<CommandInfo>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                I18n.s.commandsTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (commands.isEmpty()) {
                Text(I18n.s.noCommands, style = MaterialTheme.typography.bodyMedium)
            }
            commands.forEach { c ->
                ClayCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("/" + c.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            if (c.description.isNotBlank()) {
                                Text(
                                    c.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = { onPick(c.name) }) { Text(I18n.s.use) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RevertSheet(
    state: RevertState,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                I18n.s.revertToMessage(state.messageID),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (state.files.isEmpty()) {
                Text(
                    I18n.s.noFilesChanged,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    I18n.s.filesWillRevert(state.files.size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.files.forEach { f ->
                    ClayCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    f.path,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${f.status} +${f.additions} -${f.deletions}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (f.patch.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    f.patch.take(4000),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(I18n.s.cancelBtn) }
                TextButton(onClick = onApply, modifier = Modifier.weight(1f)) {
                    Text(I18n.s.applyRevert, color = MaterialTheme.colorScheme.error)
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
