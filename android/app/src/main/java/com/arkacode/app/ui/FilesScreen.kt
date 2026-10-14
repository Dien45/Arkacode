package com.arkacode.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.arkacode.app.net.FsEntry
import com.arkacode.app.net.OpenCodeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * F2 Files: jelajahi workspace server (tree per folder), baca file teks,
 * cari file. Path relatif ke working dir server (/root/workspace).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(port: Int, password: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(port, password) { OpenCodeClient(port = port, password = password) }

    var cwd by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FsEntry>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var openedPath by remember { mutableStateOf<String?>(null) }
    var openedText by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun loadDir(dir: String) {
        job?.cancel()
        loading = true
        error = null
        openedPath = null
        job = scope.launch {
            entries = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.listFiles(dir)
                }
            }.onFailure { error = it.message }.getOrNull()
            loading = false
        }
    }

    fun openFile(path: String) {
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            val text = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.readFile(path)
                }
            }.onFailure { error = it.message }.getOrNull()
            if (text != null) {
                openedPath = path
                openedText = text
            }
            loading = false
        }
    }

    fun search() {
        job?.cancel()
        loading = true
        searching = true
        error = null
        openedPath = null
        job = scope.launch {
            entries = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.findFiles(query)
                }
            }.onFailure { error = it.message }.getOrNull()
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadDir("") }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            if (openedPath != null) {
                                openedPath = null
                            } else onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                        }
                        Text(
                            if (openedPath != null) openedPath!!.substringAfterLast('/') else "Files",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            searching = false
                            query = ""
                            loadDir(cwd)
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Muat ulang")
                        }
                    }
                    if (openedPath == null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Cari file…") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { search() }, enabled = query.isNotBlank()) {
                                Icon(Icons.Default.Search, contentDescription = "Cari")
                            }
                        }
                        if (cwd.isNotBlank() && !searching) {
                            Breadcrumb(
                                cwd = cwd,
                                onRoot = {
                                    cwd = ""
                                    loadDir("")
                                },
                                onJump = {
                                    cwd = it
                                    loadDir(it)
                                },
                            )
                        }
                        if (searching) {
                            TextButton(onClick = {
                                searching = false
                                query = ""
                                loadDir(cwd)
                            }) { Text("Tutup pencarian") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            error?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { error = null }) { Text("Tutup") }
                }
            }
            if (loading && entries == null && openedPath == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    Text("memuat…", style = MaterialTheme.typography.bodySmall)
                }
            }
            val open = openedPath
            if (open != null) {
                FileView(path = open, content = openedText ?: "memuat…")
            } else {
                val list = entries ?: emptyList()
                if (list.isEmpty() && !loading) {
                    Text("Kosong.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val dirs = list.filter { it.isDir }.sortedBy { it.path }
                        val files = list.filterNot { it.isDir }.sortedBy { it.path }
                        items(dirs + files, key = { it.path }) { e ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (e.isDir) {
                                                cwd = e.path
                                                loadDir(e.path)
                                            } else {
                                                openFile(e.path)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (e.isDir) "📁" else "📄")
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        e.path.substringAfterLast('/'),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Breadcrumb(cwd: String, onRoot: () -> Unit, onJump: (String) -> Unit) {
    val parts = cwd.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onRoot) { Text("/") }
        var acc = ""
        parts.forEach { part ->
            acc = if (acc.isEmpty()) part else "$acc/$part"
            val target = acc
            Text("/")
            TextButton(onClick = { onJump(target) }) { Text(part, maxLines = 1) }
        }
    }
}

@Composable
private fun FileView(path: String, content: String) {
    val context = LocalContext.current
    var copied by remember(path) { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                path,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("file", content))
                copied = true
            }) {
                Text(if (copied) "Disalin ✓" else "Salin", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            content,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
