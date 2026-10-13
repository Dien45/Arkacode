package com.arkacode.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arkacode.app.core.EngineConfig
import com.arkacode.app.core.EngineManager
import com.arkacode.app.net.OpenCodeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar debug F0: status engine opencode + distro + serve.
 * Langkah di HP: 1) Setup distro → 2) Download engine → 3) Start server → 4) Health check.
 */
@Composable
fun EngineScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { EngineManager(context) }

    var distroInfo by remember { mutableStateOf("belum dicek") }
    var engineInfo by remember { mutableStateOf("belum dicek") }
    var serverInfo by remember { mutableStateOf("berhenti") }
    var busy by remember { mutableStateOf(false) }
    var downloadPct by remember { mutableIntStateOf(-1) }
    var distroPct by remember { mutableIntStateOf(-1) }
    var logTail by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var portText by remember { mutableStateOf(EngineConfig.DEFAULT_PORT.toString()) }
    var password by remember { mutableStateOf("arkacode-local") }

    fun refresh() {
        scope.launch {
            val distro = withContext(Dispatchers.IO) { engine.distroStatus() }
            distroInfo = distro.message
            val downloaded = withContext(Dispatchers.IO) { engine.isDownloaded() }
            engineInfo = if (downloaded) {
                withContext(Dispatchers.IO) { engine.version() } ?: "binary ada (distro belum siap cek versi)"
            } else {
                "belum diunduh"
            }
            serverInfo = if (engine.isRunning()) "jalan (port $portText)" else "berhenti"
            logTail = withContext(Dispatchers.IO) { engine.tailLog() }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun runJob(block: suspend () -> Unit) {
        error = null
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: "gagal"
            } finally {
                busy = false
                refresh()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Arkacode — Engine opencode (F0)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("opencode v${EngineConfig.VERSION} di proot Alpine", style = MaterialTheme.typography.bodySmall)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Distro: $distroInfo", style = MaterialTheme.typography.bodyMedium)
                Text("Engine: $engineInfo", style = MaterialTheme.typography.bodyMedium)
                Text("Server: $serverInfo", style = MaterialTheme.typography.bodyMedium)
                error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
            }
        }

        if (distroPct in 0..100) LinearProgressIndicator(progress = { distroPct / 100f }, modifier = Modifier.fillMaxWidth())
        if (downloadPct in 0..100) LinearProgressIndicator(progress = { downloadPct / 100f }, modifier = Modifier.fillMaxWidth())
        if (busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.padding(4.dp))
            Text("bekerja…")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
        }

        Button(
            onClick = {
                runJob {
                    distroPct = 0
                    engine.setupDistro { p, _ -> distroPct = p }.getOrThrow()
                    distroPct = -1
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("1. Setup distro Alpine") }

        Button(
            onClick = {
                runJob {
                    downloadPct = 0
                    val v = engine.ensureInstalled { downloadPct = it }.getOrThrow()
                    engineInfo = v
                    downloadPct = -1
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("2. Download engine opencode") }

        Button(
            onClick = {
                runJob {
                    val port = portText.toIntOrNull() ?: EngineConfig.DEFAULT_PORT
                    engine.startServer(port, password).getOrThrow()
                }
            },
            enabled = !busy && !engine.isRunning(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("3. Start server") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    runJob {
                        val port = portText.toIntOrNull() ?: EngineConfig.DEFAULT_PORT
                        val h = OpenCodeClient(port = port, password = password).health()
                        serverInfo = "OK healthy=${h.healthy} v${h.version}"
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Health check") }
            OutlinedButton(
                onClick = {
                    engine.stopServer()
                    refresh()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Stop") }
            OutlinedButton(
                onClick = { refresh() },
                modifier = Modifier.weight(1f),
            ) { Text("Refresh") }
        }

        Text("serve.log:", style = MaterialTheme.typography.labelMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Text(
                logTail.ifBlank { "(kosong)" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(10.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
