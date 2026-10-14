package com.arkacode.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkacode.app.net.ModelInfo
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.ProviderInfo
import kotlinx.coroutines.launch

/**
 * Bottom sheet pilih model per sesi: model diambil flat dari server
 * (`GET /api/model`) lalu dikelompokkan per provider. Badge "Gratis"
 * untuk model bercost nol (mis. sebagian model Zen).
 * Centang = pin sesi aktif; tap langsung switch model sesi di server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerContent(
    client: OpenCodeClient,
    currentProviderID: String?,
    currentModelID: String?,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<ProviderInfo>?>(null) }
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        scope.launch {
            providers = runCatching { client.listProviders() }.getOrNull() ?: emptyList()
            models = runCatching { client.listModels() }.getOrNull() ?: emptyList()
        }
    }

    val names = remember(providers) {
        (providers ?: emptyList()).associate { it.id to it.name }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Pilih model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Cari…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val all = models
            if (all == null) {
                Text("memuat…", style = MaterialTheme.typography.bodyMedium)
            } else if (all.isEmpty()) {
                Text(
                    "Tidak ada model dari server. Pastikan penyedia sudah dikonfigurasi (butuh login/API key di server opencode).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val filtered = remember(all, query) {
                    if (query.isBlank()) all
                    else all.filter {
                        it.id.contains(query, ignoreCase = true) ||
                            it.name.contains(query, ignoreCase = true) ||
                            it.providerID.contains(query, ignoreCase = true)
                    }
                }
                val grouped = remember(filtered) { filtered.groupBy { it.providerID } }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    grouped.toList().sortedBy { it.first }.forEach { (pid, ms) ->
                        item(key = "head_$pid") {
                            val label = names[pid] ?: pid
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expanded = if (pid in expanded) expanded - pid else expanded + pid
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("${ms.size} model", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (pid in expanded) {
                            items(ms.take(200), key = { pid + "_" + it.id }) { model ->
                                val selected = pid == currentProviderID && model.id == currentModelID
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(pid, model.id) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(model.name, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            model.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (model.free) {
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            ),
                                        ) {
                                            Text(
                                                "Gratis",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    if (selected) {
                                        Icon(Icons.Default.Check, contentDescription = "Aktif")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
