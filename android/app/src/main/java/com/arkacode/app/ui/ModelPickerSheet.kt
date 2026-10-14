package com.arkacode.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.ProviderInfo
import kotlinx.coroutines.launch

/**
 * Bottom sheet pilih model per sesi (F1): daftar provider → model.
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
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        scope.launch {
            providers = runCatching { client.listProviders() }.getOrNull() ?: emptyList()
        }
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
            val list = providers
            if (list == null) {
                Text("memuat…", style = MaterialTheme.typography.bodyMedium)
            } else if (list.isEmpty()) {
                Text(
                    "Tidak ada provider. Atur penyedia di server dulu.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.id }) { provider ->
                        val matches = remember(provider, query) {
                            if (query.isBlank()) provider.models
                            else provider.models.filter {
                                it.id.contains(query, ignoreCase = true) ||
                                    it.name.contains(query, ignoreCase = true)
                            }
                        }
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expanded = if (provider.id in expanded) {
                                                expanded - provider.id
                                            } else {
                                                expanded + provider.id
                                            }
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        provider.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${matches.size} model",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                if (provider.id in expanded) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        matches.take(100).forEach { model ->
                                            val selected = provider.id == currentProviderID &&
                                                model.id == currentModelID
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onSelect(provider.id, model.id) }
                                                    .padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    model.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.weight(1f),
                                                )
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
        }
    }
}
