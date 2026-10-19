package io.github.akifkaya0.tracetail.model

import io.github.akifkaya0.tracetail.model.Requests.line
import io.github.akifkaya0.tracetail.model.Requests.lines
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

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
    @Timeout(5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun keepsTheLinesOfAHiddenStepThatIsItsOwnParent() {
        model.setLevel(null, Level.WARN)
        model.add(listOf(line("""{"message":"Started","trace.id":"r","span.id":"s1","parent.id":"s1","phase":"START"}""")))
        val change = model.add(listOf(line("""{"message":"Inside","log.level":"WARN","trace.id":"r","span.id":"s1"}""")))
        assertEquals("s1", change.admitted.single().span)
    }

    @Test
    @Timeout(5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun keepsTheLinesOfHiddenStepsThatAreEachOthersParents() {
        model.setLevel(null, Level.WARN)
        model.add(
            listOf(
                line("""{"message":"a","trace.id":"r1","span.id":"a","parent.id":"b","phase":"START"}"""),
                line("""{"message":"b","trace.id":"r2","span.id":"b","parent.id":"a","phase":"START"}"""),
            ),
        )
        val change = model.add(listOf(line("""{"message":"Inside","log.level":"WARN","trace.id":"r2","span.id":"b"}""")))
        assertEquals("b", change.admitted.single().span)
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
    fun keepsEveryLineForANewLevel() {
        model.add((1..12_000).map { line("""{"message":"line $it","trace.id":"r${it / 10}"}""") })
        model.setLevel(null, Level.INFO)
        val rebuilt = model.rebuild()
        assertEquals(12_000, rebuilt.admitted.size)
        assertEquals(12_000, model.traceLines)
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
    fun deletesTheWholeOldestRequestOfARunPastItsLimit() {
        val model = TraceModel(measure = { 100 }, limit = { 1_000 })
        model.add(listOf(line(1, "old", "a"), line(2, "old", "b"), line(1, "old", "c")), now = 0)
        model.add((1..8).map { line(1, "new", "n$it") }, now = 0)
        // the eleventh line of run 1 is over its limit; the old request goes from both runs
        val change = model.add(listOf(line(1, "new", "n9")), now = TraceModel.ACTIVE_MS)
        assertEquals(listOf("old"), change.removed.map { it.id })
        assertEquals(listOf("a", "b", "c"), change.deleted.map { it.message })
        assertEquals(listOf("n9"), change.admitted.map { it.message })
        assertEquals(listOf("new"), model.traces.keys.toList())
        assertFalse(model.hasRun(2))
        assertEquals(9, model.kept)
    }

    @Test
    fun deletesOnlyTheOldestLinesOfARequestThatIsStillGettingLines() {
        val model = TraceModel(measure = { 100 }, limit = { 1_000 })
        model.add((1..10).map { line(1, "r", "m$it") }, now = 0)
        val change = model.add(listOf(line(1, "r", "m11"), line(1, "r", "m12")), now = TraceModel.ACTIVE_MS - 1)
        assertEquals(listOf("m1", "m2"), change.deleted.map { it.message })
        val trace = model.traces.getValue("r")
        assertEquals(10, trace.lines.size)
        assertTrue(trace.partial)
        assertEquals(listOf(trace), change.changed.toList())
    }

    @Test
    fun deletesTheWholeRequestRatherThanItsStartWhileItIsStillGettingLines() {
        val model = TraceModel(measure = { 100 }, limit = { 300 })
        model.add(listOf(line("""{"message":"Started","process.pid":"1","trace.id":"r","span.id":"s","phase":"START"}""")), now = 0)
        model.add((1..2).map { line(1, "r", "m$it") }, now = 0)
        val change = model.add(listOf(line(1, "next", "n")), now = 1)
        assertEquals(listOf("Started", "m1", "m2"), change.deleted.map { it.message })
        assertEquals(listOf("r"), change.removed.map { it.id })
        assertEquals(listOf("next"), model.traces.keys.toList())
    }

    @Test
    fun deletesALineOutsideAnyRequestAlone() {
        val model = TraceModel(measure = { 100 }, limit = { 200 })
        model.add(listOf(line(1, "r", "in"), line(1, null, "out")), now = 0)
        assertEquals(listOf("in"), model.add(listOf(line(1, null, "next")), now = TraceModel.ACTIVE_MS).deleted.map { it.message })
        // now the oldest line is outside any request
        assertEquals(listOf("out"), model.add(listOf(line(1, null, "last")), now = TraceModel.ACTIVE_MS).deleted.map { it.message })
        assertEquals(listOf("next", "last"), model.snapshot().admitted.map { it.message })
    }

    @Test
    fun countsTheLinesTheLevelHidesTowardsTheLimit() {
        val model = TraceModel(measure = { 100 }, limit = { 300 })
        model.setLevel(null, Level.WARN)
        model.add(listOf(line("""{"message":"hidden","log.level":"INFO","process.pid":"1"}""")), now = 0)
        model.add((1..3).map { line("""{"message":"w$it","log.level":"WARN","process.pid":"1"}""") }, now = 0)
        assertEquals(3, model.kept)
        model.setLevel(null, Level.DEBUG)
        assertEquals(listOf("w1", "w2", "w3"), model.rebuild().admitted.map { it.message })
    }

    @Test
    fun deletesFromTheRunWithTheMostLinesPastTheLinesOfAllRuns() {
        val model = TraceModel(measure = { 1 })
        model.add((1..10_000).map { line(1, null, "small") }, now = 0)
        model.add((1..40_000).map { line(2, "big$it", "big") }, now = 0)
        assertEquals(null, model.capped)
        val change = model.add(listOf(line(1, null, "more")), now = TraceModel.ACTIVE_MS)
        assertEquals(TraceModel.LINES_MAX, model.kept)
        assertEquals(listOf("big1"), change.removed.map { it.id })
        assertEquals("app2", model.capped)
        model.removeRun(2, ends = false)
        assertEquals(null, model.capped)
    }

    @Test
    fun deletesOnlyTheRunsOwnLinesOfARequest() {
        model.add(listOf(line(1, "r", "one"), line(2, "r", "two"), line(1, null, "alone")))
        val change = model.removeRun(1, ends = false)
        assertEquals(listOf("one", "alone"), change.deleted.map { it.message })
        val trace = model.traces.getValue("r")
        assertEquals(listOf("two"), trace.lines.map { it.message })
        assertEquals(setOf<Long?>(2), trace.pids)
        assertFalse(trace.partial)
        assertEquals(listOf(trace), change.changed.toList())
        // a run whose console was cleared keeps sending
        assertEquals(1, model.add(listOf(line(1, "r", "again"))).admitted.size)
    }

    @Test
    fun dropsTheLinesARunSendsAfterItEnded() {
        model.add(listOf(line(1, "r", "one")))
        val change = model.removeRun(1, ends = true)
        assertEquals(listOf("r"), change.removed.map { it.id })
        assertTrue(model.add(listOf(line(1, "r", "late"))).admitted.isEmpty())
        // Windows gave the id to a new process
        model.accept(1)
        assertEquals(1, model.add(listOf(line(1, "r", "new"))).admitted.size)
    }

    @Test
    fun countsTheLinesARequestKeepsAfterDroppingSome() {
        model.add((1..6_000).map { line("""{"message":"m $it","trace.id":"big","span.id":"s"}""") })
        assertEquals(4_998, model.traces.getValue("big").lines.size)
        assertEquals(4_998, model.traceLines)
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

    /** A line of the run with process id [pid], in the app `app<pid>`. */
    private fun line(pid: Int, trace: String?, message: String) =
        line("""{"message":"$message","service.name":"app$pid","process.pid":"$pid"${trace?.let { ",\"trace.id\":\"$it\"" }.orEmpty()}}""")
}
