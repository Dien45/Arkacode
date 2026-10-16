package com.arkacode.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolusi rilis `opencode` di GitHub untuk fitur pembaruan otomatis (F-update).
 *
 * Kenapa tidak mengandalkan auto-update bawaan opencode?
 *  - Binary di sini dijalankan di dalam proot dan di-bind ke `/opt/arkacode`;
 *    auto-update bawaan butuh deteksi metode instalasi + tulis ulang berkas
 *    (rawan di proot, dan bisa bentrok dengan pengelolaan app). Karena itu app
 *    mematikan auto-update bawaan (OPENCODE_DISABLE_AUTOUPDATE=1) dan mengelola
 *    pembaruan sendiri lewat modul ini.
 *
 * Sumber data:
 *  - Tag terbaru: HTTP 302 `github.com/<repo>/releases/latest` → `Location`
 *    memuat `/tag/<versi>`. Tanpa API → tidak kena batas 60 req/jam GitHub.
 *  - Digest sha256 per-aset: REST API `releases/tags/<tag>` (field `digest`,
 *    format `sha256:<hex>`) — dipakai untuk verifikasi unduhan (tidak ada
 *    berkas checksum terpisah di rilis opencode).
 */
object EngineRelease {
    private const val REPO = "anomalyco/opencode"
    private const val RELEASES_LATEST = "https://github.com/$REPO/releases/latest"
    private const val API_RELEASE_BY_TAG = "https://api.github.com/repos/$REPO/releases/tags/"
    const val DOWNLOAD_BASE = "https://github.com/$REPO/releases/download/"
    private const val UA = "arkacode-android"

    /** Aset rilis: nama berkas → sha256 hex (atau null bila API tak menyertakan digest). */
    data class Release(val tag: String, val assets: Map<String, String?>)

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------- versioning

    /** Ekstrak inti versi numerik: "v1.19.1" / "opencode 1.19.1" → [1, 19, 1]. */
    fun versionCore(v: String): List<Int> =
        Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    /** true bila [latest] lebih baru dari [installed] (installed null → dianggap perlu update). */
    fun isNewer(latest: String, installed: String?): Boolean {
        val a = versionCore(latest)
        val b = installed?.let { versionCore(it) } ?: return a.isNotEmpty()
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** true bila dua string menunjuk versi numerik yang sama (tag vs output --version). */
    fun sameVersion(a: String, b: String): Boolean =
        versionCore(a) == versionCore(b) && versionCore(a).isNotEmpty()

    /** Rapikan tag jadi berawalan "v" (mis. "1.19.1" → "v1.19.1"). */
    fun normalizeTag(tag: String): String {
        val t = tag.trim().trim('/')
        return if (t.startsWith("v")) t else "v$t"
    }

    /**
     * Versi tanpa awalan "v" untuk tampilan: UI menambahkan "v" sendiri
     * (lihat [normalizeTag] untuk kebalikannya). Menjaga agar nilai dari tag
     * ("v1.19.1") maupun dari `--version` ("1.19.1") tampil konsisten.
     */
    fun bare(v: String): String {
        val t = v.trim()
        return if (t.startsWith("v") || t.startsWith("V")) t.substring(1) else t
    }

    // ------------------------------------------------------------ aset / ABI

    /**
     * Kandidat nama aset berurutan (musl dulu karena Alpine, lalu gnu) untuk ABI
     * perangkat. `armeabi-v7a` tidak disediakan upstream → fallback arm64.
     */
    fun assetCandidates(abi: String): List<String> {
        val a = abi.lowercase()
        return when {
            a.startsWith("arm64") || a.contains("aarch64") -> listOf(
                "opencode-linux-arm64-musl.tar.gz",
                "opencode-linux-arm64.tar.gz",
            )
            a.startsWith("x86_64") || a.startsWith("x64") -> listOf(
                "opencode-linux-x64-musl.tar.gz",
                "opencode-linux-x64.tar.gz",
                "opencode-linux-x64-baseline-musl.tar.gz",
                "opencode-linux-x64-baseline.tar.gz",
            )
            else -> listOf(
                "opencode-linux-arm64-musl.tar.gz",
                "opencode-linux-arm64.tar.gz",
            )
        }
    }

    fun downloadUrl(tag: String, asset: String): String = "$DOWNLOAD_BASE$tag/$asset"

    // -------------------------------------------------------------- networking

    /** Tag rilis terbaru via redirect (tanpa API). Null bila offline / bentuk tak dikenal. */
    fun latestTag(timeoutMs: Int = 20_000): String? {
        val conn = (URL(RELEASES_LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        return try {
            conn.responseCode // memicu koneksi
            val loc = conn.getHeaderField("Location")
            loc?.substringAfterLast("/tag/")?.trim()?.trim('/')
                ?.takeIf { it.isNotBlank() }
                ?.let { normalizeTag(it) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Digest sha256 per aset untuk [tag] via API. Null bila API tak terjangkau. */
    fun releaseAssets(tag: String, timeoutMs: Int = 20_000): Map<String, String?>? {
        val conn = (URL(API_RELEASE_BY_TAG + tag).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            val root = json.parseToJsonElement(text) as? JsonObject ?: return null
            val arr = root["assets"] as? JsonArray ?: return emptyMap()
            val out = LinkedHashMap<String, String?>()
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: continue
                val digest = (o["digest"] as? JsonPrimitive)?.contentOrNull
                out[name] = digest?.removePrefix("sha256:")?.lowercase()
            }
            out
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Resolusi penuh (tag + digest). Null bila tag tak terbaca. */
    fun latestRelease(timeoutMs: Int = 20_000): Release? {
        val tag = latestTag(timeoutMs) ?: return null
        return Release(tag, releaseAssets(tag, timeoutMs) ?: emptyMap())
    }
}
