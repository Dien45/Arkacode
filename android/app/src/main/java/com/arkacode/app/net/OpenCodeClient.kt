package com.arkacode.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

data class ServerHealth(val healthy: Boolean, val version: String)

/**
 * Klien HTTP untuk `opencode serve` v1.19 (`/api/...`, skema dari tag sumber).
 * Semua panggilan cancellable (Stop/abort benar-benar memutus HTTP).
 * Parsing longgar: field tak dikenal diabaikan, bentuk tak dikenal → dilewati.
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

    /** Client sabar untuk long-poll `wait` (idle bisa makan waktu menit). */
    private val longClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private fun authHeader(): String = Credentials.basic(username, password)

    // ------------------------------------------------------------- transport

    private suspend fun call(req: Request, useLongTimeout: Boolean = false): JsonElement =
        suspendCancellableCoroutine { cont ->
            val call = (if (useLongTimeout) longClient else client).newCall(req)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val text = resp.body?.string() ?: ""
                        if (!cont.isActive) return
                        if (!resp.isSuccessful) {
                            cont.resumeWithException(
                                IllegalStateException("HTTP ${resp.code}: ${text.take(300)}"),
                            )
                            return
                        }
                        val el = runCatching { json.parseToJsonElement(text) }
                            .getOrElse { JsonPrimitive(text) }
                        cont.resume(el) {}
                    }
                }
            })
        }

    private fun getReq(path: String): Request = Request.Builder()
        .url(baseUrl + path)
        .header("Authorization", authHeader())
        .build()

    private fun postReq(path: String, bodyJson: String?): Request {
        val body = (bodyJson ?: "").toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", authHeader())
            .post(body)
            .build()
    }

    // ------------------------------------------------------------- helpers

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.arr(key: String): JsonArray =
        (this[key] as? kotlinx.serialization.json.JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.obj(key: String): JsonObject =
        (this[key] as? JsonObject) ?: JsonObject(emptyMap())

    /** Ambil array `data`, toleran bila server mengembalikan array telanjang. */
    private fun JsonElement.dataArray(): JsonArray = when (this) {
        is JsonObject -> arr("data")
        is JsonArray -> this
        else -> JsonArray(emptyList())
    }

    // ------------------------------------------------------------- endpoints

    suspend fun health(): ServerHealth = withContext(Dispatchers.IO) {
        val el = call(getReq("/global/health"))
        val o = el as? JsonObject ?: JsonObject(emptyMap())
        ServerHealth(
            healthy = o["healthy"]?.jsonPrimitive?.booleanOrNull == true,
            version = o.str("version") ?: "?",
        )
    }

    private fun encodeRelPath(path: String): String =
        path.split("/").filter { it.isNotEmpty() }
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

    /** Daftar isi satu direktori ("" = root working dir server). */
    suspend fun listFiles(path: String = ""): List<FsEntry> = withContext(Dispatchers.IO) {
        val q = if (path.isBlank()) "" else "?path=" + encodeRelPath(path)
        val el = call(getReq("/api/fs/list$q"))
        val data = (el as? JsonObject)?.let { it.dataArray() } ?: return@withContext emptyList()
        data.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val rel = o.str("path") ?: return@mapNotNull null
            FsEntry(rel, o.str("type") == "directory")
        }
    }

    /** Baca file (teks, dipotong maxChars). */
    suspend fun readFile(path: String, maxChars: Int = 100_000): String = withContext(Dispatchers.IO) {
        val bytes = callBytes(getReq("/api/fs/read/" + encodeRelPath(path)))
        String(bytes, Charsets.UTF_8).take(maxChars)
    }

    /** Baca file mentah (untuk preview gambar). */
    suspend fun readFileBytes(path: String, maxBytes: Int = 8_000_000): ByteArray = withContext(Dispatchers.IO) {
        val bytes = callBytes(getReq("/api/fs/read/" + encodeRelPath(path)))
        if (bytes.size > maxBytes) throw IllegalStateException("File terlalu besar untuk preview")
        bytes
    }

    /** Cari file/direktori rekursif. */
    suspend fun findFiles(query: String, limit: Int = 50): List<FsEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val el = call(getReq("/api/fs/find?query=" + java.net.URLEncoder.encode(query, "UTF-8") + "&limit=$limit"))
        val data = (el as? JsonObject)?.let { it.dataArray() } ?: return@withContext emptyList()
        data.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val rel = o.str("path") ?: return@mapNotNull null
            FsEntry(rel, o.str("type") == "directory")
        }
    }

    private suspend fun callBytes(req: Request): ByteArray = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!cont.isActive) return
                    if (!resp.isSuccessful) {
                        cont.resumeWithException(IllegalStateException("HTTP ${resp.code}"))
                        return
                    }
                    cont.resume(resp.body?.bytes() ?: ByteArray(0)) {}
                }
            }
        })
    }

    /**
     * Aliran event server (SSE `/api/event`) — trigger mentah untuk refresh.
     * Berhenti saat collector dibatalkan; error koneksi mengakhiri diam-diam
     * (polling 2 dtk tetap jadi jaring pengaman).
     */
    fun serverEvents(): Flow<String> = callbackFlow {
        val req = Request.Builder()
            .url(baseUrl + "/api/event")
            .header("Authorization", authHeader())
            .build()
        val call = client.newCall(req)
        try {
            val resp = call.execute()
            if (!resp.isSuccessful) {
                resp.close()
                close()
                return@callbackFlow
            }
            val reader = resp.body?.byteStream()?.bufferedReader()
            if (reader == null) {
                resp.close()
                close()
                return@callbackFlow
            }
            try {
                var eventName = ""
                val data = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isBlank() -> {
                            if (data.isNotEmpty()) {
                                trySend(eventName.ifBlank { "message" } + " " + data.toString().take(160))
                            }
                            eventName = ""
                            data.clear()
                        }
                        line.startsWith(":") -> {}
                        line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append("\n")
                            data.append(line.removePrefix("data:").trim())
                        }
                    }
                }
            } finally {
                runCatching { resp.close() }
            }
        } catch (e: Exception) {
            // koneksi putus — diam, polling yang ambil alih
        }
        awaitClose { runCatching { call.cancel() } }
    }.flowOn(Dispatchers.IO)

    /** GET mentah untuk debug. */
    suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val el = call(getReq(path))
        el.toString()
    }

    suspend fun listSessions(limit: Int = 50): List<ChatSession> = withContext(Dispatchers.IO) {
        val el = call(getReq("/api/session?limit=$limit&order=desc"))
        el.dataArray().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val model = o.obj("model")
            ChatSession(
                id = id,
                title = o.str("title") ?: "",
                providerID = model.str("providerID"),
                modelID = model.str("id"),
            )
        }
    }

    suspend fun createSession(): ChatSession = withContext(Dispatchers.IO) {
        val el = call(postReq("/api/session", "{}"))
        val o = (el as? JsonObject)?.obj("data") ?: JsonObject(emptyMap())
        val id = o.str("id") ?: throw IllegalStateException("Server tidak mengembalikan id sesi")
        ChatSession(id = id, title = o.str("title") ?: "")
    }

    suspend fun listMessages(sessionId: String, limit: Int = 200): List<ChatItem> =
        withContext(Dispatchers.IO) {
            val el = call(getReq("/api/session/$sessionId/message?limit=$limit&order=asc"))
            el.dataArray().mapNotNull { item -> parseMessage(item) }
        }

    private fun parseMessage(item: JsonElement): ChatItem? {
        val o = item as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        return when (o.str("type")) {
            "user" -> {
                val files = o.arr("files").size
                val text = o.str("text") ?: ""
                if (text.isBlank() && files == 0) null
                else ChatItem.User(id, text, files)
            }
            "assistant" -> {
                val texts = mutableListOf<String>()
                val reasonings = mutableListOf<String>()
                val tools = mutableListOf<ToolUse>()
                for (part in o.arr("content")) {
                    val p = part as? JsonObject ?: continue
                    when (p.str("type")) {
                        "text" -> p.str("text")?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                        "reasoning" -> p.str("text")?.takeIf { it.isNotBlank() }?.let { reasonings.add(it) }
                        "tool" -> {
                            val st = p.obj("state").str("status") ?: ""
                            tools.add(ToolUse(p.str("id") ?: "", p.str("name") ?: "tool", st))
                        }
                    }
                }
                if (texts.isEmpty() && reasonings.isEmpty() && tools.isEmpty()) null
                else ChatItem.Assistant(id, texts, reasonings, tools)
            }
            "synthetic", "system" -> {
                val text = o.str("text") ?: o.str("description") ?: return null
                if (text.isBlank()) null else ChatItem.Note(id, text)
            }
            else -> null
        }
    }

    /** Kirim pesan (async di server — return saat admit, bukan saat selesai). */
    suspend fun sendPrompt(sessionId: String, text: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("prompt", buildJsonObject { put("text", text) }) }
        val el = call(postReq("/api/session/$sessionId/prompt", body.toString()))
        val o = (el as? JsonObject)?.obj("data") ?: JsonObject(emptyMap())
        o.str("id") ?: ""
    }

    /** Tunggu loop agent idle (long-poll; cancel = Stop). */
    suspend fun waitIdle(sessionId: String) {
        call(postReq("/api/session/$sessionId/wait", null), useLongTimeout = true)
    }

    /** Hentikan turn yang jalan. */
    suspend fun interrupt(sessionId: String) {
        runCatching { call(postReq("/api/session/$sessionId/interrupt", null)) }
    }

    suspend fun listPermissions(sessionId: String): List<PermissionReq> =
        withContext(Dispatchers.IO) {
            val el = runCatching { call(getReq("/api/session/$sessionId/permission")) }
                .getOrNull() ?: return@withContext emptyList()
            val data = (el as? JsonObject)?.let { it.dataArray() } ?: return@withContext emptyList()
            data.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                val summary = buildString {
                    o.str("action")?.let { append(it) }
                    o.str("tool")?.let { if (isNotEmpty()) append(" ") else Unit; append(it) }
                    val rest = o.entries
                        .filter { (k, v) -> k != "id" && v.toString().length < 200 }
                        .joinToString(" ") { (k, v) -> "$k=${v.toString().trim('\"').take(80)}" }
                    if (rest.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(rest.take(280))
                    }
                }.ifBlank { "Permintaan persetujuan" }
                PermissionReq(id, summary)
            }
        }

    /** reply: "once" | "always" | "reject" (always = allow-all bawaan opencode). */
    suspend fun replyPermission(sessionId: String, requestId: String, reply: String) {
        val body = buildJsonObject { put("reply", reply) }
        call(postReq("/api/session/$sessionId/permission/$requestId/reply", body.toString()))
    }

    /** Ganti model sesi (server-side; pengganti pin model Arka). */
    suspend fun switchModel(sessionId: String, providerID: String, modelID: String) {
        val body = buildJsonObject {
            put("model", buildJsonObject {
                put("id", modelID)
                put("providerID", providerID)
            })
        }
        call(postReq("/api/session/$sessionId/model", body.toString()))
    }

    /** Semua model flat dari server (sumber kebenaran untuk picker). */
    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val el = runCatching { call(getReq("/api/model")) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = (el as? JsonObject)?.let { it.dataArray() }
            ?: (el as? JsonArray) ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val mid = o.str("id") ?: return@mapNotNull null
            val pid = o.str("providerID") ?: return@mapNotNull null
            val costs = o.arr("cost")
            val free = costs.isNotEmpty() && costs.all { c ->
                val co = c as? JsonObject ?: return@all false
                val inp = (co["input"] as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
                val out = (co["output"] as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
                !inp.isNaN() && !out.isNaN() && inp == 0.0 && out == 0.0
            }
            ModelInfo(mid, o.str("name") ?: mid, pid, free)
        }
    }

    suspend fun listProviders(): List<ProviderInfo> = withContext(Dispatchers.IO) {
        val el = runCatching { call(getReq("/api/provider")) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = (el as? JsonObject)?.let { it.dataArray() }
            ?: (el as? JsonArray) ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: o.str("name") ?: return@mapNotNull null
            ProviderInfo(id, o.str("name") ?: id, emptyList())
        }
    }
}
