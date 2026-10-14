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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

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
    // Build musl = cocok untuk Alpine; fallback build gnu bila musl bermasalah.
    const val ASSET_MUSL = "opencode-linux-arm64-musl.tar.gz"
    const val ASSET_MUSL_SHA256 = "c864e28b1504fe4412d9495493e72b8ebf3e205dd1ee14d125b99c14b26623da"
    const val ASSET_GNU = "opencode-linux-arm64.tar.gz"
    const val ASSET_GNU_SHA256 = "e9841d43d3e1a8f36ed2691592d3b8b4fb097e606c8b559a277a1b845840e26b"
    const val DEFAULT_PORT = 4096
    const val DEFAULT_HOST = "127.0.0.1"

    data class Asset(val name: String, val sha256: String)

    fun assets(): List<Asset> = listOf(
        Asset(ASSET_MUSL, ASSET_MUSL_SHA256),
        Asset(ASSET_GNU, ASSET_GNU_SHA256),
    )

    fun downloadUrl(asset: String) =
        "https://github.com/anomalyco/opencode/releases/download/$VERSION/$asset"
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

    /**
     * Cek `--version` lewat proot DENGAN bind engine (`/opt/arkacode`).
     * Tanpa bind ini proot menjawab "not found" walau binary ada di host.
     * Return null bila output tidak mirip nomor versi (mis. pesan error shell).
     */
    suspend fun version(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val out = runOneShot(listOf("/opt/arkacode/opencode", "--version")).trim()
            out.takeIf { it.contains(Regex("\\d+\\.\\d+")) }?.take(100)
        }.getOrNull()
    }

    /**
     * Binary opencode butuh simbol C++ (libstdc++/libgcc) yang tidak ada di
     * Alpine minirootfs polos — tanpa ini serve mati dengan "Error relocating".
     * Diinstal sekali via apk; marker di dalam rootfs sehingga ikut terhapus
     * (dan dipasang ulang) saat rootfs dipasang ulang.
     */
    suspend fun ensureGuestLibs(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val marker = File(distro.rootfsDir, "root/.guestlibs-ok")
            if (!marker.isFile) {
                val out = runOneShot(
                    listOf("/sbin/apk", "add", "--no-cache", "libstdc++", "libgcc"),
                    180_000,
                )
                if (out.contains("ERROR", ignoreCase = true)) {
                    throw IllegalStateException("apk gagal: ${out.trim().take(500)}")
                }
                runCatching { marker.writeText("ok") }
            }
        }
    }

    /** Eksekusi satu perintah LANGSUNG (tanpa sh) di proot dengan bind engine + workspace. */
    suspend fun runOneShot(tail: List<String>, timeoutMs: Long = 30_000): String =
        withContext(Dispatchers.IO) {
            val argv = baseArgv() ?: throw IllegalStateException("Distro belum siap")
            val builder = ProcessBuilder(argv + guestEnvTail() + tail)
            builder.directory(context.filesDir)
            builder.environment().apply {
                putAll(distro.prootProcessEnv())
                this["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
                this["HOME"] = context.filesDir.absolutePath
                this["TMPDIR"] = context.cacheDir.absolutePath
            }
            builder.redirectErrorStream(true)
            val proc = builder.start()
            try {
                if (!proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    throw IllegalStateException("timeout")
                }
                proc.inputStream.readBytes().toString(Charsets.UTF_8).take(64 * 1024)
            } finally {
                runCatching { proc.destroyForcibly() }
            }
        }

    private fun baseArgv(): MutableList<String>? {
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
        )
    }

    private fun guestEnvTail(): List<String> {
        val loader = checkNotNull(distro.loaderBinary()) { "loader proot hilang" }
        return listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PROOT_LOADER=${loader.absolutePath}",
            "PROOT_TMP_DIR=${File(context.filesDir, "proot-tmp").apply { mkdirs() }.absolutePath}",
            "PROOT_NO_SECCOMP=1",
        )
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
                val archive = File(engineDir, "opencode.tgz")
                var downloaded = false
                var lastAsset: String? = null
                var lastError: Exception? = null
                for (asset in EngineConfig.assets()) {
                    try {
                        onProgress(0)
                        lastAsset = asset.name
                        download(EngineConfig.downloadUrl(asset.name), archive, onProgress)
                        val actual = sha256Hex(archive)
                        if (!actual.equals(asset.sha256, ignoreCase = true)) {
                            throw IllegalStateException("Checksum ${asset.name} tidak cocok (rusak/terpotong?)")
                        }
                        downloaded = true
                        break
                    } catch (e: Exception) {
                        lastError = e as? Exception ?: IllegalStateException(e.message)
                    }
                }
                if (!downloaded) throw lastError ?: IllegalStateException("Unduhan engine gagal")
                onProgress(95)
                extractBinary(archive)
                runCatching { File(engineDir, "asset.txt").writeText(lastAsset ?: "?") }
                archive.delete()
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

    private fun sha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractBinary(tarGzFile: File) {
        var found = false
        GzipCompressorInputStream(BufferedInputStream(tarGzFile.inputStream())).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/')
                    if (name == "opencode") {
                        val out = binaryFile()
                        FileOutputStream(out).use { o -> tar.copyTo(o) }
                        out.setExecutable(true, true)
                        found = true
                        break
                    }
                }
            }
        }
        if (!found) throw IllegalStateException("Arsip tidak berisi binary opencode")
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
            check(isDownloaded()) { "Engine belum siap (belum diunduh / file rusak). Unduh ulang." }
            ensureGuestLibs().getOrThrow()
            workDir.mkdirs()
            logFile().appendText("\n===== serve start port=$port =====\n")

            val argv = serveArgv(port, workDir, password, providerEnv())
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
                    val tail = tailLog(15).take(1200)
                    throw IllegalStateException(
                        "Proses serve mati saat startup." +
                            if (tail.isBlank()) " (serve.log kosong)" else "\n--- serve.log ---\n$tail",
                    )
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

    /**
     * Uji eksekusi proot terisolasi (tanpa opencode): echo + arsitektur +
     * ukuran page kernel + isi /opt/arkacode. Untuk membedakan "proot tidak
     * bisa exec apa pun" vs "khusus binary opencode yang gagal".
     */
    suspend fun execSelfTest(): String = withContext(Dispatchers.IO) {
        val abi = android.os.Build.SUPPORTED_ABIS.joinToString(",")
        val echo = runCatching { runOneShot(listOf("/bin/echo", "PROOT_OK")) }.getOrElse { "echo GAGAL: ${it.message}" }
        val pagesize = runCatching { runOneShot(listOf("/bin/sh", "-c", "getconf PAGESIZE 2>/dev/null || echo unknown")) }.getOrElse { "?" }
        val bind = runCatching { runOneShot(listOf("/bin/ls", "-la", "/opt/arkacode")) }.getOrElse { "ls GAGAL: ${it.message}" }
        "ABI: $abi\n" + "echo: ${echo.trim()}\n" + "PAGESIZE: ${pagesize.trim()}\n" + "ls /opt/arkacode:\n${bind.trim().take(800)}"
    }

    fun downloadedAsset(): String? =
        File(engineDir, "asset.txt").takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }

    fun isRunning(): Boolean = serverProcess?.isAlive == true

    fun tailLog(maxLines: Int = 30): String = runCatching {
        val lines = logFile().takeIf { it.isFile }?.readLines() ?: return ""
        lines.takeLast(maxLines).joinToString("\n")
    }.getOrDefault("")

    /**
     * argv proot untuk serve: cerminan DistroManager.buildProotCommand +
     * bind engine dir, eksekusi binary LANGSUNG (bukan sh -c) agar stop bersih.
     */
    /** Env API key provider tersimpan (mis. OPENAI_API_KEY) untuk proses serve. */
    private fun providerEnv(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        fun put(names: List<String>, stored: String) {
            val v = runCatching { SecureStore.getAuth(context, stored) }.getOrNull()
            if (!v.isNullOrBlank()) names.forEach { out[it] = v }
        }
        put(listOf("OPENAI_API_KEY"), "openai")
        put(listOf("OPENCODE_API_KEY"), "opencode")
        put(listOf("ANTHROPIC_API_KEY"), "anthropic")
        put(listOf("GOOGLE_GENERATIVE_AI_API_KEY", "GEMINI_API_KEY"), "google")
        put(listOf("GROQ_API_KEY"), "groq")
        put(listOf("OPENROUTER_API_KEY"), "openrouter")
        put(listOf("MISTRAL_API_KEY"), "mistral")
        put(listOf("DEEPSEEK_API_KEY"), "deepseek")
        put(listOf("XAI_API_KEY"), "xai")
        return out
    }

    private fun serveArgv(port: Int, workDir: File, password: String, extraEnv: Map<String, String> = emptyMap()): List<String>? {
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
            "OPENCODE_SERVER_PASSWORD=$password",
        ) + extraEnv.map { (k, v) -> "$k=$v" } + listOf(
            "/opt/arkacode/opencode",
            "serve",
            "--port", port.toString(),
            "--hostname", EngineConfig.DEFAULT_HOST,
        )
    }
}
