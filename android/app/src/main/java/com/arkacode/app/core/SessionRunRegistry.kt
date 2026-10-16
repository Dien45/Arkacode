package com.arkacode.app.core

import androidx.compose.runtime.mutableStateOf
import com.arkacode.app.net.ChatItem
import com.arkacode.app.net.ModelRef
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.PermissionReq
import com.arkacode.app.net.QuestionRequest
import com.arkacode.app.net.ServerEvent
import com.arkacode.app.net.ToolUse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Status "turn berjalan" untuk SATU sesi. Dimiliki [SessionRunRegistry] yang
 * di-scope ke layar engine, sehingga status tetap hidup saat layar chat dibuang
 * (mis. pengguna pindah sesi) — turn di server tidak lagi "berhenti" di UI.
 */
class SessionRun {
    val busy = mutableStateOf(false)
    val messages = mutableStateOf<List<ChatItem>>(emptyList())
    val pending = mutableStateOf<List<PermissionReq>>(emptyList())
    val questions = mutableStateOf<List<QuestionRequest>>(emptyList())
    val liveText = mutableStateOf("")
    val liveReasoning = mutableStateOf("")
    val liveTools = mutableStateOf<List<ToolUse>>(emptyList())
    val toolLabel = mutableStateOf<String?>(null)
    val justSent = mutableStateOf<String?>(null)
    val error = mutableStateOf<String?>(null)

    internal var pollJob: Job? = null
    internal var preIds: Set<String> = emptySet()
    internal var attached = false
}

/**
 * Registry run per sesi. Polling hidup selama [scope] (layar engine), bukan
 * selama layar chat, jadi berpindah sesi tidak membuang pelacakan turn yang
 * masih berjalan. Penyelesaian turn dideteksi dari medan `finish` pesan
 * assistant (server v1.19 tidak mengirim event idle).
 */
