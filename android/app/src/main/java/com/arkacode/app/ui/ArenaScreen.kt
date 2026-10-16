package com.arkacode.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.content.Context
import androidx.compose.ui.platform.LocalContext

class ArenaScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = fillMaxSize()) {
                    AndroidView(
                        factory = { context: Context -> WebView(context) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
