package com.arkacode.app.net

/** Sesi chat di server (parsing longgar — field tak dikenal diabaikan). */
data class ChatSession(
    val id: String,
    val title: String,
    val providerID: String? = null,
    val modelID: String? = null,
)

/** Satu tool-call dalam balasan assistant. */
data class ToolUse(val id: String, val name: String, val status: String)

/** Item chat siap-render. */
sealed interface ChatItem {
    data class User(val id: String, val text: String, val fileCount: Int) : ChatItem
    data class Assistant(
        val id: String,
        val texts: List<String>,
        val reasonings: List<String>,
        val tools: List<ToolUse>,
    ) : ChatItem

    /** synthetic/system/dll — tampil kecil. */
    data class Note(val id: String, val text: String) : ChatItem
}

data class ModelInfo(val id: String, val name: String, val providerID: String, val free: Boolean = false)

/** Agent opencode (mode primary/subagent/all). */
data class AgentInfo(
    val id: String,
    val description: String,
    val mode: String,
    val color: String?,
    val hidden: Boolean,
)

/** Perintah (slash command) yang terdaftar di server. */
data class CommandInfo(val name: String, val description: String, val agent: String?)

/** Skill opencode (folder/URL/embedded). */
data class SkillInfo(val name: String, val description: String, val location: String, val slash: Boolean)

/** Satu berkas dalam rencana revert. */
data class FileDiffEntry(
    val path: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val patch: String,
)

/** Status revert yang distage server. */
data class RevertState(val messageID: String, val files: List<FileDiffEntry>)
data class ProviderInfo(val id: String, val name: String, val models: List<ModelInfo>)
data class ModelRef(val providerID: String, val id: String)

/** Permintaan persetujuan (ringkasan best-effort dari field mentahnya). */
data class PermissionReq(val id: String, val summary: String)

/** Entri filesystem server (path relatif ke working dir). */
data class FsEntry(val path: String, val isDir: Boolean)