class SessionRunRegistry(
    private val scope: CoroutineScope,
    private val client: OpenCodeClient,
    /** Rootfs Alpine di disk app — untuk men-tail file stdout shell (`.out`) realtime. */
    private val rootfsDir: File? = null,
) {
    private val runs = LinkedHashMap<String, SessionRun>()
    private var eventsJob: Job? = null

    /** Job tail output shell, kunci `"<sessionId>/<toolId>"`. */
    private val tails = HashMap<String, Job>()

    fun run(sessionId: String): SessionRun = runs.getOrPut(sessionId) { SessionRun() }

    /** Muat awal + sambung kembali ke turn yang masih berjalan di server. */
    fun attach(sessionId: String) {
        val r = run(sessionId)
        ensureEvents()
        if (r.attached) return
        r.attached = true
        scope.launch {
            runCatching { client.listMessages(sessionId) }.getOrNull()?.let { r.messages.value = it }
            runCatching { client.listPermissions(sessionId) }.getOrNull()?.let { r.pending.value = it }
            runCatching { client.listQuestions(sessionId) }.getOrNull()?.let { r.questions.value = it }
            val active = runCatching { client.sessionActive() }.getOrDefault(emptySet())
            if (sessionId in active && !r.busy.value) {
                r.busy.value = true
                startPolling(sessionId)
            }
        }
    }

    fun refresh(sessionId: String) {
        val r = run(sessionId)
        scope.launch {
            runCatching { client.listMessages(sessionId) }.getOrNull()?.let { r.messages.value = it }
            runCatching { client.listPermissions(sessionId) }.getOrNull()?.let { r.pending.value = it }
            runCatching { client.listQuestions(sessionId) }.getOrNull()?.let { r.questions.value = it }
        }
    }

    /**
     * Satu kolektor SSE untuk semua sesi: mengubah event server (tool, teks
     * streaming, pertanyaan, persetujuan, idle) menjadi update state seketika,
     * melengkapi polling sebagai jaring pengaman.
     */
    private fun ensureEvents() {
        if (eventsJob?.isActive == true) return
        eventsJob = scope.launch {
            // Sambung ulang otomatis bila koneksi SSE putus: tanpa ini streaming
            // berhenti diam-diam dan balasan hanya mengandalkan polling.
            while (isActive) {
                try {
                    client.serverEvents().collect { ev -> applyEvent(ev) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // koneksi SSE putus — coba sambung lagi di bawah
                }
                if (!isActive) break
                delay(SSE_RETRY_MS)
            }
        }
    }

    /** Terapkan satu event SSE ke state run terkait. */
    internal fun applyEvent(ev: ServerEvent) {
        // shell.exited tidak membawa sessionID — cocokkan lewat shellId ke semua run.
        if (ev is ServerEvent.Tool && ev.sessionID == null && ev.shellId != null) {
            for ((sidKey, r) in runs) if (applyShellExit(r, sidKey, ev)) return
            return
        }
        val sid = ev.sessionID ?: return
        if (!runs.containsKey(sid)) return
        val r = run(sid)
        when (ev) {
                    is ServerEvent.Tool -> applyTool(sid, r, ev)
                    is ServerEvent.TextDelta -> r.liveText.value += ev.delta
                    is ServerEvent.TextEnded -> if (ev.text.isNotBlank()) r.liveText.value = ev.text
                    is ServerEvent.ReasoningDelta -> r.liveReasoning.value += ev.delta
                    is ServerEvent.Permission -> scope.launch {
                        runCatching { client.listPermissions(sid) }.getOrNull()?.let { r.pending.value = it }
                    }
                    is ServerEvent.Question -> scope.launch {
                        runCatching { client.listQuestions(sid) }.getOrNull()?.let { r.questions.value = it }
                    }
                    is ServerEvent.Status -> if (ev.status == "idle" && r.busy.value) {
                        r.busy.value = false
                        r.justSent.value = null
                        r.toolLabel.value = null
                        r.liveTools.value = emptyList()
                        stopTails(sid)
                    }
                    is ServerEvent.Error -> r.error.value = ev.message
                    else -> {}
                }
    }

    /** Gabungkan event tool ke daftar [SessionRun.liveTools] (per id call). */
    internal fun applyTool(sid: String, r: SessionRun, ev: ServerEvent.Tool) {
        if (ev.label.isNotBlank()) r.toolLabel.value = ev.label
        // shell.created: tempelkan file stdout ke tool shell yang sedang jalan lalu mulai tail.
        if (ev.shellFile != null) {
            attachShell(sid, r, ev)
            return
        }
        // shell.exited tanpa status/output/detail: tandai selesai + hentikan tail.
        if (ev.shellId != null && ev.status.isBlank() && ev.detail == null && ev.output == null && ev.id.isBlank()) {
            applyShellExit(r, sid, ev)
            return
        }
        if (ev.id.isBlank() && ev.label.isBlank() && ev.status.isBlank() &&
            ev.detail == null && ev.output == null
        ) return
        val key = ev.id.ifBlank { "live" }
        val cur = r.liveTools.value.toMutableList()
        val i = cur.indexOfFirst { it.id == key }
        cur.setOrAdd(i, merge(cur.getOrNull(i), ev))
        r.liveTools.value = cur
        if (cur[if (i >= 0) i else cur.size - 1].status in TERMINAL_TOOL) stopTail(sid, key)
    }

    private fun MutableList<ToolUse>.setOrAdd(index: Int, value: ToolUse) {
        if (index >= 0) this[index] = value else add(value)
    }

    /** shell.created → tempel path file `.out` ke tool shell aktif lalu tail live. */
    private fun attachShell(sid: String, r: SessionRun, ev: ServerEvent.Tool) {
        val cur = r.liveTools.value.toMutableList()
        val i = cur.indexOfLast { it.status == "running" && it.shellFile == null }
        val attached = if (i >= 0) {
            cur[i] = cur[i].copy(
                name = cur[i].name.ifBlank { "shell" },
                detail = ev.detail ?: cur[i].detail,
                shellId = ev.shellId,
                shellFile = ev.shellFile,
            )
            cur[i]
        } else {
            ToolUse(
                id = "shell-" + (ev.shellId ?: System.currentTimeMillis().toString()),
                name = "shell",
                status = "running",
                detail = ev.detail,
                shellId = ev.shellId,
                shellFile = ev.shellFile,
            ).also { cur.add(it) }
        }
        r.liveTools.value = cur
        startTail(sid, r, attached)
    }

    /** Cocokkan shell.exited ke tool lewat shellId; true bila ketemu. */
    private fun applyShellExit(r: SessionRun, sid: String, ev: ServerEvent.Tool): Boolean {
        val shellId = ev.shellId ?: return false
        val cur = r.liveTools.value.toMutableList()
        val i = cur.indexOfFirst { it.shellId == shellId }
        if (i < 0) return false
        cur[i] = cur[i].copy(exit = ev.exit ?: cur[i].exit)
        r.liveTools.value = cur
        stopTail(sid, cur[i].id)
        return true
    }

    /** Gabungkan event baru dengan entri lama (field kosong tidak menimpa). */
    private fun merge(old: ToolUse?, ev: ServerEvent.Tool): ToolUse = ToolUse(
        id = ev.id.ifBlank { old?.id ?: "live" },
        name = ev.label.ifBlank { old?.name ?: "tool" },
        status = ev.status.ifBlank { old?.status ?: "running" },
        detail = ev.detail ?: old?.detail,
        output = ev.output ?: old?.output ?: "",
        exit = ev.exit ?: old?.exit,
        truncated = ev.truncated || (old?.truncated ?: false),
        shellId = ev.shellId ?: old?.shellId,
        shellFile = ev.shellFile ?: old?.shellFile,
    )

    /**
     * Tail file stdout shell (`.out`) dari disk app: file tumbuh seiring proses
     * menulis, jadi membaca berulang memberi output realtime tanpa event server.
     */
    private fun startTail(sid: String, r: SessionRun, tool: ToolUse) {
        val file = tool.shellFile ?: return
        val root = rootfsDir ?: return
        val key = "$sid/${tool.id}"
        if (tails[key]?.isActive == true) return
        val host = File(root, file.trimStart('/'))
        tails[key] = scope.launch {
            var lastLen = -1
            while (isActive) {
                val txt = runCatching { host.readText() }.getOrNull()
                if (txt != null && txt.length != lastLen) {
                    lastLen = txt.length
                    updateToolOutput(r, tool.id, txt)
                }
                delay(TAIL_MS)
            }
        }
    }

    private fun updateToolOutput(r: SessionRun, toolId: String, text: String) {
        val cur = r.liveTools.value.toMutableList()
        val i = cur.indexOfFirst { it.id == toolId }
        if (i < 0 || cur[i].output == text) return
        cur[i] = cur[i].copy(output = text)
        r.liveTools.value = cur
    }

    private fun stopTail(sid: String, toolId: String) {
        tails.remove("$sid/$toolId")?.cancel()
    }

    private fun stopTails(sid: String) {
        tails.keys.filter { it.startsWith("$sid/") }.forEach { tails.remove(it)?.cancel() }
    }

    fun clearError(sessionId: String) {
        run(sessionId).error.value = null
    }

    /** Kirim prompt (+ lampiran). `false` bila kosong & tanpa lampiran, atau sibuk. */
    fun send(
        sessionId: String,
        text: String,
        model: ModelRef?,
        files: List<Attachment> = emptyList(),
    ): Boolean {
        val trimmed = text.trim()
        val r = run(sessionId)
        if ((trimmed.isEmpty() && files.isEmpty()) || r.busy.value) return false
        r.error.value = null
        r.busy.value = true
        r.justSent.value = trimmed.ifBlank { null }
        r.liveText.value = ""
        r.liveReasoning.value = ""
        r.liveTools.value = emptyList()
        r.toolLabel.value = null
        r.preIds = snapshotPreIds(r.messages.value)
        stopTails(sessionId)
        r.pollJob?.cancel()
        r.pollJob = scope.launch {
            try {
                model?.let { m -> runCatching { client.switchModel(sessionId, m.providerID, m.id) } }
                client.sendPrompt(sessionId, trimmed, files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                r.error.value = e.message
                r.busy.value = false
                r.justSent.value = null
                return@launch
            }
            pollUntilDone(sessionId)
        }
        return true
    }

    /** Hentikan turn yang jalan (juga membatalkan polling). */
    fun stop(sessionId: String) {
        val r = run(sessionId)
        r.pollJob?.cancel()
        r.busy.value = false
        r.liveText.value = ""
        r.liveReasoning.value = ""
        r.liveTools.value = emptyList()
        r.toolLabel.value = null
        r.justSent.value = null
        stopTails(sessionId)
        scope.launch {
            runCatching { client.interrupt(sessionId) }
            runCatching { client.listMessages(sessionId) }.getOrNull()?.let { r.messages.value = it }
        }
    }

    private fun startPolling(sessionId: String) {
        val r = run(sessionId)
        if (r.pollJob?.isActive == true) return
        r.preIds = snapshotPreIds(r.messages.value)
        r.pollJob = scope.launch { pollUntilDone(sessionId) }
    }

    private suspend fun pollUntilDone(sessionId: String) {
        val r = run(sessionId)
        var waited = 0L
        var fetchFails = 0
        var lastErr: String? = null
        try {
            while (true) {
                val loaded = try {
                    client.listMessages(sessionId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e.message
                    null
                }
                if (loaded != null) {
                    r.messages.value = loaded
                    fetchFails = 0
                } else if (++fetchFails >= MAX_FETCH_FAILS) {
                    r.error.value = lastErr ?: "Tidak dapat memuat pesan dari server"
                }
                runCatching { client.listPermissions(sessionId) }.getOrNull()?.let { r.pending.value = it }
                runCatching { client.listQuestions(sessionId) }.getOrNull()?.let { r.questions.value = it }
                val newAssistant = r.messages.value
                    .filterIsInstance<ChatItem.Assistant>()
                    .lastOrNull { it.id !in r.preIds }
                if (newAssistant != null && isTerminal(newAssistant.finish)) {
                    // Tunggu singkat sampai server menuntaskan penyimpanan isi
                    // balasan — menghindari bubble kosong karena pesan tersimpan
                    // belum lengkap tepat saat `finish` muncul.
                    var settle = 0
                    var asst: ChatItem.Assistant = newAssistant
                    while (settle < SETTLE_TRIES && !hasBody(asst)) {
                        delay(SETTLE_MS)
                        runCatching { client.listMessages(sessionId) }.getOrNull()
                            ?.let { r.messages.value = it }
                        asst = r.messages.value
                            .filterIsInstance<ChatItem.Assistant>()
                            .lastOrNull { it.id !in r.preIds } ?: asst
                        settle++
                    }
                    asst.error?.takeIf { it.isNotBlank() }?.let { r.error.value = it }
                    break
                }
                if (waited >= MAX_POLL_MS) break
                delay(POLL_MS)
                waited += POLL_MS
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            r.busy.value = false
            r.justSent.value = null
            r.toolLabel.value = null
            r.liveTools.value = emptyList()
            stopTails(sessionId)
            // liveText sengaja TIDAK dibersihkan di sini: ChatScreen memakainya
            // sebagai cadangan bila pesan tersimpan tak memuat teks balasan.
            runCatching { client.listMessages(sessionId) }.getOrNull()?.let { r.messages.value = it }
        }
    }

    /** Pesan assistant punya isi (teks/reasoning/tool) — bukan bubble kosong. */
    private fun hasBody(item: ChatItem.Assistant?): Boolean =
        item != null &&
            (item.texts.any { it.isNotBlank() } || item.reasonings.isNotEmpty() || item.tools.isNotEmpty())

    /**
     * Kunci id pesan "sebelum turn ini". Assistant terakhir yang belum selesai
     * DIKECUALIKAN agar saat selesai terhitung sebagai pesan baru (penting untuk
     * menyambung kembali turn yang sudah berjalan saat layar dibuka).
     */
    private fun snapshotPreIds(messages: List<ChatItem>): Set<String> {
        val lastAsst = messages.filterIsInstance<ChatItem.Assistant>().lastOrNull()
        val pendingId = if (lastAsst != null && !isTerminal(lastAsst.finish)) lastAsst.id else null
        val ids = messages.mapTo(mutableSetOf()) { it.id }
        if (pendingId != null) ids.remove(pendingId)
        return ids
    }

    /** `tool-calls` = lanjut ke langkah berikutnya, jadi belum selesai. */
    private fun isTerminal(finish: String?): Boolean = finish != null && finish != "tool-calls"

    private companion object {
        const val POLL_MS = 1500L
        const val MAX_POLL_MS = 30 * 60 * 1000L
        const val SSE_RETRY_MS = 2000L
        const val SETTLE_TRIES = 8
        const val SETTLE_MS = 700L
        const val MAX_FETCH_FAILS = 5
        const val TAIL_MS = 350L
        val TERMINAL_TOOL = setOf("completed", "error")
    }
}
