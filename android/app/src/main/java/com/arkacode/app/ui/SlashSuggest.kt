package com.arkacode.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkacode.app.core.SlashSuggestion

/**
 * Panel saran slash command yang muncul di atas kolom input saat pengguna
 * mengetik `/…`. Gaya clay konsisten dengan komponen lain.
 */
@Composable
fun SlashSuggestList(
    suggestions: List<SlashSuggestion>,
    onSelect: (SlashSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    ClaySurface(
        modifier = modifier.fillMaxWidth(),
        shape = ClayShapeLarge,
        elevation = 14.dp,
    ) {
        Column(Modifier.padding(6.dp)) {
            suggestions.forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ClayShapeSmall)
                        .clickable { onSelect(s) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "/" + s.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        s.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (s.isSkill) {
                        Text(
                            I18n.s.tabSkills,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
