package io.github.akifkaya0.tracetail.model

import io.github.akifkaya0.tracetail.model.Requests.line
import io.github.akifkaya0.tracetail.model.Requests.lines
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The received lines grouped by request, and the level filter applied to them. */
class TraceModelTest {

    private val model = TraceModel()

    @Test
    fun groupsTheLinesByRequest() {
        val change = model.add(lines("order") + lines("levels"))
        assertFalse(change.reset)
        assertEquals(24, change.admitted.size)
        assertEquals(listOf("order", "levels"), change.changed.map { it.id })
        assertEquals(15, model.traces.getValue("order").lines.size)
        // the last line of `levels` belongs to no request: it shows only in the Flat and Raw tabs
        assertEquals(8, model.traces.getValue("levels").lines.size)
        assertEquals(listOf("shop", "stock"), model.apps.toList())
        assertEquals(1, model.appIndex("stock"))
    }

    @Test
    fun hangsALineUnderTheNearestStepTheLevelShows() {
        model.setLevel(null, Level.INFO)
        val change = model.add(lines("levels"))
        assertEquals(
            listOf("Started", "Checked the stock", "No price, using the list price", "Finished", "Refreshed the catalog"),
            change.admitted.map { it.message },
        )
        val step = model.traces.getValue("levels").steps.values.single()
        assertEquals("s1", step.id)
        assertEquals(listOf("Checked the stock", "No price, using the list price"), step.notes.map { it.message })
    }

    @Test
    fun keepsTheMainStepsStartAndEndWhateverTheLevel() {
        model.setLevel(null, Level.WARN)
        val change = model.add(lines("levels"))
        // the Flat tab shows only what passes the level
        assertEquals(listOf("No price, using the list price"), change.admitted.map { it.message })
        val trace = model.traces.getValue("levels")
        assertEquals(listOf(Phase.START, null, Phase.END), trace.lines.map { it.phase })
        val step = trace.steps.values.single()
        assertEquals("s1", step.id)
        assertEquals(listOf("No price, using the list price"), step.notes.map { it.message })
    }

    @Test
    fun showsNoRequestThatHasNoLineAtTheLevel() {
        model.setLevel(null, Level.ERROR)
        val change = model.add(lines("levels"))
        assertEquals(emptyList<LogLine>(), change.admitted)
        assertEquals(emptyList<Trace>(), change.changed.toList())
        assertTrue(model.traces.isEmpty())
    }

    @Test
    fun appliesANewLevelToTheLinesItKeeps() {
        model.add(lines("levels"))
        model.setLevel(null, Level.WARN)
        val warn = model.rebuild()
        assertTrue(warn.reset)
        assertEquals(1, warn.admitted.size)
        assertEquals(3, model.traces.getValue("levels").lines.size)
        model.setLevel(null, Level.DEBUG)
        assertEquals(9, model.rebuild().admitted.size)
        assertEquals(8, model.traces.getValue("levels").lines.size)
    }

    @Test
    fun keepsTheLastLinesOnly() {
        model.add((1..TraceModel.KEEP_MAX + 1).map { line("""{"message":"line $it"}""") })
        val rebuilt = model.rebuild().admitted
        assertEquals(TraceModel.KEEP_MAX, rebuilt.size)
        assertEquals("line 2", rebuilt.first().message)
    }

    @Test
    fun setsTheLevelOfOneApp() {
        model.add(lines("order"))
        model.setLevel("stock", Level.WARN)
        model.rebuild()
        assertEquals(setOf("shop"), model.traces.getValue("order").apps)
        assertTrue(model.isLevel("stock", Level.WARN))
        assertTrue(model.isLevel("shop", Level.DEBUG))
        assertFalse(model.isLevel(null, Level.DEBUG))
        model.setLevel(null, Level.INFO)
        assertTrue(model.isLevel(null, Level.INFO))
        assertTrue(model.isLevel("stock", Level.INFO))
    }

    @Test
    fun dropsTheOldestRequests() {
        val change = model.add((1..TraceModel.TRACE_MAX + 1).map { line("""{"message":"m","trace.id":"r$it"}""") })
        assertEquals(TraceModel.TRACE_MAX, model.traces.size)
        assertEquals("r2", model.traces.keys.first())
        assertEquals(listOf("r1"), change.removed.map { it.id })
        assertFalse(change.changed.any { it.id == "r1" })
    }

    @Test
    fun forgetsEverythingOnClear() {
        model.add(lines("order"))
        val change = model.clear()
        assertTrue(change.reset)
        assertTrue(change.admitted.isEmpty())
        assertTrue(model.traces.isEmpty())
        assertTrue(model.rebuild().admitted.isEmpty())
    }
}
