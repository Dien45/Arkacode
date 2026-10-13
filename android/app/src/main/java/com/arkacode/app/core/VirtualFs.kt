package com.arkacode.app.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Satu entri (file/folder) di workspace. */
data class FsEntry(
    val path: String,
    val size: Long,
    val modified: Long,
    val isDir: Boolean = false,
)

@Serializable
private data class LegacySessionFs(val entries: MutableMap<String, String> = mutableMapOf())

/**
 * Workspace: satu "proot workspace" bersama di `<filesDir>/workspace/` yang
 * di-bind ke `/root/workspace` di dalam distro Alpine. Di bawahnya tiap sesi
 * punya subfolder sendiri: `<filesDir>/workspace/sessions/<sessionId>/` —
 * dipakai sebagai cwd run_command, target write_file, dan isi tab Workspace
 * (File Explorer menampilkan seluruh proot workspace, termasuk folder sesi
 * lain).
 *
 * Layout lama `<filesDir>/sessions/<sessionId>/workspace` otomatis dimigrasi
 * ke subfolder sesi saat sebuah sesi diakses.
 */
class VirtualFs(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val legacyDir = File(context.filesDir, "virtual_fs")
    private val sessionsDir = File(context.filesDir, "sessions").apply { mkdirs() }

    /** Root proot workspace (tampil di tab Workspace; di-bind ke /root/workspace). */
    fun prootWorkspaceDir(): File = File(context.filesDir, "workspace").apply { mkdirs() }

    /** Folder root milik satu sesi (bukan workspace-nya). */
    fun sessionDir(sessionId: String): File {
        val dir = File(sessionsDir, sanitizeSessionId(sessionId))
        if (!dir.exists()) dir.mkdirs()
        migrateLegacy(sessionId, dir)
        return dir
    }

    /** Folder workspace satu sesi: `<prootWorkspace>/sessions/<sid>`. */
    fun workspaceDir(sessionId: String): File {
        val dir = File(File(prootWorkspaceDir(), "sessions"), sanitizeSessionId(sessionId)).apply { mkdirs() }
        migrateOldWorkspace(sessionId, dir)
        return dir
    }

    fun sanitizeSessionId(sessionId: String): String {
        val cleaned = sessionId.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
        return cleaned.ifBlank { "default" }
    }

    /**
     * Resolusi path aman terhadap [root]: menolak path absolut dan traversal
     * (`..`). Return null kalau path tidak valid.
     */
    private fun resolveUnder(root: File, path: String): File? {
        val normalized = path.trim().trimStart('/').replace('\\', '/')
        if (normalized.isEmpty()) return null
        if (normalized.split('/').any { it == ".." }) return null
        val target = File(root, normalized)
        // Pastikan hasilnya tetap di dalam root (jaga-jaga symlink/path aneh).
        val rootPath = root.canonicalPath + File.separator
        return if (target.canonicalPath.startsWith(rootPath) || target.canonicalPath == root.canonicalPath) {
            target
        } else {
            null
        }
    }

    /** Resolusi path relatif terhadap workspace sesi. */
    fun resolve(sessionId: String, path: String): File? = resolveUnder(workspaceDir(sessionId), path)

    /** Resolusi path relatif terhadap root proot workspace (mis. sessions/<sid>/file). */
    fun resolveProot(path: String): File? = resolveUnder(prootWorkspaceDir(), path)

    // ------------------------------------------------------------- read/write

    fun read(sessionId: String, path: String): String? {
        val f = resolve(sessionId, path) ?: return null
        if (!f.isFile) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun readProot(path: String): String? {
        val f = resolveProot(path) ?: return null
        if (!f.isFile) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun readBytes(sessionId: String, path: String): ByteArray? {
        val f = resolve(sessionId, path) ?: return null
        if (!f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    fun readBytesProot(path: String): ByteArray? {
        val f = resolveProot(path) ?: return null
        if (!f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    /** Menulis file (folder induk dibuat otomatis). Return false kalau path invalid. */
    fun write(sessionId: String, path: String, content: String): Boolean {
        val f = resolve(sessionId, path) ?: return false
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(content)
            true
        }.getOrDefault(false)
    }

    fun writeProot(path: String, content: String): Boolean {
        val f = resolveProot(path) ?: return false
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(content)
            true
        }.getOrDefault(false)
    }

    fun writeBytes(sessionId: String, path: String, bytes: ByteArray): Boolean {
        val f = resolve(sessionId, path) ?: return false
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    fun writeBytesProot(path: String, bytes: ByteArray): Boolean {
        val f = resolveProot(path) ?: return false
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    fun remove(sessionId: String, path: String): Boolean {
        val f = resolve(sessionId, path) ?: return false
        return runCatching { f.deleteRecursively() }.getOrDefault(false)
    }

    fun removeProot(path: String): Boolean {
        val f = resolveProot(path) ?: return false
        return runCatching { f.deleteRecursively() }.getOrDefault(false)
    }

    /** Path → ukuran (byte), urut alfabetis. Dipertahankan untuk kompatibilitas pemanggil lama. */
    fun list(sessionId: String): List<Pair<String, Int>> =
        listEntries(sessionId).filterNot { it.isDir }.map { it.path to it.size.toInt() }

    /** Daftar file (tanpa folder) lengkap dengan waktu modifikasi. */
    fun listEntries(sessionId: String): List<FsEntry> = listEntriesUnder(workspaceDir(sessionId))

    /** Pohon folder untuk File Explorer (folder + file, urut folder dulu). */
    fun tree(sessionId: String): List<FsEntry> =
        listEntries(sessionId).sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.path.lowercase() })

    /** Pohon seluruh proot workspace (semua sesi) untuk tab Workspace. */
    fun treeProot(): List<FsEntry> =
        listEntriesUnder(prootWorkspaceDir()).sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.path.lowercase() })

    private val skipDirNames = setOf(
        ".git", "node_modules", "build", ".gradle", "dist", ".next",
        "target", "vendor", "__pycache__", ".cache", "rootfs", "distro",
    )
    private val maxTreeEntries = 3000

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    private fun listEntriesUnder(root: File): List<FsEntry> {
        if (!root.exists()) return emptyList()
        val out = mutableListOf<FsEntry>()
        root.walkTopDown()
            .onEnter { dir -> dir.name !in skipDirNames && !isSymlink(dir) }
            .forEach { f ->
                if (f == root) return@forEach
                if (out.size >= maxTreeEntries) return out.sortedBy { it.path.lowercase() }
                val rel = runCatching { f.relativeTo(root).path.replace(File.separatorChar, '/') }.getOrNull()
                    ?: return@forEach
                if (rel.isEmpty()) return@forEach
                val link = isSymlink(f)
                val isDir = !link && runCatching { f.isDirectory }.getOrDefault(false)
                val len = if (isDir) 0L else runCatching { f.length() }.getOrDefault(0L)
                val mod = runCatching { f.lastModified() }.getOrDefault(0L)
                out.add(FsEntry(path = rel, size = len, modified = mod, isDir = isDir))
            }
        return out.sortedBy { it.path.lowercase() }
    }

    fun getAllKeys(sessionId: String): List<String> = list(sessionId).map { it.first }

    fun totalBytes(sessionId: String): Long = listEntries(sessionId).filterNot { it.isDir }.sumOf { it.size }

    /** Jumlah file di dalam satu folder (untuk UI tree). */
    fun countIn(sessionId: String, folder: String): Int {
        val prefix = folder.trimEnd('/') + "/"
        return list(sessionId).count { it.first.startsWith(prefix) }
    }

    fun deleteSession(sessionId: String) {
        runCatching { workspaceDir(sessionId).deleteRecursively() }
        runCatching { File(sessionsDir, sanitizeSessionId(sessionId)).deleteRecursively() }
        runCatching { File(legacyDir, "$sessionId.json").delete() }
    }

    // -------------------------------------------------------------- migration

    /** Pindahkan workspace lama `<sessions>/<sid>/workspace` ke layout proot. */
    private fun migrateOldWorkspace(sessionId: String, newDir: File) {
        val oldDir = File(sessionDir(sessionId), "workspace")
        if (!oldDir.isDirectory) return
        val same = runCatching { oldDir.canonicalPath == newDir.canonicalPath }.getOrDefault(false)
        if (same) return
        val marker = File(newDir, ".arka-migrated")
        if (marker.exists()) return
        runCatching {
            val hasNew = newDir.listFiles()?.any { it.name != ".arka-migrated" } == true
            if (!hasNew) {
                oldDir.copyRecursively(newDir, overwrite = true)
                oldDir.deleteRecursively()
            }
            marker.writeText("ok")
        }
    }

    /** Migrasi workspace JSON versi lama ke file nyata (sekali per sesi). */
    private fun migrateLegacy(sessionId: String, dir: File) {
        val marker = File(dir, ".migrated")
        if (marker.exists()) return
        val legacy = File(legacyDir, "$sessionId.json")
        if (legacy.isFile) {
            runCatching {
                val parsed = json.decodeFromString<LegacySessionFs>(legacy.readText())
                val ws = File(dir, "workspace").apply { mkdirs() }
                parsed.entries.forEach { (path, content) ->
                    val rel = path.trimStart('/').replace('\\', '/')
                    if (rel.isNotEmpty() && !rel.split('/').contains("..")) {
                        val target = File(ws, rel)
                        target.parentFile?.mkdirs()
                        target.writeText(content)
                    }
                }
                legacy.delete()
            }
        }
        runCatching { marker.writeText("ok") }
    }
}
