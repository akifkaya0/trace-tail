package io.github.akifkaya0.tracetail.model

import io.github.akifkaya0.tracetail.model.Requests.at
import io.github.akifkaya0.tracetail.model.Requests.line
import io.github.akifkaya0.tracetail.model.Requests.lines
import io.github.akifkaya0.tracetail.model.Requests.trace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How one request's lines become steps, nested by which step started which. */
class TraceTest {

    @Test
    fun nestsEachStepUnderTheStepThatStartedIt() {
        val trace = trace("order")
        val shape = trace.analyse(at("10:00:01"))
        assertEquals(listOf("a1"), shape.roots.map { it.id })
        assertEquals(listOf("a2"), shape.kids("a1"))
        assertEquals(listOf("a3", "a4", "a5"), shape.kids("a2"))
        assertEquals(listOf("b1"), shape.kids("a3"))
        assertEquals(listOf("b2"), shape.kids("a5"))
        assertEquals(15, trace.blockCount(shape.roots.single(), shape))
        assertEquals(0, shape.unfinished)
        assertEquals(0, shape.running)
    }

    @Test
    fun readsAStepFromItsStartAndEndLines() {
        val trace = trace("order")
        assertEquals(setOf("shop", "stock"), trace.apps)
        val call = trace.steps.getValue("a3")
        assertEquals(at("10:00:00.010"), call.start)
        assertEquals(at("10:00:00.040"), call.end)
        assertEquals("a2", call.parent)
        assertEquals("shop", call.app)
        assertEquals("HTTP_OUT", call.event)
        assertEquals(Phase.START, call.startLine?.phase)
        assertEquals(Phase.END, call.endLine?.phase)
        // the other app's end of the call, its ids under Micrometer's names
        val served = trace.steps.getValue("b1")
        assertEquals("stock", served.app)
        assertEquals("a3", served.parent)
        assertEquals(listOf("Reserved"), served.notes.map { it.message })
    }

    @Test
    fun startsAStepFromItsEndWhenItsStartIsMissing() {
        val trace = Trace("order")
        trace.add(lines("order").last())
        val step = trace.steps.getValue("b2")
        assertEquals(at("10:00:00.200"), step.start)
        assertEquals(at("10:00:00.240"), step.end)
        assertEquals("a5", step.parent)
        assertEquals("MQ_IN", step.event)
        // its parent is not in the request, so it is a root
        assertEquals(listOf(step), trace.analyse(at("10:00:01")).roots)
    }

    @Test
    fun countsTheLinesOfEachLevel() {
        val trace = trace("failed")
        assertEquals(listOf(0, 4, 4, 1), Level.entries.map(trace::count))
    }

    @Test
    fun marksAStepUnfinishedWhenTheStepThatCalledItHasEnded() {
        val trace = trace("unfinished")
        val shape = trace.analyse(at("10:00:01"))
        assertTrue(trace.steps.getValue("u2").unfinished)
        // a queue's consumer runs on its own, so it may still finish
        assertFalse(trace.steps.getValue("u4").unfinished)
        assertEquals(1, shape.unfinished)
        assertEquals(1, shape.running)
    }

    @Test
    fun marksAStepUnfinishedThirtySecondsAfterItStarted() {
        val trace = trace("unfinished")
        val shape = trace.analyse(at("10:00:00.030") + Trace.UNFINISHED_AFTER_MS + 1)
        assertTrue(trace.steps.getValue("u4").unfinished)
        assertEquals(2, shape.unfinished)
        assertEquals(0, shape.running)
    }

    @Test
    fun keepsTheStepsOfARequestThatIsStillRunningRunning() {
        val trace = Trace("unfinished")
        lines("unfinished").dropLast(1).forEach(trace::add)
        val shape = trace.analyse(at("10:00:01"))
        assertEquals(0, shape.unfinished)
        assertEquals(3, shape.running)
    }

