package com.arkacode.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Akses "All files access" (MANAGE_EXTERNAL_STORAGE) untuk membaca ZIP yang
 * diunduh Chrome ke folder Download publik. Diperlukan sejak scoped storage
 * (Android 11+) agar berkas yang dibuat aplikasi lain (Chrome) bisa dibaca.
 */
object StorageAccess {

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Intent ke halaman Settings agar pengguna memberi izin sekali. */
    fun requestAllFilesAccess(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
}
