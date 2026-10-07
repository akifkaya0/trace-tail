package io.github.akifkaya0.tracetail

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
                first.write("a1\n\n")
                second.write("b1\n   \nçağrı ışık\n")
                first.write("a2\n")
                val lines = take(4)
                assertEquals(setOf("a1", "a2", "b1", "çağrı ışık"), lines.toSet())
                assertEquals(listOf("a1", "a2"), lines.filter { it.startsWith("a") })
                // blank lines are skipped
                assertEquals(TraceTailServer.Stats(server.port, 2, 4, 0), server.stats())
            }
        }
        waitUntil { server.stats().connections == 0 }
    }

    @Test
    fun dropsTheOldestLinesWhileTheViewDoesNotTakeThem() {
        val count = INBOX_CAPACITY + 5_000
        connect().use { it.write((1..count).joinToString("") { i -> "$i\n" }) }
        waitUntil { server.stats().received == count.toLong() }
        assertEquals(5_000, server.stats().dropped)
        assertEquals((5_001..5_010).map(Int::toString), server.drain(10))
        val rest = server.drain(Int.MAX_VALUE)
        assertEquals(INBOX_CAPACITY - 10, rest.size)
        assertEquals(count.toString(), rest.last())
        assertEquals(emptyList<String>(), server.drain(10))
    }

    @Test
    fun closesItsPortAndConnectionsOnDispose() {
        connect().use { socket ->
            socket.write("a1\n")
            take(1)
            server.dispose()
            assertEquals(-1, socket.getInputStream().read())
        }
        assertThrows(ConnectException::class.java) { connect() }
    }

    private fun connect() = Socket(InetAddress.getLoopbackAddress(), server.port)

    private fun Socket.write(text: String) = getOutputStream().write(text.toByteArray())

    /** Takes [count] lines from the server as they arrive. */
    private fun take(count: Int): List<String> {
        val lines = ArrayList<String>()
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
