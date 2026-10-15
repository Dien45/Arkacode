package com.arkacode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkacode.app.net.AgentInfo
import com.arkacode.app.net.CommandInfo
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.SkillInfo

private enum class CapsTab { Agents, Commands, Skills }

/**
 * Daftar kapabilitas opencode server: agent, perintah (slash command), dan skill.
 * Sumber kebenaran langsung dari `/api/agent`, `/api/command`, `/api/skill`.
 */
@Composable
fun CapabilitiesScreen(
    port: Int,
    password: String,
    onBack: () -> Unit,
) {
    val client = remember(port, password) { OpenCodeClient(port = port, password = password) }
    var tab by remember { mutableStateOf(CapsTab.Agents) }
    var agents by remember { mutableStateOf<List<AgentInfo>?>(null) }
    var commands by remember { mutableStateOf<List<CommandInfo>?>(null) }
    var skills by remember { mutableStateOf<List<SkillInfo>?>(null) }

    LaunchedEffect(Unit) {
        agents = runCatching { client.listAgents() }.getOrNull()
        commands = runCatching { client.listCommands() }.getOrNull()
        skills = runCatching { client.listSkills() }.getOrNull()
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ClayHeader(
                title = I18n.s.capsTitle,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                leading = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = I18n.s.back)
                    }
                },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CapsTab.entries.forEach { t ->
                    ClayPill(
                        text = if (t == CapsTab.Agents) I18n.s.tabAgents else if (t == CapsTab.Commands) I18n.s.tabCommands else I18n.s.tabSkills,
                        selected = tab == t,
                        onClick = { tab = t },
                    )
                }
            }
            when (tab) {
                CapsTab.Agents -> CapsList(
                    items = agents,
                    empty = I18n.s.noAgentsShort,
                ) { a ->
                    CapsRow(
                        title = a.id,
                        subtitle = a.description.ifBlank { I18n.s.modeFmt(a.mode) },
                        tag = a.mode,
                    )
                }
                CapsTab.Commands -> CapsList(
                    items = commands,
                    empty = I18n.s.noCommandsShort,
                ) { c ->
                    CapsRow(
                        title = "/" + c.name,
                        subtitle = c.description.ifBlank { I18n.s.commandHint(c.name) },
                        tag = c.agent,
                    )
                }
                CapsTab.Skills -> CapsList(
                    items = skills,
                    empty = I18n.s.noSkills,
                ) { s ->
                    CapsRow(
                        title = s.name,
                        subtitle = s.description.ifBlank { s.location },
                        tag = if (s.slash) I18n.s.slashTag else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> CapsList(items: List<T>?, empty: String, row: @Composable (T) -> Unit) {
    when {
        items == null -> Row(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
        items.isEmpty() -> Text(
            empty,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        else -> LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items) { item -> row(item) }
        }
    }
}

@Composable
private fun CapsRow(title: String, subtitle: String, tag: String?) {
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
                Text(title, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            tag?.let {
                Box(
                    Modifier
                        .clip(ClayShapePill)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