    @Test
    fun dropsTheOldestLinesInsideTheStepsFirst() {
        val trace = Trace("job")
        trace.add(line("""{"message":"Started","trace.id":"job","span.id":"j1","event.action":"SCHEDULED","phase":"START"}"""))
        repeat(Trace.LINES_MAX) { trace.add(line("""{"message":"rate $it","log.level":"WARN","trace.id":"job","span.id":"j1"}""")) }
        // a tenth goes at once, so the next lines do not trim again
        val kept = Trace.LINES_MAX - Trace.LINES_MAX / 10
        assertEquals(kept, trace.lines.size)
        assertEquals(Trace.LINES_MAX + 1 - kept, trace.dropped)
        assertEquals("Started", trace.lines.first().message)
        assertEquals("rate ${Trace.LINES_MAX - kept + 1}", trace.lines[1].message)
        val notes = trace.steps.getValue("j1").notes
        assertEquals(kept - 1, notes.size)
        assertEquals(trace.lines[1], notes.first())
        // the counts keep the dropped lines
        assertEquals(Trace.LINES_MAX, trace.count(Level.WARN))
    }

    @Test
    fun dropsTheOldestFinishedStepsOfARequestOfStepsAlone() {
        val trace = jobOfSteps(2_500)
        // 5,001 lines are 501 too many: 251 steps go, START and END together
        assertEquals(502, trace.dropped)
        assertEquals(4_499, trace.lines.size)
        // the main step, which the others hang under, stays
        assertEquals(listOf("j0", "j252"), trace.steps.keys.take(2))
        assertEquals(trace.lines.size, trace.steps.size * 2 - 1)
    }

    @Test
    fun keepsTheNewestLinesInsideTheSteps() {
        val trace = jobOfSteps(2_499)
        trace.add(line("""{"message":"No rate","log.level":"WARN","trace.id":"job","span.id":"j0"}"""))
        trace.add(methodLine(2_500, "START"))
        // the warning is the only line inside a step, but it is new: 251 old steps go instead
        assertEquals("No rate", trace.steps.getValue("j0").notes.single().message)
        assertEquals(502, trace.dropped)
    }

    @Test
    fun keepsAStepThatHoldsOneOfTheNewestLines() {
        val trace = jobOfSteps(2_499)
        // a line of the oldest step comes late, after the step ended
        trace.add(line("""{"message":"Late","trace.id":"job","span.id":"j1"}"""))
        trace.add(methodLine(2_500, "START"))
        assertEquals("Late", trace.steps.getValue("j1").notes.single().message)
        assertEquals(listOf("j0", "j1", "j253"), trace.steps.keys.take(3))
    }

    @Test
    fun dropsAStepLeftWithNoLine() {
        val trace = Trace("r")
        trace.add(line("""{"message":"Started","trace.id":"r","span.id":"a1","event.action":"HTTP_IN","phase":"START"}"""))
        trace.add(line("""{"message":"outside any step","trace.id":"r"}"""))
        repeat(Trace.LINES_MAX - 1) { trace.add(line("""{"message":"n $it","trace.id":"r","span.id":"a1"}""")) }
        // the line outside any step was the oldest to go, and the step that held it went with it
        assertEquals(listOf("a1"), trace.steps.keys.toList())
        assertEquals(listOf("a1"), trace.analyse(0).roots.map { it.id })
    }

    /** A job whose main step `j0` runs [count] finished method steps, `j1` on. */
    private fun jobOfSteps(count: Int) = Trace("job").also { trace ->
        trace.add(line("""{"message":"Started","trace.id":"job","span.id":"j0","event.action":"SCHEDULED","phase":"START"}"""))
        for (i in 1..count) {
            trace.add(methodLine(i, "START"))
            trace.add(methodLine(i, "END"))
        }
    }

    private fun methodLine(i: Int, phase: String) =
        line("""{"message":"m","trace.id":"job","span.id":"j$i","parent.id":"j0","event.action":"METHOD","phase":"$phase"}""")

    private fun Trace.Shape.kids(id: String) = kids[id].orEmpty().map { it.id }
}
