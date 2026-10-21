package com.ayuemin.ilmar.github

import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException

/** Ephemeral localhost credential helper: no PAT in shell text, environment, remote URL or disk. */
internal object GitCredentialBridge {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    suspend fun <T> withCredential(token: String, repository: String, operation: suspend (String) -> T): T = coroutineScope {
        require(token.isNotBlank() && token.none { it == '\r' || it == '\n' })
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val nonce = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString()
        val endpoint = "http://127.0.0.1:${server.localPort}/$nonce"
        // Git supplies protocol/host/path on stdin; never offer the credential for another remote.
        val helper = "!f() { [ \"\$1\" = get ] || return 0; protocol=; host=; path=; " +
            "while IFS='=' read -r key value; do [ -n \"\$key\" ] || break; case \"\$key\" in protocol) protocol=\$value;; host) host=\$value;; path) path=\$value;; esac; done; " +
            "[ \"\$protocol\" = https ] && [ \"\$host\" = github.com ] && [ \"\$path\" = " + quote("$repository.git") + " ] || return 1; " +
            "/bin/busybox wget -q -O - --post-data='' " + quote(endpoint) + "; }; f"
        val worker = launch(Dispatchers.IO) {
            while (isActive) {
                val socket = try { server.accept() } catch (e: SocketException) { if (isActive) throw e else break }
                socket.use { client ->
                    client.soTimeout = 2000
                    try {
                        val input = client.getInputStream()
                        fun line(): String {
                            val text = StringBuilder()
                            while (true) { val byte = input.read(); check(byte >= 0 && text.length < 4096); if (byte == 10) return text.toString().trimEnd('\r'); text.append(byte.toChar()) }
                        }
                        val first = line()
                        var contentLength = "0"; var ended = false
                        repeat(50) {
                            if (!ended) {
                                val header = line()
                                if (header.isEmpty()) ended = true
                                else if (header.startsWith("Content-Length:", true)) contentLength = header.substringAfter(':').trim()
                            }
                        }
                        val accepted = ended && first == "POST /$nonce HTTP/1.1" && contentLength == "0"
                        val body = if (accepted) "username=x-access-token\npassword=$token\n\n".toByteArray() else ByteArray(0)
                        val head = "HTTP/1.1 ${if (accepted) "200 OK" else "403 Forbidden"}\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                    } catch (_: java.io.IOException) { /* Malformed/abandoned local clients receive no credential. */ }
                    catch (_: IllegalStateException) { }
                }
            }
        }
        try { operation(helper) } finally {
            worker.cancel(); server.close()
            withContext(NonCancellable) { worker.join() }
        }
    }
}
