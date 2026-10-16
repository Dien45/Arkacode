package com.arkacode.app.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Layar WebView bawaan untuk [https://arena.ai/](https://arena.ai/).
 *
 * - Muat website Arena AI asli (tanpa API key, login lewat browser).
 * - Cookie & localStorage disimpan internal WebView → sesi login tetap
 *   walau aplikasi ditutup dan dibuka lagi.
 * - Tangkap unduhan (download) → simpan ke folder /Download Android.
 *   Hasil unduhan nanti diimpor ke workspace proot via [ArenaDownloadBridge].
 */
class ArenaScreenActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ArenaWebView()
                }
            }
        }
    }
}

@Composable
fun ArenaWebView() {
    val context = LocalContext.current

    val webView = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true        // localStorage / IndexedDB
                databaseEnabled = true
                setAppCacheEnabled(true)
                setAppCacheDir(context.cacheDir.absolutePath)
                setCacheMode(WebSettings.LOAD_DEFAULT)
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            // Cookie agar login Arena AI tidak hilang saat tutup buka aplikasi.
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this@ArenaWebView, true)
            cookieManager.flush()

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest?
                ): Boolean {
                    // Biarkan WebView muat URL itu sendiri (tidak buka browser luar).
                    return false
                }

                override fun onReceivedError(
                    view: WebView,
                    errorCode: Int,
                    description: String,
                    failingUrl: String
                ) {
                    Toast.makeText(
                        context,
                        "Gagal memuat Arena AI: $description",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // === TANGKAP DOWNLOAD ===
            setDownloadListener(object : android.webkit.DownloadListener {
                override fun onDownloadStart(
                    url: String,
                    userAgent: String?,
                    contentDisposition: String?,
                    mimeType: String?,
                    contentLength: Long
                ) {
                    val uri = Uri.parse(url)
                    val fileName = uri.lastPathSegment ?: "arena-download"

                    val request = DownloadManager.Request(uri)
                        .setMimeType(mimeType ?: "*/*")
                        .setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName
                        )
                        .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        .setTitle("Arena AI Download")
                        .setDescription("Mengunduh $fileName")

                    val manager =
                        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(request)

                    Toast.makeText(
                        context,
                        "Unduhan dimulai: $fileName",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })

            loadUrl("https://arena.ai/")
        }
    }

    // Tombol back Android → kembali ke halaman sebelumnya di WebView.
    // Jika sudah di halaman pertama, keluar layar Arena.
    onBackPressedDispatcherOwner?.onBackPressedDispatcher?.addCallback {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize()
    )
}
