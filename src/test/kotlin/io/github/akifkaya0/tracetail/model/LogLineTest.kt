package io.github.akifkaya0.tracetail.model

import io.github.akifkaya0.tracetail.model.Requests.at
import io.github.akifkaya0.tracetail.model.Requests.line
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How one received JSON line is read into the fields the view works with. */
class LogLineTest {

    @Test
    fun readsTheFieldsThatHaveAPlaceOfTheirOwn() {
        val line = line(
            """{"@timestamp":"2026-10-07T10:00:00.250Z","log.level":"WARN","message":"Ended with FAILURE","service.name":"stock",""" +
                """"trace.id":"t-1","span.id":"s-2","parent.id":"s-1","user.id":"u-7","event.action":"METHOD","phase":"END",""" +
                """"log.logger":"sample.stock.Warehouse","log.origin.file.line":42,""" +
                """"error.stack_trace":"java.lang.IllegalStateException: boom\n\tat sample.stock.Warehouse.take(Warehouse.java:42)"}""",
        )
        assertEquals(at("10:00:00.250"), line.time)
        assertEquals(Level.WARN, line.level)
        assertEquals("Ended with FAILURE", line.message)
        assertEquals("stock", line.app)
        assertEquals("t-1", line.trace)
        assertEquals("s-2", line.span)
        assertEquals("s-1", line.parent)
        assertEquals("u-7", line.user)
        assertEquals("METHOD", line.event)
        assertEquals(Phase.END, line.phase)
        assertEquals("sample.stock.Warehouse", line.logger)
        assertEquals(42, line.originLine)
        assertEquals("java.lang.IllegalStateException: boom\n\tat sample.stock.Warehouse.take(Warehouse.java:42)", line.stackTrace)
        assertEquals(emptyMap<String, String>(), line.fields)
    }

    @Test
    fun readsMicrometersIdNames() {
        val line = line("""{"message":"m","traceId":"t-1","spanId":"s-1"}""")
        assertEquals("t-1", line.trace)
        assertEquals("s-1", line.span)
    }

    @Test
    fun keepsTheOtherFieldsInTheOrderTheyCame() {
        val line = line(
            """{"message":"m","route":"/order","status":"200","durationMs":12.0,"retried":true,"cart":{"items":2},"note":null,"outcome":"SUCCESS"}""",
        )
        assertEquals(
            listOf("route" to "/order", "status" to "200", "durationMs" to "12.0", "retried" to "true", "cart" to """{"items":2}""", "outcome" to "SUCCESS"),
            line.fields.toList(),
        )
        assertEquals("200", line.status)
        assertEquals("SUCCESS", line.outcome)
        assertEquals(12L, line.durationMs)
    }

    @Test
    fun showsEveryLevelAsOneOfFour() {
        val levels = listOf("TRACE", "DEBUG", "info", "WARN", "ERROR", "FATAL", "NOTICE").map { line("""{"log.level":"$it"}""").level }
        assertEquals(listOf(Level.DEBUG, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR, Level.ERROR, Level.INFO), levels)
    }

    @Test
    fun titlesALineByItsEventOrElseItsMessage() {
        assertEquals("HTTP_IN", line("""{"message":"Started","event.action":"HTTP_IN"}""").title)
        assertEquals("Started BootApp in 1.2 seconds", line("""{"message":"Started BootApp in 1.2 seconds"}""").title)
    }

    @Test
    fun fillsInWhatALineLacks() {
        val before = System.currentTimeMillis()
        val line = line("""{"@timestamp":"yesterday","trace.id":"","phase":"BEGIN"}""")
        assertTrue(line.time >= before)
        assertEquals(Level.INFO, line.level)
        assertEquals("?", line.app)
        assertEquals("", line.message)
        assertNull(line.trace)
        assertNull(line.phase)
    }

    @Test
    fun skipsWhatIsNotAJsonObject() {
        for (text in listOf("", "Started BootApp", "[1,2]", "\"text\"", """{"message":""")) {
            assertNull(LogLine.parse(text), text)
        }
    }

    @Test
    fun givesEachLineItsOwnNumber() {
        val first = line("{}")
        val second = line("{}")
        assertTrue(first.seq < second.seq)
        assertEquals(first.seq, first.placed("s-1", null).seq)
    }

    @Test
    fun leavesTheStackTraceOutOfTheIndentedJson() {
        val json = line("""{"message":"failed","error.type":"java.lang.IllegalStateException","error.stack_trace":"java.lang.IllegalStateException"}""")
            .prettyJson()
        assertEquals("{\n  \"message\": \"failed\",\n  \"error.type\": \"java.lang.IllegalStateException\"\n}", json)
    }
}
