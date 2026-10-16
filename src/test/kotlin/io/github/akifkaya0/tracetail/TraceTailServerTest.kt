package io.github.akifkaya0.tracetail

import io.github.akifkaya0.tracetail.model.LogLine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket

/** The receiver, with sockets standing in for the applications' agents. */
class TraceTailServerTest {

    private val server = TraceTailServer()

    @AfterEach
    fun dispose() = server.dispose()

    @Test
    fun readsTheLinesOfEveryConnection() {
        connect().use { first ->
            connect().use { second ->
                first.write(json("a1") + "\n")
                second.write(json("b1") + "   \nnot json\n" + json("çağrı ışık"))
                first.write(json("a2"))
                val lines = take(4)
                // blank lines and lines that are not JSON objects are skipped
                assertEquals(setOf("a1", "a2", "b1", "çağrı ışık"), lines.map { it.message }.toSet())
                assertEquals(listOf("a1", "a2"), lines.map { it.message }.filter { it.startsWith("a") })
                // the views find lines by their numbers, which follow the order they are taken in
                assertEquals(lines.map { it.seq }.sorted(), lines.map { it.seq })
                assertEquals(TraceTailServer.Stats(server.port, 2), server.stats())
            }
        }
        waitUntil { server.stats().connections == 0 }
    }

    @Test
    fun dropsTheOldestLinesWhileTheViewDoesNotTakeThem() {
        val count = INBOX_CAPACITY + 5_000
        connect().use { it.write((1..count).joinToString("") { i -> json("$i") }) }
        waitUntil { server.waiting() == INBOX_CAPACITY && server.stats().connections == 0 }
        assertEquals((5_001..5_010).map(Int::toString), server.drain(10).map { it.message })
        val rest = server.drain(Int.MAX_VALUE)
        assertEquals(INBOX_CAPACITY - 10, rest.size)
        assertEquals(count.toString(), rest.last().message)
        assertEquals(emptyList<LogLine>(), server.drain(10))
    }

    @Test
    fun closesItsPortAndConnectionsOnDispose() {
        connect().use { socket ->
            socket.write(json("a1"))
            take(1)
            server.dispose()
            assertEquals(-1, socket.getInputStream().read())
        }
        assertThrows(ConnectException::class.java) { connect() }
    }

    private fun connect() = Socket(InetAddress.getLoopbackAddress(), server.port)

    private fun Socket.write(text: String) = getOutputStream().write(text.toByteArray())

    /** A line as the agent writes it, with only a message. */
    private fun json(message: String) = "{\"message\":\"$message\"}\n"

    /** Takes [count] lines from the server as they arrive. */
    private fun take(count: Int): List<LogLine> {
        val lines = ArrayList<LogLine>()
        waitUntil {
            lines += server.drain(count - lines.size)
            lines.size == count
        }
        return lines
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail<Unit>("timed out")
            Thread.sleep(10)
        }
    }

    private companion object {
        /** The server's own limit, which it keeps private. */
        const val INBOX_CAPACITY = 20_000
        const val TIMEOUT_MILLIS = 30_000L
    }
}
