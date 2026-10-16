package com.arkacode.app

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.arkacode.app.core.ArenaDownloadBridge
import com.arkacode.app.core.SessionUiPrefs
import com.arkacode.app.core.StorageAccess
import com.arkacode.app.ui.ArkacodeTheme
import com.arkacode.app.ui.EngineScreen
import com.arkacode.app.ui.I18n
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        autoImportDownloads()
    }

    /**
     * Saat pengguna kembali ke app (mis. dari Chrome Custom Tab Arena AI),
     * pindai /Download dan impor ZIP workspace ke proot workspace secara
     * otomatis. Hanya ZIP agar berkas lain di Downloads tidak ikut tersedot.
     */
    private fun autoImportDownloads() {
        if (!StorageAccess.hasAllFilesAccess()) return
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching {
                    ArenaDownloadBridge.importDownloadsToWorkspace(
                        downloadsDir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ),
                        workspaceDir = File(filesDir, "workspace"),
                        onlyZip = true,
                    )
                }.getOrNull()
            }
            if (summary?.changed == true) {
                Toast.makeText(
                    this@MainActivity,
                    I18n.s.arenaImportResult(summary.copied, summary.extractedFiles),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

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
            // Ikon & scrim status/nav bar harus mengikuti TEMA APLIKASI, bukan
            // tebakan uiMode sistem (enableEdgeToEdge() polos di onCreate bisa
            // salah arah ketika tema aplikasi ≠ tema sistem).
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                )
            }
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
