package com.arkacode.app.core

/**
 * Konfigurasi `run_command` yang dipakai [ExecRunner].
 *
 * `run_command` SELALU berjalan di distro Alpine asli lewat proot (tanpa root):
 * `apk`, `git`, `python3`, `node`, dll. tersedia. Backend shell Android (toybox)
 * sudah dihapus — tidak ada lagi jalur fallback native.
 *
 * Prasyarat: binary proot dibundel sebagai jniLibs (task Gradle
 * `downloadProotBinaries`) dan rootfs Alpine sudah dipasang dari Settings.
 */
data class ExecSettings(
    /** Kosong = semua command diizinkan (tetap lewat gate approval). */
    val allowlist: List<String> = emptyList(),
    val timeoutMs: Long = ExecRunner.PROOT_DEFAULT_TIMEOUT_MS,
    val distroRootfsUrl: String = DistroManager.DEFAULT_ROOTFS_URL,
    /** proot butuh seccomp dimatikan di sebagian perangkat ARM64. */
    val prootNoSeccomp: Boolean = true,
    /** Bind proot workspace (berisi folder per sesi) ke /root/workspace di dalam distro. */
    val bindWorkspace: Boolean = true,
)
