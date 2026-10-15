package com.arkacode.app.core

import com.arkacode.app.net.CommandInfo
import com.arkacode.app.net.SkillInfo

/** Satu baris saran slash command: perintah server atau skill terpasang. */
data class SlashSuggestion(
    val name: String,
    val description: String,
    val isSkill: Boolean,
)

/**
 * Deteksi & filter saran slash command untuk kolom input.
 * Murni tanpa Android sehingga mudah dites dan jadi sumber kebenaran
 * bagi panel UI maupun perilaku kirim.
 */
object SlashSuggest {

    /** Batas baris yang ditampilkan di panel. */
    const val MAX_SUGGESTIONS = 6

    /**
     * Nama parsial yang sedang diketik, atau `null` bila kolom bukan sedang
     * mengetik nama command.
     *
     * - `""`  → pengguna mengetik `/` saja → tampilkan semua.
     * - `"pa"` → pengguna mengetik `/pa` → filter.
     * - `null` → teks tidak diawali `/`, atau sudah ada spasi setelah nama
     *   (`/plan test`) → nama sudah selesai, panel tertutup.
     */
    fun token(text: String): String? {
        if (!text.startsWith("/")) return null
        val body = text.substring(1)
        val name = body.takeWhile { it != ' ' && it != '\n' && it != '\t' }
        // Ada teks setelah nama (spasi + apa pun) → nama sudah lengkap.
        if (body.length > name.length) return null
        return name
    }

    /** Perintah & skill yang namanya diawali [prefix] (tanpa `/`). */
    fun filter(
        prefix: String,
        commands: List<CommandInfo>,
        skills: List<SkillInfo>,
    ): List<SlashSuggestion> {
        val p = prefix.lowercase()
        val cmds = commands.filter { it.name.lowercase().startsWith(p) }.map {
            SlashSuggestion(it.name, it.description.ifBlank { it.template.orEmpty() }, isSkill = false)
        }
        val sks = skills.filter { it.name.lowercase().startsWith(p) }.map {
            SlashSuggestion(it.name, it.description.ifBlank { it.location }, isSkill = true)
        }
        return (cmds + sks).sortedBy { it.name.lowercase() }.take(MAX_SUGGESTIONS)
    }

    /** Teks yang menggantikan token di kolom input saat saran dipilih. */
    fun replacement(name: String): String = "/$name "
}
