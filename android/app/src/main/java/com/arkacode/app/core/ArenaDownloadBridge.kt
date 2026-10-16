package com.arkacode.app.core

import java.io.File
import java.io.InputStream

/**
 * Jembatan impor berkas dari folder Download Android ke workspace proot Arkacode.
 *
 * Alur:
 *  1. WebView / Chrome Custom Tab Arena AI menyimpan berkas ke /Download.
 *  2. Pengguna menekan tombol impor di layar Files Arkacode (atau impor otomatis
 *     saat aplikasi kembali aktif).
 *  3. Fungsi ini menyalin berkas dari /Download ke [workspaceDir] (root proot
 *     workspace, bind-mount ke /root/workspace). Berkas `.zip` langsung
 *     diekstrak ke workspace (isi arsip, bukan zip-nya).
 *  4. Berkas itu langsung terlihat oleh server opencode lewat /root/workspace.
 */
object ArenaDownloadBridge {

    data class Summary(
        val copied: Int,
        val extractedZips: Int,
        val extractedFiles: Int,
    ) {
        val changed: Boolean get() = copied > 0 || extractedFiles > 0
    }

    fun importDownloadsToWorkspace(
        downloadsDir: File,
        workspaceDir: File,
        extractZips: Boolean = true,
        onlyZip: Boolean = false,
    ): Summary {
        if (!downloadsDir.exists() || !downloadsDir.isDirectory) {
            return Summary(0, 0, 0)
        }

        workspaceDir.mkdirs()

        var copied = 0
        var zips = 0
        var extractedFiles = 0

        downloadsDir.listFiles()?.forEach { file ->
            if (!file.isFile || file.name.isEmpty()) return@forEach
            val safeName = File(file.name).name
            val isZip = safeName.endsWith(".zip", ignoreCase = true)
            if (onlyZip && !isZip) return@forEach
            try {
                if (extractZips && isZip) {
                    val result = ZipExtractor.extract(file, workspaceDir, stripSingleRoot = true)
                    if (result.files == 0 && result.skipped > 0) {
                        // Arsip rusak / tak dapat diekstrak: simpan zip-nya apa adanya.
                        copyFile(file, File(workspaceDir, safeName))
                        copied++
                    } else {
                        zips++
                        extractedFiles += result.files
                        file.delete()
                    }
                } else {
                    copyFile(file, File(workspaceDir, safeName))
                    copied++
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return Summary(copied, zips, extractedFiles)
    }

    /**
     * Impor satu berkas ZIP (mis. dari pemilih berkas SAF) langsung ke workspace.
     * Stream disalin ke berkas sementara lalu diekstrak.
     */
    fun importZipStream(
        input: InputStream,
        workspaceDir: File,
        tmpDir: File,
    ): ZipExtractor.Result {
        workspaceDir.mkdirs()
        tmpDir.mkdirs()
        val tmp = File(tmpDir, "import-${System.currentTimeMillis()}.zip")
        return try {
            tmp.outputStream().use { out -> input.copyTo(out) }
            val result = ZipExtractor.extract(tmp, workspaceDir, stripSingleRoot = true)
            if (result.files == 0 && result.skipped > 0) {
                copyFile(tmp, File(workspaceDir, "imported.zip"))
            }
            result
        } finally {
            input.close()
            tmp.delete()
        }
    }

    private fun copyFile(src: File, dest: File) {
        src.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
