package com.arkacode.app.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Ekstraktor ZIP aman untuk berkas workspace yang diunduh dari Arena AI.
 *
 * Pengamanan:
 *  - Anti "zip-slip": setiap entri dinormalisasi dan path kanoniknya wajib
 *    berada di dalam folder tujuan; entri dengan `..`, path absolut, atau
 *    keluar dari tujuan akan dilewati.
 *  - Batas jumlah entri dan total byte agar tidak terserang zip bomb.
 *  - Opsi membuang satu folder root pembungkus (umum pada arsip workspace:
 *    zip berisi satu folder induk lalu berkas di dalamnya).
 */
object ZipExtractor {

    data class Result(
        val files: Int,
        val skipped: Int,
        val totalBytes: Long,
    )

    private const val DEFAULT_MAX_ENTRIES = 10_000
    private const val DEFAULT_MAX_TOTAL_BYTES = 1L shl 30 // 1 GiB

    /**
     * Ekstrak [zip] ke [destDir].
     *
     * @param stripSingleRoot bila true dan seluruh entri berada di bawah satu
     *   folder root, folder itu dibuang sehingga isinya mendarat langsung di
     *   [destDir].
     */
    fun extract(
        zip: File,
        destDir: File,
        stripSingleRoot: Boolean = true,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    ): Result {
        destDir.mkdirs()
        val destRoot = destDir.canonicalFile
        val destPrefix = destRoot.path + File.separator

        val strip = if (stripSingleRoot) singleRootFolder(zip, maxEntries) else null

        var files = 0
        var skipped = 0
        var totalBytes = 0L

        ZipInputStream(FileInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                try {
                    val name = normalizeEntryName(entry.name, strip)
                    if (name.isNotEmpty() && !entry.isDirectory) {
                        if (files >= maxEntries) {
                            skipped++
                        } else {
                            val target = File(destRoot, name).canonicalFile
                            if (isInside(destPrefix, target)) {
                                target.parentFile?.mkdirs()
                                val written = copyEntry(zis, target, maxTotalBytes - totalBytes)
                                if (written < 0) {
                                    target.delete()
                                    skipped++
                                } else {
                                    files++
                                    totalBytes += written
                                }
                            } else {
                                skipped++
                            }
                        }
                    }
                } finally {
                    zis.closeEntry()
                }
                entry = zis.nextEntry
            }
        }
        return Result(files = files, skipped = skipped, totalBytes = totalBytes)
    }

    /** Kembalikan nama folder root bila semua entri berada di bawah satu root. */
    private fun singleRootFolder(zip: File, maxEntries: Int): String? {
        var root: String? = null
        var count = 0
        ZipInputStream(FileInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && count < maxEntries) {
                val name = entry.name.replace('\\', '/').trimStart('/')
                val slash = name.indexOf('/')
                if (slash <= 0) return null // entri di root, tidak ada root tunggal
                val top = name.substring(0, slash)
                if (root == null) root = top else if (root != top) return null
                count++
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return root
    }

    private fun normalizeEntryName(raw: String, stripRoot: String?): String {
        var name = raw.replace('\\', '/').trimStart('/')
        if (stripRoot != null && name.startsWith("$stripRoot/")) {
            name = name.substring(stripRoot.length + 1)
        }
        return name
    }

    private fun isInside(destPrefix: String, target: File): Boolean =
        target.path.startsWith(destPrefix)

    /** @return byte tertulis, atau -1 bila melewati batas [limit]. */
    private fun copyEntry(zis: ZipInputStream, target: File, limit: Long): Long {
        var written = 0L
        FileOutputStream(target).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = zis.read(buf)
                if (read <= 0) break
                written += read
                if (written > limit) return -1L
                out.write(buf, 0, read)
            }
        }
        return written
    }
}
