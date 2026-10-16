package com.arkacode.app.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Jembatan impor berkas dari folder Download Android ke workspace proot Arkacode.
 *
 * Alur:
 *  1. WebView Arena AI menyimpan berkas ke /Download (lihat ArenaScreen).
 *  2. Pengguna ketuk tombol impor di layar Files Arkacode.
 *  3. Fungsi ini menyalin semua berkas dari /Download ke
 *     <filesDir>/workspace/<projectName>/ di sisi host.
 *  4. Folder itu bind-mount ke /root/workspace di dalam proot Alpine,
 *     jadi berkas langsung terlihat oleh server opencode.
 */
object ArenaDownloadBridge {

    fun importDownloadsToWorkspace(
        context: Context,
        filesDirFolder: File,
        projectName: String
    ): Int {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        if (!downloadsDir.exists() || !downloadsDir.isDirectory) {
            return 0
        }

        val workspaceDir = File(filesDirFolder, "workspace/$projectName")
        workspaceDir.mkdirs()

        var imported = 0
        downloadsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.isNotEmpty()) {
                val safeName = File(file.name).name
                val dest = File(workspaceDir, safeName)
                try {
                    file.inputStream().use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    file.delete()
                    imported++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return imported
    }
}
