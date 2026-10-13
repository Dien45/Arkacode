package com.arkacode.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.arkacode.app.net.OpenCodeClient
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Unduh + jalankan binary `opencode` resmi di dalam proot Alpine (F0).
 *
 * - Versi di-pin ([ENGINE_VERSION]) demi build reproducible.
 * - Binary ditaruh di `<filesDir>/engine/opencode`, di-bind ke
 *   `/opt/arkacode` di dalam distro, dijalankan langsung (tanpa sh) agar
 *   sinyal stop bekerja.
 * - Server dipegang sebagai [Process] + log append ke `serve.log`.
 */
object EngineConfig {
    const val VERSION = "v1.19.0"
    const val ASSET_ARM64 = "opencode-linux-arm64.zip"
    const val ASSET_FALLBACK = "opencode-linux-aarch64.zip"
    const val DEFAULT_PORT = 4096
    const val DEFAULT_HOST = "127.0.0.1"

    fun downloadUrls(): List<String> = listOf(ASSET_ARM64, ASSET_FALLBACK).map { asset ->
        "https://github.com/anomalyco/opencode/releases/download/$VERSION/$asset"
    }
}

sealed interface EngineState {
    data object NotInstalled : EngineState
    data class Downloading(val percent: Int) : EngineState
    data class Ready(val detail: String) : EngineState
    data class Running(val port: Int) : EngineState
    data class Error(val message: String) : EngineState
}

class EngineManager(private val context: Context) {
    private val distro = DistroManager(context)
    private val execRunner = ExecRunner(context, distro)

    private val engineDir: File get() = File(context.filesDir, "engine").apply { mkdirs() }
    private val workspaceDir: File get() = File(context.filesDir, "workspace").apply { mkdirs() }

    fun binaryFile(): File = File(engineDir, "opencode")
    fun logFile(): File = File(engineDir, "serve.log")

    @Volatile
    private var serverProcess: Process? = null

    fun isDownloaded(): Boolean {
        val bin = binaryFile()
        return bin.isFile && bin.canExecute()
    }

    /** Cek `--version` lewat proot (butuh distro + engine terpasang). */
    suspend fun version(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val res = execRunner.run(
                "/opt/arkacode/opencode --version",
                null,
                ExecSettings(bindWorkspace = false, timeoutMs = 30_000),
            )
            (res.stdout + res.stderr).trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * Unduh + verifikasi + ekstrak binary. [onProgress] 0..100.
     * Berhasil bila zip valid, berisi entry `opencode`, dan `--version` jalan.
     */
    suspend fun ensureInstalled(onProgress: (Int) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (isDownloaded()) {
                    return@runCatching version() ?: "terpasang (distro belum siap untuk cek versi)"
                }
                val zipFile = File(engineDir, "opencode.zip")
                var downloaded = false
                var lastError: Exception? = null
                for (url in EngineConfig.downloadUrls()) {
                    try {
                        onProgress(0)
                        download(url, zipFile, onProgress)
                        downloaded = true
                        break
                    } catch (e: Exception) {
                        lastError = e as? Exception ?: IllegalStateException(e.message)
                    }
                }
                if (!downloaded) throw lastError ?: IllegalStateException("Unduhan engine gagal")
                onProgress(95)
                extractBinary(zipFile)
                zipFile.delete()
                onProgress(100)
                version() ?: "terpasang (distro belum siap untuk cek versi)"
            }
        }

