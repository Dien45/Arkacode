package com.arkacode.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
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
import com.arkacode.app.net.IntegrationInfo
import com.arkacode.app.net.IntegrationMethod
import com.arkacode.app.net.IntegrationPrompt
import com.arkacode.app.net.OAuthAttempt
import com.arkacode.app.net.OpenCodeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** State satu alur OAuth yang sedang berjalan. */
private data class ActiveOAuth(
    val integration: IntegrationInfo,
    val method: IntegrationMethod,
    val attempt: OAuthAttempt,
)

/** Apakah prompt tampil berdasar kondisi `when` (eq/neq). */
private fun promptVisible(p: IntegrationPrompt, values: Map<String, String>): Boolean {
    val k = p.whenKey ?: return true
    val v = values[k] ?: ""
    return when (p.whenOp) {
        "eq" -> v == p.whenValue
        "neq" -> v != p.whenValue
        else -> true
    }
}

/**
 * Integrasi & OAuth (F4): daftar integrasi server + hubungkan via OAuth (mis.
 * opencode Zen) atau API key. Kredensial disimpan di server; server perlu
 * Stop → Start ulang agar berlaku.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsSheet(port: Int, password: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(port, password) { OpenCodeClient(port = port, password = password) }

    var integrations by remember { mutableStateOf<List<IntegrationInfo>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var promptValues by remember { mutableStateOf(mapOf<String, String>()) }
    var keyDrafts by remember { mutableStateOf(mapOf<String, String>()) }
    var active by remember { mutableStateOf<ActiveOAuth?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var codeInput by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            loading = true
            integrations = runCatching { client.listIntegrations() }
                .onFailure { error = it.message }
                .getOrDefault(emptyList())
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun begin(mi: IntegrationInfo, m: IntegrationMethod) {
        val methodId = m.id ?: return
        val inputs = m.prompts
            .filter { promptVisible(it, promptValues) }
            .associate { it.key to (promptValues[it.key] ?: "") }
        scope.launch {
            error = null
            statusText = I18n.s.connecting
            runCatching { client.beginOAuth(mi.id, methodId, inputs) }
                .onSuccess { att ->
                    active = ActiveOAuth(mi, m, att)
                    statusText = att.instructions.ifBlank { I18n.s.oauthInstructions }
                    if (att.url.isNotBlank()) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(att.url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
                .onFailure {
                    statusText = null
                    error = it.message
                }
        }
    }

    fun finish() {
        val att = active?.attempt ?: return
        scope.launch {
            runCatching { client.completeOAuth(att.attemptID, codeInput) }
                .onSuccess {
                    notice = I18n.s.oauthComplete
                    active = null
                    statusText = null
                    codeInput = ""
                    reload()
                }
                .onFailure { error = it.message }
        }
    }

    fun cancelAttempt() {
        val att = active?.attempt ?: return
        scope.launch {
            runCatching { client.cancelOAuth(att.attemptID) }
            active = null
            statusText = null
            codeInput = ""
        }
    }

    // Poll status untuk mode auto; mode code diselesaikan manual oleh pengguna.
    LaunchedEffect(active?.attempt?.attemptID) {
        val att = active?.attempt ?: return@LaunchedEffect
        if (att.mode == "code") return@LaunchedEffect
        while (true) {
            delay(2500)
            val st = runCatching { client.oauthStatus(att.attemptID) }.getOrNull() ?: continue
            when (st.status) {
                "complete" -> {
                    notice = I18n.s.oauthComplete
                    active = null
                    statusText = null
                    reload()
                    break
                }
                "failed" -> {
                    error = st.message ?: I18n.s.oauthFailed("")
                    active = null
                    statusText = null
                    break
                }
                "expired" -> {
                    error = I18n.s.oauthExpired
                    active = null
                    statusText = null
                    break
                }
                else -> statusText = I18n.s.oauthPending
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .heightIn(max = 620.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    I18n.s.integrationsTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = I18n.s.close)
                }
            }
            Text(
                I18n.s.integrationsHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { error = null }) { Text(I18n.s.close) }
                }
            }
            notice?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { notice = null }) { Text(I18n.s.close) }
                }
            }

            active?.let { a ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${a.integration.name} · ${a.method.label}", fontWeight = FontWeight.SemiBold)
                        statusText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (a.attempt.url.isNotBlank()) {
                            Text(
                                a.attempt.url,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (a.attempt.url.isNotBlank()) {
                                TextButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(a.attempt.url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }) { Text(I18n.s.oauthOpenBrowser) }
                            }
                            TextButton(onClick = { cancelAttempt() }) { Text(I18n.s.cancelConnect) }
                        }
                        if (a.attempt.mode == "code") {
                            OutlinedTextField(
                                value = codeInput,
                                onValueChange = { codeInput = it },
                                label = { Text(I18n.s.oauthCodeLabel) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(onClick = { finish() }) { Text(I18n.s.finishConnect) }
                        }
                    }
                }
            }

            if (loading && integrations == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    Text(I18n.s.loading, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val list = integrations ?: emptyList()
                if (list.isEmpty()) {
                    Text(I18n.s.noIntegrations, style = MaterialTheme.typography.bodyMedium)
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    list.forEach { mi ->
                        IntegrationCard(
                            mi = mi,
                            promptValues = promptValues,
                            keyDraft = keyDrafts[mi.id] ?: "",
                            onPromptChange = { k, v -> promptValues = promptValues + (k to v) },
                            onKeyDraftChange = { v -> keyDrafts = keyDrafts + (mi.id to v) },
                            onConnectOAuth = { m -> begin(mi, m) },
                            onConnectKey = { m ->
                                scope.launch {
                                    runCatching { client.connectIntegrationKey(mi.id, keyDrafts[mi.id] ?: "") }
                                        .onSuccess {
                                            notice = I18n.s.connected
                                            keyDrafts = keyDrafts - mi.id
                                            reload()
                                        }
                                        .onFailure { error = it.message }
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun IntegrationCard(
    mi: IntegrationInfo,
    promptValues: Map<String, String>,
    keyDraft: String,
    onPromptChange: (String, String) -> Unit,
    onKeyDraftChange: (String) -> Unit,
    onConnectOAuth: (IntegrationMethod) -> Unit,
    onConnectKey: (IntegrationMethod) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mi.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (mi.connections.isNotEmpty()) {
                    Text(
                        I18n.s.connected,
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            mi.connections.forEach { c ->
                val label = c.label ?: c.envName ?: c.id ?: c.type
                Text(
                    I18n.s.connLabel(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            mi.methods.forEach { m ->
                when (m.type) {
                    "oauth" -> {
                        Text(m.label, style = MaterialTheme.typography.bodySmall)
                        m.prompts.filter { promptVisible(it, promptValues) }.forEach { p ->
                            if (p.type == "select") {
                                Text(p.message, style = MaterialTheme.typography.labelSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    p.options.forEach { op ->
                                        FilterChip(
                                            selected = (promptValues[p.key] ?: "") == op.value,
                                            onClick = { onPromptChange(p.key, op.value) },
                                            label = { Text(op.label) },
                                        )
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = promptValues[p.key] ?: "",
                                    onValueChange = { onPromptChange(p.key, it) },
                                    label = { Text(p.message) },
                                    placeholder = p.placeholder?.let { ph -> { Text(ph) } },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        TextButton(onClick = { onConnectOAuth(m) }) { Text(I18n.s.connect) }
                    }
                    "key" -> {
                        OutlinedTextField(
                            value = keyDraft,
                            onValueChange = onKeyDraftChange,
                            label = { Text(m.label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { onConnectKey(m) }) { Text(I18n.s.connect) }
                    }
                    "env" -> {
                        Text(
                            I18n.s.envProvided(m.envNames.joinToString(", ")),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
