package io.github.akifkaya0.tracetail.ui

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import io.github.akifkaya0.tracetail.model.Level
import java.awt.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Colours and text formats shared by the views. Every colour has a light and a dark variant. */
internal object Palette {
    private val APPS = arrayOf(
        JBColor(0x5a4ec3, 0x9d93ee), JBColor(0x0a7864, 0x4cc0a3), JBColor(0x9d3b84, 0xd985c3), JBColor(0xa94e18, 0xe08c56),
        JBColor(0x4a59c2, 0x8b97ee), JBColor(0x137a79, 0x4cc3c1), JBColor(0x76690d, 0xc9b84a), JBColor(0xa82f64, 0xe07aa6),
    )
    private val OK = JBColor(0x1c7845, 0x5fb865)
    private val WARN = JBColor(0x9e5f0b, 0xe0a93e)
    private val ERROR = JBColor(0xbd3a2c, 0xf06f63)
    private val DEBUG = JBColor(0x6b60b2, 0xa99cf0)
    private val ACCENT = JBColor(0x0b6d89, 0x4fb3d1)

    val GRAY: SimpleTextAttributes = SimpleTextAttributes.GRAYED_ATTRIBUTES
    val PLAIN: SimpleTextAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES
    val BOLD: SimpleTextAttributes = SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
    val ERROR_TEXT = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, ERROR)
    val ERROR_ITALIC = SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, ERROR)
    val RUNNING = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, ACCENT)
    val RUNNING_ITALIC = SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, ACCENT)
    val START = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, ACCENT)
    val END = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.GRAY)

    fun appColor(index: Int): Color = APPS[index % APPS.size]

    fun app(index: Int) = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, appColor(index))

    fun levelColor(level: Level): Color = when (level) {
        Level.DEBUG -> DEBUG
        Level.INFO -> JBColor.foreground()
        Level.WARN -> WARN
        Level.ERROR -> ERROR
    }

    val errorColor: Color get() = ERROR
    val accentColor: Color get() = ACCENT

    fun level(level: Level) = when (level) {
        Level.DEBUG -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DEBUG)
        Level.INFO -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.GRAY)
        Level.WARN -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, WARN)
        Level.ERROR -> ERROR_TEXT
    }

    fun outcome(outcome: String?) = when (outcome) {
        "SUCCESS" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, OK)
        "REJECTED" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, WARN)
        "UNAUTHORIZED" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.GRAY)
        else -> ERROR_TEXT
    }

    /** How a field's value is shown; outcomes and check results stand out. */
    fun value(key: String, value: String) = when {
        key == "outcome" -> outcome(value)
        key == "result" && value == "PASSED" -> outcome("SUCCESS")
        key == "result" && value == "REJECTED" -> outcome("REJECTED")
        else -> PLAIN
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    fun time(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis))

    fun duration(millis: Long): String {
        val ms = millis.coerceAtLeast(0)
        return when {
            ms < 1_000 -> "$ms ms"
            ms < 10_000 -> String.format(Locale.ROOT, "%.2f s", ms / 1000.0)
            else -> String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
        }
    }

    fun plural(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"
}
