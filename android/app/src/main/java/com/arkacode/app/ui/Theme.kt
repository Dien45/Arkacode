package com.arkacode.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Claymorphism Arkacode: kartu puffy bersudut besar + bayangan lembut.
 * Terang = putih di atas biru muda. Gelap = slate-navy laut dalam.
 */

private val LightBlue = Color(0xFF2E8BDE)
private val LightBlueDeep = Color(0xFF0B2E4F)
private val LightBluePale = Color(0xFFD3E9FB)
private val LightBg = Color(0xFFE9F2FB)
private val LightSurface = Color(0xFFF4F9FE)
private val LightCard = Color(0xFFFFFFFF)
private val LightInk = Color(0xFF16283A)
private val LightSecondary = Color(0xFF5B8FC4)

private val NightBlue = Color(0xFF7CC4FF)
private val NightBg = Color(0xFF0E1622)
private val NightSurface = Color(0xFF131E2E)
private val NightCard = Color(0xFF1A2638)
private val NightInk = Color(0xFFDCE7F3)

private val ClayLight = lightColorScheme(
    primary = LightBlue,
    onPrimary = Color.White,
    primaryContainer = LightBluePale,
    onPrimaryContainer = LightBlueDeep,
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = LightBluePale,
    onSecondaryContainer = LightBlueDeep,
    tertiary = Color(0xFF2FA37C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3F2E3),
    onTertiaryContainer = Color(0xFF0B3D28),
    background = LightBg,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightBluePale,
    onSurfaceVariant = Color(0xFF33506B),
    surfaceContainerHigh = LightCard,
    surfaceContainerHighest = Color(0xFFE2EEF9),
    outline = Color(0xFFB9D4EC),
)

private val ClayDark = darkColorScheme(
    primary = NightBlue,
    onPrimary = Color(0xFF06283F),
    primaryContainer = Color(0xFF174A75),
    onPrimaryContainer = Color(0xFFD6EBFF),
    secondary = Color(0xFF8FB4D9),
    onSecondary = Color(0xFF06283F),
    secondaryContainer = Color(0xFF1E3A58),
    onSecondaryContainer = Color(0xFFD6EBFF),
    tertiary = Color(0xFF5FCE9E),
    onTertiary = Color(0xFF06281A),
    tertiaryContainer = Color(0xFF14352A),
    onTertiaryContainer = Color(0xFFB9F0D8),
    background = NightBg,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF22314A),
    onSurfaceVariant = Color(0xFFA9C2DA),
    surfaceContainerHigh = NightCard,
    surfaceContainerHighest = Color(0xFF22314A),
    outline = Color(0xFF2E4258),
)

@Composable
fun ArkacodeTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) ClayDark else ClayLight,
        content = content,
    )
}

/** Kartu clay: sudut 20dp + bayangan lembut biru (terang) / dalam (gelap). */
@Composable
fun ClayCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.shadow(
            elevation = 10.dp,
            shape = RoundedCornerShape(20.dp),
            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = { Column(Modifier.padding(14.dp), content = content) },
    )
}
