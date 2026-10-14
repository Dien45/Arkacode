package com.arkacode.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arkacode.app.core.SecureStore
import com.arkacode.app.net.ModelInfo
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.ProviderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nama env yang dibaca opencode per provider (sama dengan EngineManager). */
private val ENV_HINTS = mapOf(
    "openai" to listOf("OPENAI_API_KEY"),
    "anthropic" to listOf("ANTHROPIC_API_KEY"),
    "google" to listOf("GOOGLE_GENERATIVE_AI_API_KEY", "GEMINI_API_KEY"),
    "groq" to listOf("GROQ_API_KEY"),
    "openrouter" to listOf("OPENROUTER_API_KEY"),
    "mistral" to listOf("MISTRAL_API_KEY"),
    "deepseek" to listOf("DEEPSEEK_API_KEY"),
    "xai" to listOf("XAI_API_KEY"),
)

/**
 * Layar Penyedia AI (F2+): daftar provider + model dari server, isi API key
 * per provider (tersimpan terenkripsi, disuntik sebagai env saat serve nyala).
 * Berlaku setelah Stop → Start ulang server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(port: Int, password: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(port, password) { OpenCodeClient(port = port, password = password) }

    var providers by remember { mutableStateOf<List<ProviderInfo>?>(null) }
    var models by remember { mutableStateOf(listOf<ModelInfo>()) }
    var saved by remember { mutableStateOf(setOf<String>()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var drafts by remember { mutableStateOf(mapOf<String, String>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true
        error = null
        scope.launch {
            providers = runCatching {
                withContext(Dispatchers.IO) { client.listProviders() }
            }.onFailure { error = it.message }.getOrNull()
            models = runCatching {
                withContext(Dispatchers.IO) { client.listModels() }
            }.getOrDefault(emptyList())
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        load()
        saved = withContext(Dispatchers.IO) {
            // tandai provider yang sudah punya kunci tersimpan
            (providers ?: emptyList()).mapNotNull { p ->
                val has = runCatching { SecureStore.getAuth(context, p.id) }.getOrNull()
                if (!has.isNullOrBlank()) p.id else null
            }.toSet()
        }
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                    Text(
                        "Penyedia AI",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Text(
                    "Isi API key per provider — tersimpan terenkripsi di HP dan disuntik otomatis tiap server nyala. Berlaku setelah Stop → Start ulang. Model gratis Zen butuh login via CLI/TUI di mesin lain untuk saat ini.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            error?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { error = null }) { Text("Tutup") }
                }
            }
            notice?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { notice = null }) { Text("Tutup") }
                }
            }
            if (loading && providers == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    Text("memuat…", style = MaterialTheme.typography.bodySmall)
                }
            }
            val list = providers ?: emptyList()
            val byProvider = models.groupBy { it.providerID }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.id }) { p ->
                    val ms = byProvider[p.id] ?: emptyList()
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expanded = if (p.id in expanded) expanded - p.id else expanded + p.id
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                if (p.id in saved) {
                                    Text("●", color = MaterialTheme.colorScheme.tertiary)
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text("${ms.size} model", style = MaterialTheme.typography.labelSmall)
                            }
                            if (p.id in expanded) {
                                Spacer(Modifier.height(6.dp))
                                val envs = ENV_HINTS[p.id]
                                if (envs != null) {
                                    Text(
                                        "Env: " + envs.joinToString(", "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedTextField(
                                        value = drafts[p.id] ?: "",
                                        onValueChange = { drafts = drafts + (p.id to it) },
                                        placeholder = { Text(if (p.id in saved) "tersimpan (isi untuk ganti)" else "tempel API key…") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = {
                                            val v = drafts[p.id] ?: ""
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    SecureStore.setAuth(context, p.id, v)
                                                }
                                                drafts = drafts - p.id
                                                saved = if (v.isBlank()) saved - p.id else saved + p.id
                                                notice = if (v.isBlank()) "Kunci ${p.name} dihapus." else "Kunci ${p.name} tersimpan. Stop → Start agar berlaku."
                                            }
                                        }) { Text("Simpan") }
                                        TextButton(onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    SecureStore.setAuth(context, p.id, "")
                                                }
                                                drafts = drafts - p.id
                                                saved = saved - p.id
                                                notice = "Kunci ${p.name} dihapus."
                                            }
                                        }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
                                    }
                                } else {
                                    Text(
                                        "Kunci diatur via opencode.json di server untuk provider ini.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (ms.isNotEmpty()) {
                                    Text("Model:", style = MaterialTheme.typography.labelMedium)
                                    ms.take(30).forEach { m ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                m.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                            )
                                            if (m.free) {
                                                Text(
                                                    "Gratis",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                )
                                            }
                                        }
                                    }
                                    if (ms.size > 30) {
                                        Text("+${ms.size - 30} lainnya", style = MaterialTheme.typography.labelSmall)
                                    }
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
