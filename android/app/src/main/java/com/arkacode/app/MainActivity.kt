package com.arkacode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.arkacode.app.core.SessionUiPrefs
import com.arkacode.app.ui.ArkacodeTheme
import com.arkacode.app.ui.EngineScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scope = rememberCoroutineScope()
            val systemDark = isSystemInDarkTheme()
            var themeOverride by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                themeOverride = withContext(Dispatchers.IO) {
                    runCatching { SessionUiPrefs(this@MainActivity).themeMode() }.getOrNull()
                }
            }
            val dark = themeOverride?.let { it == "dark" } ?: systemDark
            ArkacodeTheme(darkTheme = dark) {
                EngineScreen(
                    darkTheme = dark,
                    onToggleTheme = {
                        val next = if (dark) "light" else "dark"
                        themeOverride = next
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    SessionUiPrefs(this@MainActivity).setThemeMode(next)
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
