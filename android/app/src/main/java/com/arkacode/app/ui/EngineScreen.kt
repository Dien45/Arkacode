package com.arkacode.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkacode.app.core.EngineConfig
import com.arkacode.app.core.EngineManager
import com.arkacode.app.core.SessionRunRegistry
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.ChatSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEFAULT_PASSWORD = "arkacode-local"

private sealed interface Screen {
    data class Boot(val attempt: Int) : Screen
    data class Ready(val version: String) : Screen
    data object Stopped : Screen
}

/**
 * F0+: layar log auto-provisioning. Buka app → semuanya jalan sendiri
 * (cek server lama → distro → engine → start → health). Baru kalau server
 * sudah jalan, frontend ditampilkan.
 */
@Composable
fun EngineScreen(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { EngineManager(context) }
    var port by remember { mutableStateOf(EngineConfig.DEFAULT_PORT) }
    var password by remember { mutableStateOf(DEFAULT_PASSWORD) }
    var credsReady by remember { mutableStateOf(false) }

    // Muat kredensial tersimpan (bila ada) sebelum pipeline jalan.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { com.arkacode.app.core.SecureStore.load(context) }.getOrNull()
        }?.let { (savedPort, savedPw) ->
            port = savedPort
            if (savedPw.isNotEmpty()) password = savedPw
        }
        credsReady = true
        runCatching { com.arkacode.app.core.SessionUiPrefs(context).langCode() }.getOrNull()?.let { code ->
            I18n.lang = AppLang.entries.firstOrNull { it.code == code } ?: AppLang.ID
        }
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Boot(0)) }
    var chatTarget by remember { mutableStateOf<ChatSession?>(null) }
    var showProviders by remember { mutableStateOf(false) }
    var showCaps by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    var failMsg by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun stamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    fun log(s: String) {
        lines = (lines + "[${stamp()}] $s").takeLast(300)
    }

    suspend fun pipeline() {
        working = true
        failMsg = null
        try {
            // 0. Server lama masih hidup? Langsung masuk.
            log("cek server lama di port $port…")
            val alive = runCatching {
                OpenCodeClient(port = port, password = password).health()
            }.getOrNull()
            if (alive?.healthy == true) {
                log("server sudah jalan v${alive.version}, langsung masuk.")
                screen = Screen.Ready(alive.version)
                return
            }
            // 1. Distro
            val distro = withContext(Dispatchers.IO) { engine.distroStatus() }
            if (!distro.installed) {
                log("Alpine belum ada — mengunduh & mengekstrak (±4 MB)…")
                var last = -1
                engine.setupDistro { p, label ->
                    if (p / 10 != last / 10) {
                        last = p
                        scope.launch { log("distro $p% — $label") }
                    }
                }.getOrThrow()
                log("distro siap.")
            } else {
                log("distro siap (${distro.message}).")
            }
            // 2. Engine
            if (!withContext(Dispatchers.IO) { engine.isDownloaded() }) {
                log("mengunduh opencode ${EngineConfig.VERSION} (±60 MB)…")
                var last = -1
                val v = engine.ensureInstalled {
                    if (it / 10 != last / 10) {
                        last = it
                        scope.launch { log("engine $it%") }
                    }
                }.getOrThrow()
                log("engine: $v")
            } else {
                val v = withContext(Dispatchers.IO) { engine.version() } ?: "siap"
                log("engine sudah ada ($v).")
            }
            // 3. Start + health (termasuk pasang lib C++ sekali bila perlu)
            log("menyalakan server di port $port…")
            engine.startServer(port, password).getOrThrow()
            val h = OpenCodeClient(port = port, password = password).health()
            log("server jalan v${h.version}.")
            screen = Screen.Ready(h.version)
        } catch (e: Exception) {
            val msg = e.message ?: "gagal"
            log("GAGAL: $msg")
            failMsg = msg
        } finally {
            working = false
        }
    }

    when (val s = screen) {
        is Screen.Boot -> {
            LaunchedEffect(s.attempt, credsReady) { if (credsReady) pipeline() }
            BootScreen(
                lines = lines,
                working = working,
                failMsg = failMsg,
                onRetry = {
                    lines = emptyList()
                    screen = Screen.Boot(s.attempt + 1)
                },
            )
        }
        is Screen.Ready -> {
            val target = chatTarget
            val chatClient = remember(port, password) { OpenCodeClient(port = port, password = password) }
            val runs = remember(chatClient) { SessionRunRegistry(scope, chatClient) }
            if (showCaps) {
                CapabilitiesScreen(
                    port = port,
                    password = password,
                    onBack = { showCaps = false },
                )
            } else if (showProviders) {
                ProvidersScreen(
                    port = port,
                    password = password,
                    onBack = { showProviders = false },
                )
            } else if (target != null) {
                ChatScreen(
                    sessionId = target.id,
                    sessionTitle = target.title,
                    port = port,
                    password = password,
                    client = chatClient,
                    registry = runs,
                    initialProviderID = target.providerID,
                    initialModelID = target.modelID,
                    onBack = { chatTarget = null },
                )
            } else {
                ReadyScreen(
                    version = s.version,
                    port = port,
                    password = password,
                    engine = engine,
                    isDark = darkTheme,
                    onToggleTheme = onToggleTheme,

                    onStop = {
                        scope.launch {
                            withContext(Dispatchers.IO) { engine.stopServer() }
                            lines = lines + "[${stamp()}] server dihentikan."
                            screen = Screen.Stopped
                        }
                    },
                    onOpenChat = { chatTarget = it },
                    onNewSession = { chatTarget = it },
                    onOpenProviders = { showProviders = true },
                    onOpenCapabilities = { showCaps = true },
                )
            }
        }
        Screen.Stopped -> {
            val listState = rememberLazyListState()
            LaunchedEffect(lines.size) {
                if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(I18n.s.stoppedTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = port.toString(),
                    onValueChange = { port = it.filter(Char::isDigit).take(5).toIntOrNull() ?: port },
                    label = { Text(I18n.s.port) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(I18n.s.serverPassword) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { com.arkacode.app.core.SecureStore.save(context, port, password) }
                            }
                            screen = Screen.Boot(0)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(I18n.s.saveStart) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(lines, key = { it }) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun BootScreen(
    lines: List<String>,
    working: Boolean,
    failMsg: String?,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                I18n.s.preparing,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (working) CircularProgressIndicator()
        }
        if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        failMsg?.let {
            Text(I18n.s.failedPrefix + it, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(I18n.s.retry) }
        }
        ClayCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(0.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(lines, key = { it }) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun ReadyScreen(
    version: String,
    port: Int,
    password: String,
    engine: EngineManager,
    onStop: () -> Unit,
    onOpenChat: (ChatSession) -> Unit,
    onNewSession: (ChatSession) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenCapabilities: () -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uiPrefs = remember { com.arkacode.app.core.SessionUiPrefs(context) }
    var sessions by remember { mutableStateOf<List<ChatSession>?>(null) }
    var titles by remember { mutableStateOf(mapOf<String, String>()) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    var loading by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    var creating by remember { mutableStateOf(false) }

    fun load() {
        loading = true
        scope.launch {
            sessions = runCatching {
                withContext(Dispatchers.IO) {
                    OpenCodeClient(port = port, password = password).listSessions()
                }
            }.getOrNull()
            titles = runCatching { uiPrefs.titleOverrides() }.getOrDefault(titles)
            hidden = runCatching { uiPrefs.hiddenIds() }.getOrDefault(hidden)
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(I18n.s.appName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    I18n.s.tagline(version, port),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = {
                I18n.lang = if (I18n.lang == AppLang.ID) AppLang.EN else AppLang.ID
                scope.launch { runCatching { uiPrefs.setLangCode(I18n.lang.code) } }
            }) { Text(if (I18n.lang == AppLang.ID) "EN" else "ID") }
            TextButton(onClick = onToggleTheme) { Text(if (isDark) "\u2600\uFE0F" else "\uD83C\uDF19") }
            OutlinedButton(onClick = onStop) { Text(I18n.s.stop) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(I18n.s.sessionsCount(sessions?.size ?: 0), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))


            OutlinedButton(
                onClick = {
                    creating = true
                    scope.launch {
                        val created = runCatching {
                            withContext(Dispatchers.IO) {
                                OpenCodeClient(port = port, password = password).createSession()
                            }
                        }.getOrNull()
                        creating = false
                        if (created != null) onNewSession(created) else load()
                    }
                },
                enabled = !loading && !creating,
            ) { Text(I18n.s.newSession) }
            OutlinedButton(onClick = { load() }, enabled = !loading) { Text(I18n.s.reload) }
        }
        val all = sessions
        val list = all?.filter { it.id !in hidden }
        if (list == null) {
            Text(
                if (loading) I18n.s.loadingSessions else I18n.s.loadSessionsFailed,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        } else if (list.isEmpty()) {
            Text(
                I18n.s.noSessions,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(list.take(50), key = { it.id }) { s ->
                    val shown = s.copy(title = titles[s.id] ?: s.title)
                    ClayCard(
                        modifier = Modifier.clickable { onOpenChat(shown) },
                    ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(shown.title.ifBlank { I18n.s.noTitle }, fontWeight = FontWeight.SemiBold)
                                    Text(s.id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                }
                                TextButton(onClick = {
                                    renameTarget = s
                                    renameDraft = titles[s.id] ?: s.title
                                }) { Text(I18n.s.name) }
                                TextButton(onClick = { deleteTarget = s }) {
                                    Text(I18n.s.deleteKey, color = MaterialTheme.colorScheme.error)
                                }
                            }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        NavigationBar {
            NavigationBarItem(
                selected = true,
                onClick = {},
                icon = { Icon(Icons.Default.List, contentDescription = null) },
                label = { Text(I18n.s.navSessions) },
            )
            NavigationBarItem(
                selected = false,
                onClick = onOpenProviders,
                icon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                label = { Text(I18n.s.navAI) },
            )
            NavigationBarItem(
                selected = false,
                onClick = onOpenCapabilities,
                icon = { Icon(Icons.Default.Build, contentDescription = null) },
                label = { Text(I18n.s.navCaps) },
            )
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(I18n.s.renameSession) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text(I18n.s.name) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val name = renameDraft.trim()
                        if (name.isEmpty()) {
                            runCatching { uiPrefs.clearTitle(target.id) }
                            titles = titles - target.id
                        } else {
                            runCatching { uiPrefs.setTitle(target.id, name.take(80)) }
                            titles = titles + (target.id to name.take(80))
                        }
                        renameTarget = null
                    }
                }) { Text(I18n.s.save) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(I18n.s.cancel) }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(I18n.s.hideSession) },
            text = {
                Text(I18n.s.hideSessionBody)
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { uiPrefs.setHidden(target.id, true) }
                        hidden = hidden + target.id
                        deleteTarget = null
                    }
                }) { Text(I18n.s.hide, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(I18n.s.cancel) }
            },
        )
    }
}
