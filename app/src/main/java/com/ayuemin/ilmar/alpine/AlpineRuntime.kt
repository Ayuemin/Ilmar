package com.ayuemin.ilmar.alpine

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One explicitly shared environment; never receives provider/GitHub credentials or host HOME. */
internal class AlpineRuntime(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "alpine")
    private val root = File(directory, "rootfs")
    private val native = app.applicationInfo.nativeLibraryDir?.let(::File)
    companion object {
        private val mutation = Mutex()
        const val VERSION = "3.24.2"
        const val BRANCH = "v3.24"
        const val SHA256 = "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773"
        const val URL = "https://dl-cdn.alpinelinux.org/alpine/$BRANCH/releases/aarch64/alpine-minirootfs-$VERSION-aarch64.tar.gz"
        const val ID = "alpine_shell"
    }
    fun supported() = native != null && Build.SUPPORTED_ABIS.contains("arm64-v8a") &&
        listOf("libproot_exec.so", "libproot_loader.so", "libtalloc.so", "libilmar_process.so").all { File(native, it).isFile }
    fun installed() = File(root, ".ilmar-ready").isFile && supported()
    fun hasFiles() = directory.exists()
    private val preferences = app.getSharedPreferences("ilmar_alpine", Context.MODE_PRIVATE)
    fun dnsSetting() = preferences.getString("dns", "").orEmpty()
    fun setDns(value: String) {
        val values = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        require(values.size <= 4 && values.all { address ->
            if (':' in address) address.matches(Regex("[0-9a-fA-F:.]+")) && runCatching { java.net.InetAddress.getByName(address) }.isSuccess
            else address.split('.').let { it.size == 4 && it.all { piece -> piece.isNotBlank() && piece.all(Char::isDigit) && (piece.toIntOrNull() ?: -1) in 0..255 } }
        }) { "DNS: до четырёх IPv4/IPv6 адресов через пробел; пусто — сеть Android" }
        check(preferences.edit().putString("dns", values.joinToString(" ")).commit())
    }
    private fun updateDns(filesystem: File) {
        val configured = dnsSetting().split(' ').filter { it.isNotBlank() }
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val automatic = manager?.activeNetwork?.let { manager.getLinkProperties(it) }?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
        val addresses = configured.ifEmpty { automatic }.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val file = File(filesystem, "etc/resolv.conf")
        check(file.parentFile!!.canonicalPath.startsWith(filesystem.canonicalPath + File.separator))
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) check(file.delete())
        file.writeText(addresses.joinToString("\n", postfix = "\n") { "nameserver $it" })
    }

    suspend fun install(): String = withContext(Dispatchers.IO) { mutation.withLock {
        check(supported()) { "Alpine требует ARM64 и сборку APK с PRoot" }
        check(!root.exists()) { "Окружение уже установлено; существующие данные не заменены" }
        directory.mkdirs()
        directory.listFiles().orEmpty().filter { it.name.startsWith("install-") || it.name.startsWith("rootfs-") && it.name.endsWith(".tar.gz") }.forEach(RootfsArchive::remove)
        val staging = File(directory, "install-${java.util.UUID.randomUUID()}").apply { mkdir() }
        val archive = File(directory, "rootfs-${java.util.UUID.randomUUID()}.tar.gz")
        try {
            download(archive)
            val digest = MessageDigest.getInstance("SHA-256")
            archive.inputStream().use { input -> val bytes = ByteArray(8192); while (true) { currentCoroutineContext().ensureActive(); val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
            check(digest.digest().joinToString("") { "%02x".format(it) } == SHA256) { "SHA-256 Alpine не совпадает; установка остановлена" }
            val job = currentCoroutineContext()
            GZIPInputStream(archive.inputStream()).use { RootfsArchive.extract(it, staging) { job.ensureActive() } }
            listOf("root", "tmp", "proc", "dev", "sys", "run", "var/cache/apk").forEach { File(staging, it).mkdirs() }
            fun config(path: String, value: String) {
                val file = File(staging, path)
                check(file.parentFile!!.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Небезопасный каталог rootfs" }
                // Replace an archive link itself, never write through its target on the Android host.
                if (java.nio.file.Files.isSymbolicLink(file.toPath())) check(file.delete())
                file.writeText(value)
            }
            config("etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            config("etc/apk/repositories", "https://dl-cdn.alpinelinux.org/alpine/$BRANCH/main\nhttps://dl-cdn.alpinelinux.org/alpine/$BRANCH/community\n")
            val smoke = runProcess(staging, "cat /etc/alpine-release; /bin/busybox echo ILMAR_ALPINE_OK", "/root", 30)
            check(smoke.optInt("exit_code", -1) == 0 && smoke.optString("output").contains("ILMAR_ALPINE_OK")) { "PRoot smoke test не прошёл; окружение не активировано" }
            java.nio.file.Files.write(File(staging, ".ilmar-ready").toPath(), "$VERSION\n$SHA256\n".toByteArray(), java.nio.file.StandardOpenOption.CREATE_NEW)
            currentCoroutineContext().ensureActive()
            check(staging.renameTo(root)) { "Не удалось активировать Alpine" }
            "Alpine $VERSION установлен; PRoot smoke test пройден"
        } finally { archive.delete(); RootfsArchive.remove(staging) }
    } }

    suspend fun remove() = withContext(Dispatchers.IO) { mutation.withLock { RootfsArchive.remove(directory) } }

    suspend fun execute(command: String, workdir: String = "/root", seconds: Int = 60, authorized: () -> Boolean = { true }): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank() && command.length <= 32768 && '\u0000' !in command) { "Команда: 1–32768 символов" }
        require(workdir.startsWith('/') && workdir.length <= 1024 && '\u0000' !in workdir) { "Нужен абсолютный путь внутри Alpine" }
        require(seconds in 1..300) { "Таймаут: 1–300 секунд" }
        mutation.withLock {
            check(installed()) { "Установи Alpine в настройках инструментов" }
            check(authorized()) { "Разрешение Alpine отозвано" }
            runProcess(root, command, workdir, seconds).toString()
        }
    }

    private suspend fun runProcess(filesystem: File, command: String, workdir: String, seconds: Int): JSONObject = coroutineScope {
        val native = native ?: error("Native PRoot не включён в этот APK")
        updateDns(filesystem)
        val args = listOf(File(native, "libilmar_process.so").path, File(native, "libproot_exec.so").path,
            "--rootfs=${filesystem.path}", "--bind=/dev", "--bind=/proc", "--bind=/sys", "--bind=/dev/urandom:/dev/random",
            "-w", workdir, "-0", "--link2symlink", "--kill-on-exit", "-L", "/bin/sh", "-c", command)
        val builder = ProcessBuilder(args).redirectErrorStream(true)
        builder.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", native.path)
            put("PROOT_LOADER", File(native, "libproot_loader.so").path)
            put("PROOT_TMP_DIR", File(filesystem, "tmp").path)
            put("PROOT_NO_SECCOMP", "1")
            put("HOME", "/root"); put("TMPDIR", "/tmp"); put("LANG", "C.UTF-8"); put("TERM", "dumb")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        }
        val process = builder.start()
        process.outputStream.close() // Commands are non-interactive; no indefinite stdin prompts.
        val group = AtomicInteger(0)
        var truncated = false
        val capture = java.io.ByteArrayOutputStream()
        val output = async(Dispatchers.IO) {
            process.inputStream.buffered().use { input ->
                val first = StringBuilder()
                while (true) { val ch = input.read(); check(ch >= 0 && first.length < 80) { "Не получен PID launcher" }; if (ch == 10) break; first.append(ch.toChar()) }
                check(first.startsWith("ILMAR_PROCESS_GROUP=")) { "Неверный launcher handshake" }
                val pid = first.toString().substringAfter('=').toInt(); check(pid > 1); group.set(pid)
                val bytes = ByteArray(8192)
                while (true) {
                    val n = input.read(bytes); if (n < 0) break
                    val keep = minOf(n, 65536 - capture.size())
                    if (keep > 0) capture.write(bytes, 0, keep)
                    if (keep < n) truncated = true
                }
            }
        }
        var timeout = false
        var exit = -1
        try {
            val completed = withTimeoutOrNull(seconds * 1000L) {
                while (process.isAlive) delay(50)
                exit = process.exitValue()
                output.await()
                true
            }
            timeout = completed == null
        } finally {
            // Kill the session even if the shell exited but a child kept its output pipe open.
            if (group.get() > 1) runCatching { Os.kill(-group.get(), OsConstants.SIGKILL) }
            process.destroyForcibly()
            output.cancel()
            runCatching { process.inputStream.close() }
        }
        JSONObject().put("exit_code", if (timeout) -1 else exit).put("timed_out", timeout)
            .put("output", capture.toString("UTF-8")).put("truncated", truncated).put("untrusted", true)
    }

    private suspend fun download(file: File): Unit = suspendCancellableCoroutine { continuation ->
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(120, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url(URL).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Не удалось скачать Alpine")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { reply ->
                        check(reply.isSuccessful) { "Alpine: HTTP ${reply.code}" }
                        val body = reply.body ?: error("Пустой rootfs")
                        check(continuation.isActive) { "Отмена" }
                        body.byteStream().use { input -> file.outputStream().use { out ->
                            val bytes = ByteArray(8192); var size = 0L
                            while (true) { if (!continuation.isActive) throw IOException("Отмена"); val n = input.read(bytes); if (n < 0) break; size += n; check(size <= 16L * 1024 * 1024) { "Rootfs превышает 16 МиБ" }; out.write(bytes, 0, n) }
                        } }
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                finally { if (continuation.isCancelled) file.delete() }
            }
        })
    }
}
