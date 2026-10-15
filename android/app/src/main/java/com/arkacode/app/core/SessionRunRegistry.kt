package com.arkacode.app.core

import androidx.compose.runtime.mutableStateOf
import com.arkacode.app.net.ChatItem
import com.arkacode.app.net.ModelRef
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.PermissionReq
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Status "turn berjalan" untuk SATU sesi. Dimiliki [SessionRunRegistry] yang
 * di-scope ke layar engine, sehingga status tetap hidup saat layar chat dibuang
 * (mis. pengguna pindah sesi) — turn di server tidak lagi "berhenti" di UI.
 */
class SessionRun {
    val busy = mutableStateOf(false)
    val messages = mutableStateOf<List<ChatItem>>(emptyList())
    val pending = mutableStateOf<List<PermissionReq>>(emptyList())
    val liveText = mutableStateOf("")
    val liveReasoning = mutableStateOf("")
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
) {
    private val runs = LinkedHashMap<String, SessionRun>()

    fun run(sessionId: String): SessionRun = runs.getOrPut(sessionId) { SessionRun() }

    /** Muat awal + sambung kembali ke turn yang masih berjalan di server. */
    fun attach(sessionId: String) {
        val r = run(sessionId)
        if (r.attached) return
        r.attached = true
        scope.launch {
            runCatching { client.listMessages(sessionId) }.getOrNull()?.let { r.messages.value = it }
            runCatching { client.listPermissions(sessionId) }.getOrNull()?.let { r.pending.value = it }
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
        }
    }

    fun clearError(sessionId: String) {
        run(sessionId).error.value = null
    }

    /** Kirim prompt. `false` bila kosong atau sesi masih sibuk. */
    fun send(sessionId: String, text: String, model: ModelRef?): Boolean {
        val trimmed = text.trim()
        val r = run(sessionId)
        if (trimmed.isEmpty() || r.busy.value) return false
        r.error.value = null
        r.busy.value = true
        r.justSent.value = trimmed
        r.liveText.value = ""
        r.liveReasoning.value = ""
        r.toolLabel.value = null
        r.preIds = snapshotPreIds(r.messages.value)
        r.pollJob?.cancel()
        r.pollJob = scope.launch {
            try {
                model?.let { m -> runCatching { client.switchModel(sessionId, m.providerID, m.id) } }
                client.sendPrompt(sessionId, trimmed)
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
        r.toolLabel.value = null
        r.justSent.value = null
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
        try {
            while (true) {
                runCatching { client.listMessages(sessionId) }.getOrNull()?.let { r.messages.value = it }
                runCatching { client.listPermissions(sessionId) }.getOrNull()?.let { r.pending.value = it }
                val newAssistant = r.messages.value
                    .filterIsInstance<ChatItem.Assistant>()
                    .lastOrNull { it.id !in r.preIds }
                val done = newAssistant != null && isTerminal(newAssistant.finish)
                if (done) {
                    newAssistant.error?.takeIf { it.isNotBlank() }?.let { r.error.value = it }
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
            runCatching { client.listMessages(sessionId) }.getOrNull()?.let { r.messages.value = it }
        }
    }

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
    }
}
