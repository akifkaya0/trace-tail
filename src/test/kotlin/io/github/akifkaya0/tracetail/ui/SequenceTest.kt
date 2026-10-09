package io.github.akifkaya0.tracetail.ui

import io.github.akifkaya0.tracetail.model.Requests.at
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.Requests.line
import io.github.akifkaya0.tracetail.model.Requests.lines
import io.github.akifkaya0.tracetail.model.TraceModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A request drawn as a sequence diagram, checked through the Mermaid text that Copy as Mermaid gives. */
class SequenceTest {

    @Test
    fun drawsARequestAcrossApps() {
        // stock's HTTP_IN is the far end of shop's call: one arrow, not two
        assertEquals(
            """
            sequenceDiagram
                actor P0 as User
                box rgb(244,247,250) shop
                participant P1 as shop
                participant P2 as OrderService
                end
                participant P3 as stock
                participant P4 as payments
                participant P5 as Queue
                P0->>P1: /order
                activate P1
                P1->>P2: place()
                activate P2
                P2->>P3: reserve
                activate P3
                Note right of P3: Reserved item=chair count=2
                P3-->>P2: 200 SUCCESS · 30 ms
                deactivate P3
                P2->>P4: charge
                activate P4
                P4-->>P2: 200 SUCCESS · 50 ms
                deactivate P4
                P2->>P5: shipping
                activate P5
                P5-->>P2: queued · 5 ms
                deactivate P5
                P2-->>P1: returned · 101 ms
                deactivate P2
                P1-->>P0: 200 SUCCESS · 110 ms
                deactivate P1
                P5-)P3: shipping
                activate P3
                P3-->>P5: ack · 40 ms
                deactivate P3
            """.trimIndent(),
            mermaid("order"),
        )
    }

    @Test
    fun drawsAFailedRequest() {
        // a note's # and ; would break the Mermaid text, so they are left out
        assertEquals(
            """
            sequenceDiagram
                actor P0 as User
                participant P1 as shop
                box rgb(244,247,250) stock
                participant P2 as stock
                participant P3 as Warehouse
                end
                P0->>P1: /order
                activate P1
                P1->>P2: reserve
                activate P2
                P2->>P3: take()
                activate P3
                Note right of P3: Shelf 4 is empty 0 left
                P3-->>P2: ✕ IllegalStateException · 3 ms
                deactivate P3
                P2-->>P1: 500 FAILURE · 12 ms
                deactivate P2
                P1-->>P0: 502 FAILURE · 15 ms
                deactivate P1
            """.trimIndent(),
            mermaid("failed"),
        )
    }

    @Test
    fun marksAStepThatWillNotFinish() {
        assertEquals(
            """
            sequenceDiagram
                actor P0 as User
                box rgb(244,247,250) shop
                participant P1 as shop
                participant P2 as OrderService
                end
                participant P3 as Queue
                participant P4 as stock
                P0->>P1: /order
                activate P1
                P1->>P2: place()
                activate P2
                P1->>P3: shipping
                activate P3
                P3-->>P1: queued · 5 ms
                deactivate P3
                P3-)P4: shipping
                activate P4
                P1-->>P0: 200 SUCCESS · 100 ms
                deactivate P1
                Note over P2: unfinished
            """.trimIndent(),
            mermaid("unfinished"),
        )
    }

    @Test
    fun drawsACallAtTheAppThatTookIt() {
        // shop calls its target stock, but the run configuration that took the call is named stock-dev
        val renamed = lines("order").map {
            if (it.app == "stock") line(it.json.replace("\"service.name\":\"stock\"", "\"service.name\":\"stock-dev\"")) else it
        }
        assertEquals(mermaid("order").replace("P3 as stock", "P3 as stock-dev"), mermaid("order", renamed))
    }

    @Test
    fun drawsTheUserAndTheLdapAndMailSystems() {
        // STOMP frames come from the user and go back to them; a heartbeat has no destination
        fun step(ms: Int, span: String, event: String, phase: String, parent: String?, fields: String) = line(
            """{"@timestamp":"2026-10-07T10:00:00.0${ms}Z","service.name":"chat","trace.id":"ws","span.id":"$span",""" +
                (parent?.let { """"parent.id":"$it",""" } ?: "") +
                """"event.action":"$event","phase":"$phase"$fields}""",
        )
        fun end(outcome: String) = ""","outcome":"$outcome","durationMs":"5""""
        val lines = listOf(
            step(10, "w1", "WS_IN", "START", null, ""","command":"SEND","destination":"/app/history""""),
            step(20, "w2", "LDAP_OUT", "START", "w1", ""","op":"search""""),
            step(25, "w2", "LDAP_OUT", "END", "w1", end("SUCCESS")),
            step(30, "w3", "MAIL_OUT", "START", "w1", ""","op":"send""""),
            step(35, "w3", "MAIL_OUT", "END", "w1", end("ERROR")),
            step(40, "w4", "WS_OUT", "START", "w1", ""","command":"MESSAGE","destination":"/user/queue/history""""),
            step(45, "w4", "WS_OUT", "END", "w1", end("SUCCESS")),
            step(50, "w1", "WS_IN", "END", null, end("SUCCESS")),
            step(60, "w5", "WS_IN", "START", null, ""","command":"HEARTBEAT""""),
            step(65, "w5", "WS_IN", "END", null, end("REJECTED")),
        )
        assertEquals(
            """
            sequenceDiagram
                actor P0 as User
                participant P1 as chat
                participant P2 as ldap
                participant P3 as mail
                P0->>P1: SEND /app/history
                activate P1
                P1->>P2: search
                activate P2
                P2-->>P1: SUCCESS · 5 ms
                deactivate P2
                P1->>P3: send
                activate P3
                P3-->>P1: ERROR · 5 ms
                deactivate P3
                P1->>P0: MESSAGE /user/queue/history
                activate P0
                P0-->>P1: SUCCESS · 5 ms
                deactivate P0
                P1-->>P0: SUCCESS · 5 ms
                deactivate P1
                P0->>P1: HEARTBEAT
                activate P1
                P1-->>P0: REJECTED · 5 ms
                deactivate P1
            """.trimIndent(),
            mermaid("ws", lines),
        )
    }

    /** The request's diagram as the Sequence tab draws it a second after the request started. */
    private fun mermaid(name: String, lines: List<LogLine> = lines(name)): String {
        val model = TraceModel()
        model.add(lines)
        val trace = model.traces.getValue(name)
        trace.analyse(at("10:00:01"))
        return Sequence(trace) { it in model.apps }.mermaid()
    }
}
