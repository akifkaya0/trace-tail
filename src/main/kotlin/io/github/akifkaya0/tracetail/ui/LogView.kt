package io.github.akifkaya0.tracetail.ui

import com.intellij.execution.ConsoleFolding
import com.intellij.execution.filters.CompositeFilter
import com.intellij.execution.filters.Filter
import com.intellij.execution.impl.ConsoleViewUtil
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterClient
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.impl.ContextMenuPopupHandler
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PlainTextTokenTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.LogLine
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.JComponent

/**
 * Log lines in a read-only editor set up like a console: its font and colours, the links of its
 * filters, such as those of stack trace frames, and its folding. Unlike a console, it takes lines
 * out from anywhere. A line is coloured once, when it comes: the editor reads its parts' colours
 * from [Colours]. A view scrolled to its end keeps following new lines; otherwise the lines being
 * read stay in place while lines above them go.
 *
 * Each change that adds or removes lines makes the editor measure every line again, so the lines
 * that go are taken out with as few changes as can be; see [delete]. While the view is off screen,
 * the changes wait, and it takes them together when it shows again.
 */
internal class LogView(
    private val project: Project,
    parent: Disposable,
    /** A line's text in parts with their colours; together they end with a line break. */
    private val parts: (LogLine) -> List<Pair<String, ConsoleViewContentType>>,
    /** Fades or tints a line, with the lines its stack trace takes; a style with a background fills the lines from edge to edge. */
    private val style: (LogLine) -> TextAttributes? = { null },
    /** Whether a stack trace's lines fold as in a console; a line of JSON would fold whole. */
    private val folds: Boolean = true,
) {

    private class Entry(val line: LogLine, val lengths: IntArray, val types: Array<ConsoleViewContentType>) {
        val length = lengths.sum()

        /** Where the text starts, while the entry is before [validTo]. */
        var start = 0
        var shown = true

        /** The fold regions and the filters' links, by their offsets within the entry, to put back when the text around them is replaced. */
        var folds: List<Fold> = emptyList()
        var links: List<Filter.ResultItem> = emptyList()
    }

    private class Fold(val start: Int, val end: Int, val placeholder: String)

    /** The changes that came while the view was off screen; editing an editor no one sees costs as much as one in view. */
    private class Held {
        var reset = false
        val added = LinkedHashMap<Long, LogLine>()
        val deleted = HashSet<Long>()
    }

    private val disposable = Disposer.newDisposable(parent, "Trace Tail log view")
    val editor: EditorEx = ConsoleViewUtil.setupConsoleEditor(project, true, false)
    private val entries = ArrayList<Entry>()

    /** The entries before this index know where they start; [startOf] works out the others, as taking out lines moves them. */
    private var validTo = 0
    private val styled = ArrayList<RangeHighlighter>()
    private val filter = CompositeFilter(project, ConsoleViewUtil.computeConsoleFilters(project, null, GlobalSearchScope.allScope(project)))
        .apply { setForceUseAllFilters(true) }
    private val filterThread = AppExecutorUtil.createBoundedApplicationPoolExecutor("Trace Tail links", 1)

    /** Set when the reader scrolls up with the caret on the last line, which would otherwise keep following. */
    private var stopFollowing = false
    private var held = Held()

    val component: JComponent get() = editor.component

    /** The lines shown, those still held included. */
    val lineCount: Int get() = if (held.reset) held.added.size else entries.size - held.deleted.size + held.added.size

    init {
        Disposer.register(disposable) { EditorFactory.getInstance().releaseEditor(editor) }
        editor.highlighter = Colours()
        editor.settings.isAllowSingleLogicalLineFolding = true
        editor.scrollPane.border = JBUI.Borders.empty()
        editor.installPopupHandler(ContextMenuPopupHandler.Simple(DefaultActionGroup(ActionManager.getInstance().getAction(IdeActions.ACTION_COPY))))
        val scrolling = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = checkFollowing()
            override fun mouseDragged(e: MouseEvent) = checkFollowing()
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (!e.isShiftDown) checkFollowing()
            }
        }
        editor.scrollPane.addMouseWheelListener(scrolling)
        editor.scrollPane.verticalScrollBar.addMouseListener(scrolling)
        editor.scrollPane.verticalScrollBar.addMouseMotionListener(scrolling)
        editor.component.addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && editor.component.isShowing) takeHeld()
        }
    }

    fun setSoftWraps(on: Boolean) {
        editor.settings.isUseSoftWraps = on
    }

    fun show(change: Change) {
        if (editor.component.isShowing) {
            apply(change.reset, change.deleted.map { it.seq }, change.admitted)
            return
        }
        val h = held
        if (change.reset) {
            h.reset = true
            h.added.clear()
            h.deleted.clear()
        }
        for (line in change.deleted) if (h.added.remove(line.seq) == null) h.deleted += line.seq
        for (line in change.admitted) h.added[line.seq] = line
    }

    private fun takeHeld() {
        val h = held
        if (!h.reset && h.added.isEmpty() && h.deleted.isEmpty()) return
        held = Held()
        apply(h.reset, h.deleted.toList(), h.added.values.toList())
    }

    private fun apply(reset: Boolean, deleted: List<Long>, admitted: List<LogLine>) {
        if (reset) clear()
        if (deleted.isEmpty() && admitted.isEmpty()) return
        val follow = !stopFollowing && (atEnd() || caretOnLastLine())
        stopFollowing = false
        if (deleted.isNotEmpty()) delete(deleted, keepView = !follow)
        if (admitted.isNotEmpty()) append(admitted, follow)
        if (follow) {
            // as a console does, a selection made while following stays
            val selection = if (editor.selectionModel.hasSelection()) editor.caretModel.caretsAndSelections else null
            EditorUtil.scrollToTheEnd(editor, true)
            selection?.let { editor.caretModel.caretsAndSelections = it }
        }
    }

    /** Puts the caret on the line and brings it into view; false when the view does not show it. */
    fun scrollTo(line: LogLine): Boolean {
        takeHeld()
        val i = indexOf(line.seq)
        if (i < 0) return false
        editor.caretModel.moveToOffset(startOf(i))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        return true
    }

    /** The line whose text holds the offset, and where that text starts. */
    fun lineAt(offset: Int): Pair<LogLine, Int>? {
        val i = indexAt(offset)
        return if (i < 0) null else entries[i].line to startOf(i)
    }

    /** Fades and tints every line again, as [style] now says. */
    fun restyle() {
        styled.forEach { it.dispose() }
        styled.clear()
        markStyles(0, entries.size)
    }

    /* ---------- adding and taking out ---------- */

    private fun append(lines: List<LogLine>, follow: Boolean) {
        val document = editor.document
        val from = document.textLength
        val text = StringBuilder()
        val first = entries.size
        var start = from
        // only a stack trace has lines to fold or link, and few lines have one
        val traces = ArrayList<Pair<Entry, String>>()
        for (line in lines) {
            // a document takes only "\n"; a message or field from Windows may hold "\r\n"
            val p = parts(line).map { StringUtil.convertLineSeparators(it.first) to it.second }.filter { it.first.isNotEmpty() }
            val entry = Entry(line, IntArray(p.size) { p[it].first.length }, Array(p.size) { p[it].second })
            entry.start = start
            start += entry.length
            val entryStart = text.length
            p.forEach { text.append(it.first) }
            if (line.stackTrace != null) traces += entry to text.substring(entryStart)
            entries += entry
        }
        // the entries come first: the editor asks for their colours while it takes the text
        if (!follow) editor.scrollingModel.accumulateViewportChanges()
        try {
            document.insertString(from, text)
        } finally {
            if (!follow) editor.scrollingModel.flushViewportChanges()
        }
        markStyles(first, entries.size)
        if (traces.isEmpty()) return
        if (folds) {
            for ((entry, entryText) in traces) entry.folds = foldsIn(entryText)
            addFolds(first, entries.size)
        }
        link(traces)
    }

    /**
     * Takes out the lines. Runs of lines that go, with few lines between them, are replaced in one
     * change by the lines between them, whose styles, fold regions and links are then put back.
     * Unless the view follows the end, the first line in view stays where it is.
     */
    private fun delete(seqs: List<Long>, keepView: Boolean) {
        // the indexes of the entries that go; each is found by a binary search, the entries being in sequence order
        val doomed = seqs.map(::indexOf).filter { it >= 0 }.sorted()
        if (doomed.isEmpty()) return
        val document = editor.document
        val area = editor.scrollingModel.visibleArea
        val topLine = editor.yToVisualLine(area.y)
        var top = editor.visualPositionToOffset(VisualPosition(topLine, 0))
        val shift = area.y - editor.visualLineToY(topLine)
        val ours = Collections.newSetFromMap(IdentityHashMap<RangeHighlighter, Boolean>()).apply { addAll(styled) }
        var goneEntries = 0
        var a = 0
        while (a < doomed.size) {
            // a span runs from an entry that goes to the last one after it with few entries between them
            var b = a
            while (b + 1 < doomed.size && doomed[b + 1] - doomed[b] - 1 <= MERGE_GAP) b++
            val from = doomed[a] - goneEntries
            val to = doomed[b] + 1 - goneEntries
            val text = document.immutableCharSequence
            val start = startOf(from)
            val end = startOf(to - 1) + entries[to - 1].length
            val kept = ArrayList<Entry>()
            val keptText = StringBuilder()
            var newTop = -1
            var next = a
            for (k in from until to) {
                val e = entries[k]
                val s = startOf(k)
                val goes = next <= b && doomed[next] - goneEntries == k
                if (goes) next++
                if (newTop < 0 && top in start until s + e.length) newTop = start + keptText.length + if (goes) 0 else top - s
                if (goes) {
                    e.shown = false
                } else {
                    kept += e
                    keptText.append(text, s, s + e.length)
                }
            }
            val (styledFrom, styledTo) = removeMarkers(start, end, ours)
            // a style runs over the lines around the span too, which lose it with the span's
            val restyleFrom = if (styledFrom < start) indexAt(styledFrom) else from
            val restyleTo = if (styledTo >= end) indexAt(styledTo) + 1 else to
            entries.subList(from, to).clear()
            entries.addAll(from, kept)
            validTo = minOf(validTo, from)
            document.replaceString(start, end, keptText)
            top = when {
                top >= end -> top - (end - start - keptText.length)
                newTop >= 0 -> newTop
                else -> top
            }
            markStyles(restyleFrom, restyleTo - (to - from - kept.size))
            putBackMarkers(from, from + kept.size)
            goneEntries += to - from - kept.size
            a = b + 1
        }
        if (keepView) {
            val y = editor.visualLineToY(editor.offsetToVisualLine(top, false)) + shift
            val scrolling = editor.scrollingModel
            scrolling.disableAnimation()
            scrolling.scrollVertically(y)
            scrolling.enableAnimation()
        }
    }

    /**
     * Takes out this view's styles, links and fold regions in [start, end), before the text there is
     * replaced. Returns where the styles taken out started and ended, which may be outside the range.
     */
    private fun removeMarkers(start: Int, end: Int, ours: Set<RangeHighlighter>): Pair<Int, Int> {
        val markup = editor.markupModel
        val doomed = ArrayList<RangeHighlighterEx>()
        var styledFrom = start
        var styledTo = start
        // the end is the start of the next line, whose markers stay
        markup.processRangeHighlightersOverlappingWith(start, end - 1) { h ->
            if (h in ours) {
                styledFrom = minOf(styledFrom, h.startOffset)
                styledTo = maxOf(styledTo, h.endOffset)
            }
            if (h in ours || h.layer == HighlighterLayer.CONSOLE_FILTER || EditorHyperlinkSupport.getHyperlinkInfo(h) != null) doomed += h
            true
        }
        doomed.forEach(markup::removeHighlighter)
        val folding = editor.foldingModel
        val regions = folding.allFoldRegions.filter { it.startOffset < end && it.endOffset > start }
        if (regions.isNotEmpty()) folding.runBatchFoldingOperation { regions.forEach(folding::removeFoldRegion) }
        return styledFrom to styledTo
    }

    /** Puts back the fold regions and links of the entries in [from, to); their styles are marked apart. */
    private fun putBackMarkers(from: Int, to: Int) {
        if (from == to) return
        addFolds(from, to)
        for (i in from until to) addLinks(i)
    }

    private fun clear() {
        entries.forEach { it.shown = false }
        entries.clear()
        validTo = 0
        styled.clear()
        editor.markupModel.removeAllHighlighters()
        val folding = editor.foldingModel as FoldingModelEx
        folding.runBatchFoldingOperation { folding.clearFoldRegions() }
        editor.document.setText("")
    }

    /* ---------- where the entries are ---------- */

    private fun startOf(i: Int): Int {
        while (validTo <= i) {
            entries[validTo].start = if (validTo == 0) 0 else entries[validTo - 1].let { it.start + it.length }
            validTo++
        }
        return entries[i].start
    }

    /** The index of the entry whose text holds the offset, or -1. */
    private fun indexAt(offset: Int): Int {
        if (entries.isEmpty()) return -1
        val known = validTo.takeIf { it > 0 }?.let { entries[it - 1].start + entries[it - 1].length } ?: 0
        if (offset < known) {
            return entries.binarySearch(0, validTo) { e ->
                when {
                    e.start + e.length <= offset -> -1
                    e.start > offset -> 1
                    else -> 0
                }
            }
        }
        for (i in maxOf(validTo - 1, 0) until entries.size) {
            if (offset < startOf(i) + entries[i].length) return i
        }
        return -1
    }

    /** The entries are in arrival order, which is the order of their lines' sequence numbers. */
    private fun indexOf(seq: Long): Int = entries.binarySearch { it.line.seq.compareTo(seq) }

    private fun atEnd(): Boolean {
        val bar = editor.scrollPane.verticalScrollBar
        return bar.value == bar.maximum - bar.visibleAmount
    }

    private fun caretOnLastLine(): Boolean {
        val document = editor.document
        return document.getLineNumber(editor.caretModel.offset) >= document.lineCount - 1
    }

    private fun checkFollowing() {
        if (!atEnd() && caretOnLastLine()) stopFollowing = true
    }

    /* ---------- styles, folding and links ---------- */

    /** Fades or tints the entries in [from, to), each run of entries with the same style as one range. */
    private fun markStyles(from: Int, to: Int) {
        if (styled.size > STYLED_PRUNE_AT) styled.removeIf { !it.isValid }
        var runStart = -1
        var runEnd = -1
        var current: TextAttributes? = null
        for (i in from until to) {
            val s = style(entries[i].line)
            if (s != current) {
                mark(runStart, runEnd, current)
                runStart = startOf(i)
                current = s
            }
            runEnd = startOf(i) + entries[i].length
        }
        mark(runStart, runEnd, current)
    }

    private fun mark(from: Int, to: Int, style: TextAttributes?) {
        if (style == null || to <= from) return
        val markup = editor.markupModel
        // the last character is the line break, which would take in the next line
        styled += if (style.backgroundColor == null) {
            markup.addRangeHighlighter(from, to - 1, HighlighterLayer.HYPERLINK + 1, style, HighlighterTargetArea.EXACT_RANGE)
        } else {
            markup.addRangeHighlighter(from, to - 1, HighlighterLayer.CARET_ROW - 1, style, HighlighterTargetArea.LINES_IN_RANGE)
        }
    }

    /** Where the console's foldings fold an entry's lines, such as a stack trace's frames inside the JDK. */
    private fun foldsIn(text: String): List<Fold> {
        val foldings = ConsoleFolding.EP_NAME.extensionList
        if (foldings.isEmpty()) return emptyList()
        val lines = text.removeSuffix("\n").split('\n')
        val starts = IntArray(lines.size)
        for (k in 1 until lines.size) starts[k] = starts[k - 1] + lines[k - 1].length + 1
        val found = ArrayList<Fold>()
        var folding: ConsoleFolding? = null
        var first = 0
        for (k in 0..lines.size) {
            val next = lines.getOrNull(k)?.let { line -> foldings.firstOrNull { it.shouldFoldLine(project, line) } }
            if (next === folding) continue
            folding?.let { f ->
                val placeholder = f.getPlaceholderText(project, lines.subList(first, k))
                // a fold attached to the line above starts at that line's end, the entry's own lines only
                val start = if (f.shouldBeAttachedToThePreviousLine() && first > 0) starts[first] - 1 else starts[first]
                if (placeholder != null) found += Fold(start, starts[k - 1] + lines[k - 1].length, placeholder)
            }
            folding = next
            first = k
        }
        return found
    }

    private fun addFolds(from: Int, to: Int) {
        if ((from until to).none { entries[it].folds.isNotEmpty() }) return
        val folding = editor.foldingModel
        folding.runBatchFoldingOperation {
            for (i in from until to) {
                for (f in entries[i].folds) folding.addFoldRegion(startOf(i) + f.start, startOf(i) + f.end, f.placeholder)?.isExpanded = false
            }
        }
    }

    /**
     * Runs the console's filters over the entries' lines, off the UI thread. A link is placed by its
     * entry, which other lines going may have moved, or not at all if its entry went.
     */
    private fun link(traces: List<Pair<Entry, String>>) {
        if (filter.isEmpty) return
        ReadAction.nonBlocking<List<Pair<Entry, List<Filter.ResultItem>>>> {
            traces.map { (entry, text) -> entry to linksIn(text) }
        }.expireWith(disposable).finishOnUiThread(ModalityState.any()) { found ->
            for ((entry, links) in found) {
                entry.links = links
                val i = if (entry.shown) indexOf(entry.line.seq) else -1
                if (i >= 0) addLinks(i)
            }
        }.submit(filterThread)
    }

    /** The filters' results for an entry's text; their offsets are the entry's own, as the lines' ends given to the filters are. */
    private fun linksIn(text: String): List<Filter.ResultItem> {
        val found = ArrayList<Filter.ResultItem>()
        var lineStart = 0
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it + 1 }
            filter.applyFilter(text.substring(lineStart, lineEnd), lineEnd)?.resultItems?.forEach {
                if (it.highlightStartOffset <= it.highlightEndOffset && it.highlightEndOffset <= text.length) found += it
            }
            lineStart = lineEnd
        }
        return found
    }

    private fun addLinks(i: Int) {
        val links = entries[i].links
        if (links.isEmpty()) return
        val support = EditorHyperlinkSupport.get(editor)
        val start = startOf(i)
        for (item in links) {
            val info = item.hyperlinkInfo
            if (info != null) {
                support.createHyperlink(start + item.highlightStartOffset, start + item.highlightEndOffset, item.highlightAttributes, info)
            } else {
                item.highlightAttributes?.let { support.addHighlighter(start + item.highlightStartOffset, start + item.highlightEndOffset, it) }
            }
        }
    }

    /** The colours of the entries' parts, read where the editor asks for them. */
    private inner class Colours : EditorHighlighter {
        override fun createIterator(startOffset: Int): HighlighterIterator = Tokens(indexAt(startOffset), startOffset)
        override fun setEditor(editor: HighlighterClient) = Unit
    }

    private inner class Tokens(private var index: Int, offset: Int) : HighlighterIterator {
        private var part = 0

        /** Where the part starts within its entry. */
        private var from = 0

        init {
            if (index >= 0) {
                val inEntry = offset - startOf(index)
                val lengths = entries[index].lengths
                while (part < lengths.size - 1 && from + lengths[part] <= inEntry) from += lengths[part++]
            }
        }

        override fun getStart() = startOf(index) + from
        override fun getEnd() = getStart() + entries[index].lengths[part]
        override fun getTextAttributes(): TextAttributes = entries[index].types[part].attributes
        override fun getTokenType(): IElementType = PlainTextTokenTypes.PLAIN_TEXT
        override fun getDocument(): Document = editor.document
        override fun atEnd() = index < 0 || index >= entries.size

        override fun advance() {
            val e = entries[index]
            from += e.lengths[part]
            if (++part == e.lengths.size) {
                index++
                part = 0
                from = 0
            }
        }

        override fun retreat() {
            if (--part >= 0) {
                from -= entries[index].lengths[part]
                return
            }
            if (--index < 0) return
            val e = entries[index]
            part = e.lengths.size - 1
            from = e.length - e.lengths[part]
        }
    }

    private companion object {
        const val STYLED_PRUNE_AT = 1_000

        /**
         * Runs of lines that go are replaced together while this many lines or fewer are between
         * them. A change costs a pass over every line; a line put back costs about a hundredth of that.
         */
        const val MERGE_GAP = 100
    }
}
