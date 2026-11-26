package com.boox.day.api.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.TimeUnit

/** One browser authorization attempt. It listens only on IPv4 loopback and never logs requests. */
internal class ChatGptLoopback : Closeable {
    private val server = ServerSocketChannel.open().socket()
    init {
        try {
            server.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 8)
            server.soTimeout = 500
        } catch (failure: IOException) {
            server.close()
            throw failure
        }
    }

    val port: Int get() = server.localPort

    suspend fun await(attempt: ChatGptProtocol.Attempt): ChatGptProtocol.Callback = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        var callback: ChatGptProtocol.Callback? = null
        while (callback == null && System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            callback = runInterruptible { handleNext(attempt) }
        }
        callback ?: throw ChatGptFailure("ChatGPT sign-in timed out. Try again.")
    }

    private fun handleNext(attempt: ChatGptProtocol.Attempt): ChatGptProtocol.Callback? {
        val socket = try {
            server.accept()
        } catch (_: SocketTimeoutException) {
            return null
        }
        socket.use {
            val callback = try {
                readCallback(socket, attempt)
            } catch (_: IOException) {
                null
            } catch (_: ChatGptFailure) {
                null
            }
            // A browser closing the tab after its callback must not discard an authenticated code.
            runCatching { reply(socket, callback != null) }
            return callback
        }
    }

    private fun readCallback(socket: Socket, attempt: ChatGptProtocol.Attempt): ChatGptProtocol.Callback? {
        socket.soTimeout = 750
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val request = reader.readLimitedLine(8192) { System.nanoTime() < deadline } ?: return null
        val parts = request.split(' ')
        if (parts.size != 3 || parts[0] != "GET" || parts[2] !in setOf("HTTP/1.1", "HTTP/1.0")) return null
        var host: String? = null
        repeat(48) {
            val line = reader.readLimitedLine(4096) { System.nanoTime() < deadline } ?: return null
            if (line.isEmpty()) {
                return if (host == "127.0.0.1:$port") attempt.callback(parts[1]) else null
            }
            if (line.substringBefore(':').equals("Host", ignoreCase = true)) {
                if (host != null) return null
                host = line.substringAfter(':', "").trim()
            }
        }
        return null
    }

    private fun reply(socket: Socket, valid: Boolean) {
        val status = if (valid) "200 OK" else "400 Bad Request"
        val body = if (valid) "Return to BooxDay to finish connecting." else "Invalid sign-in callback."
        val response = "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
    }

    override fun close() = server.close()
}
