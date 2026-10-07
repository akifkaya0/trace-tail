package io.github.akifkaya0.tracetail.model

import io.github.akifkaya0.tracetail.model.Requests.at
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

    private fun Trace.Shape.kids(id: String) = kids[id].orEmpty().map { it.id }
}
