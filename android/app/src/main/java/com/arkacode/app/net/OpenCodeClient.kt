package com.arkacode.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ServerHealth(val healthy: Boolean, val version: String)
data class SessionInfo(val id: String, val title: String)

/**
 * Klien HTTP minimal untuk `opencode serve` (F0: health + GET mentah).
 * Auth: HTTP basic (username default "opencode", password dari env server).
 */
class OpenCodeClient(
    port: Int = 4096,
    private val username: String = "opencode",
    private val password: String = "",
) {
    private val baseUrl = "http://127.0.0.1:$port"
    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun authHeader(): String = Credentials.basic(username, password)

    suspend fun health(): ServerHealth = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/global/health")
            .header("Authorization", authHeader())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Server menjawab HTTP ${resp.code}")
            val obj = json.parseToJsonElement(resp.body?.string() ?: "").jsonObject
            ServerHealth(
                healthy = obj["healthy"]?.jsonPrimitive?.booleanOrNull == true,
                version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "?",
            )
        }
    }

    /** Daftar sesi di server (id + judul, parsing longgar). */
    suspend fun listSessions(): List<SessionInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/session")
            .header("Authorization", authHeader())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            runCatching {
                json.parseToJsonElement(resp.body?.string() ?: "").jsonArray.mapNotNull { el ->
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    SessionInfo(id, o["title"]?.jsonPrimitive?.contentOrNull ?: "")
                }
            }.getOrDefault(emptyList())
        }
    }

    /** GET mentah untuk eksplorasi endpoint dari layar debug. */
    suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", authHeader())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} untuk $path")
            resp.body?.string() ?: ""
        }
    }
}
