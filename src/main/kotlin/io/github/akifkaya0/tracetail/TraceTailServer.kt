package io.github.akifkaya0.tracetail

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Receives the logs of the applications started from this project. Each application connects to a
 * loopback port and writes one JSON object per line.
 *
 * Reading never waits on the IDE. Each connection has its own thread that only appends to a bounded
 * inbox, and a full inbox drops its oldest line. A slow or closed view therefore cannot make an
 * application's logging block.
 */
@Service(Service.Level.PROJECT)
class TraceTailServer : Disposable {

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val inbox = ArrayDeque<String>()   // guarded by itself
    private val received = AtomicLong()
    private val dropped = AtomicLong()

    val port: Int get() = server.localPort

    init {
        thread(name = "Trace Tail listener", isDaemon = true) { acceptLoop() }
    }

    /** Takes every line received since the previous call, oldest first. */
    fun drain(): List<String> = synchronized(inbox) { inbox.toList().also { inbox.clear() } }

    fun stats() = Stats(port, connections.size, received.get(), dropped.get())

    private fun acceptLoop() {
        while (true) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return   // the server was closed on dispose
            }
            connections += socket
            thread(name = "Trace Tail reader ${socket.port}", isDaemon = true) { read(socket) }
        }
    }

    private fun read(socket: Socket) {
        try {
            socket.getInputStream().bufferedReader().forEachLine { if (it.isNotBlank()) offer(it) }
        } catch (_: IOException) {
            // The application stopped or the project is closing.
        } finally {
            connections -= socket
            closeQuietly(socket)
        }
    }

    private fun offer(line: String) {
        received.incrementAndGet()
        synchronized(inbox) {
            if (inbox.size == INBOX_CAPACITY) {
                inbox.removeFirst()
                dropped.incrementAndGet()
            }
            inbox.addLast(line)
        }
    }

    override fun dispose() {
        closeQuietly(server)
        connections.forEach(::closeQuietly)
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (_: IOException) {
        }
    }

    data class Stats(val port: Int, val connections: Int, val received: Long, val dropped: Long)

    private companion object {
        const val INBOX_CAPACITY = 20_000
    }
}