    private fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "arkacode-android")
        }
        conn.connect()
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode} saat mengunduh engine")
        val total = conn.contentLengthLong.takeIf { it > 0 }
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (total != null) onProgress(((read * 90) / total).toInt().coerceIn(0, 90))
                }
            }
        }
        if (target.length() < 1_000_000) throw IllegalStateException("Berkas unduhan terlalu kecil, kemungkinan bukan zip valid")
    }

    private fun extractBinary(zipFile: File) {
        var found = false
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (name == "opencode") {
                    val out = binaryFile()
                    FileOutputStream(out).use { o -> zip.copyTo(o) }
                    out.setExecutable(true, true)
                    found = true
                    break
                }
            }
        }
        if (!found) throw IllegalStateException("ZIP tidak berisi binary opencode")
        if (!binaryFile().canExecute()) throw IllegalStateException("Binary tidak bisa di-exec")
    }

    fun distroStatus(): DistroStatus = distro.status()

    suspend fun setupDistro(onProgress: (Int, String) -> Unit = { _, _ -> }): Result<Unit> =
        distro.install(DistroManager.DEFAULT_ROOTFS_URL, onProgress)

    /**
     * Jalankan `opencode serve` sebagai proses persisten, lalu tunggu
     * `/global/health` OK (maks [waitMs]). Kembalikan port bila siap.
     */
    suspend fun startServer(
        port: Int = EngineConfig.DEFAULT_PORT,
        password: String,
        workDir: File = workspaceDir,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (serverProcess?.isAlive == true) return@runCatching port
            val bin = binaryFile()
            check(bin.isFile) { "Engine belum diunduh. Unduh dulu." }
            workDir.mkdirs()
            logFile().appendText("\n===== serve start port=$port =====\n")

            val argv = serveArgv(port, workDir)
                ?: throw IllegalStateException("Distro belum siap (pasang Alpine dulu).")
            val builder = ProcessBuilder(argv)
            builder.directory(context.filesDir)
            builder.environment().apply {
                putAll(distro.prootProcessEnv())
                put("OPENCODE_SERVER_PASSWORD", password)
                this["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
                this["HOME"] = context.filesDir.absolutePath
                this["TMPDIR"] = context.cacheDir.absolutePath
            }
            builder.redirectErrorStream(true)
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile()))
            val proc = builder.start()
            serverProcess = proc

            val client = OpenCodeClient(port = port, password = password)
            val deadline = System.currentTimeMillis() + 60_000
            var lastErr = ""
            while (System.currentTimeMillis() < deadline) {
                if (proc.isAlive.not()) {
                    serverProcess = null
                    throw IllegalStateException("Proses serve mati saat startup. Cek serve.log.")
                }
                try {
                    val h = client.health()
                    if (h.healthy) return@runCatching port
                    lastErr = "healthy=false"
                } catch (e: Exception) {
                    lastErr = e.message ?: "menunggu..."
                }
                delay(1000)
            }
            stopServer()
            throw IllegalStateException("Server tidak siap dalam 60 dtk ($lastErr). Cek serve.log.")
        }
    }

    fun stopServer() {
        runCatching { serverProcess?.destroy() }
        runCatching {
            serverProcess?.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        runCatching { serverProcess?.destroyForcibly() }
        serverProcess = null
        runCatching { logFile().appendText("===== serve stop =====\n") }
    }

    fun isRunning(): Boolean = serverProcess?.isAlive == true

    fun tailLog(maxLines: Int = 30): String = runCatching {
        val lines = logFile().takeIf { it.isFile }?.readLines() ?: return ""
        lines.takeLast(maxLines).joinToString("\n")
    }.getOrDefault("")

    /**
     * argv proot untuk serve: cerminan DistroManager.buildProotCommand +
     * bind engine dir, eksekusi binary LANGSUNG (bukan sh -c) agar stop bersih.
     */
    private fun serveArgv(port: Int, workDir: File): List<String>? {
        val proot = distro.prootBinary() ?: return null
        val loader = distro.loaderBinary() ?: return null
        if (!distro.isInstalled()) return null
        val pw = workspaceDir
        return mutableListOf(
            proot.absolutePath,
            "--kill-on-exit",
            "-0",
            "-r", distro.rootfsDir.absolutePath,
            "-w", "/root/workspace",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", distro.rootfsDir.absolutePath + "/tmp:/dev/shm",
            "-b", "${pw.absolutePath}:/root/workspace",
            "-b", "${engineDir.absolutePath}:/opt/arkacode",
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PROOT_LOADER=${loader.absolutePath}",
            "PROOT_TMP_DIR=${File(context.filesDir, "proot-tmp").apply { mkdirs() }.absolutePath}",
            "PROOT_NO_SECCOMP=1",
            "/opt/arkacode/opencode",
            "serve",
            "--port", port.toString(),
            "--hostname", EngineConfig.DEFAULT_HOST,
        )
    }
}
