package com.arkacode.app.core

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Jembatan impor berkas dari folder Download Android ke workspace proot Arkacode.
 *
 * Alur:
 *   1. WebView Arena AI menunda berkas ke `/Download` (lihat [ArenaScreen]).
 *   2. Pengguna ketuk tombol "Impor dari Downloads" di layar Files Arkacode.
 *   3. Fungsi ini menyalin semua berkas dari `/Download` ke
 *      `<filesDir>/workspace/<projectName>/` di sisi host.
 *   4. Karena folder ini bind-mount ke `/root/workspace` di dalam proot Alpine,
 *      berkas langsung terlihat oleh opencode server → siap diedit, di-commit,
 *      dan di-push ke GitHub.
 */
object ArenaDownloadBridge {

    /**
     * Menyalin semua berkas dari folder Download Android ke workspace proot.
     *
     * @param context Context Android
     * @param filesDirFolder Folder `filesDir` aplikasi (host side)
     * @param projectName Nama proyek tujuan di dalam workspace
     * @return Jumlah berkas yang berhasil diimpor
     */
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
                // Hindari path traversal: hanya ambil nama berkas, tidak ada ".."
                val safeName = File(file.name).name
                val dest = File(workspaceDir, safeName)
                try {
                    file.inputStream().use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Hapus dari Downloads setelah berhasil dipindah (opsional,
                    // biar folder Download tidak penuh sisa unduhan Arena AI).
                    file.delete()
                    imported++
                } catch (e: Exception) {
                    // Lewati berkas yang gagal, lanjutkan ke berikutnya.
                    e.printStackTrace()
                }
            }
        }
        return imported
    }
}
