# Trace Tail

English | [Türkçe](README.tr.md)

An IntelliJ IDEA plugin that shows the logs of the applications you run from the IDE as a live,
trace-grouped view. The logs do not arrive as one long stream but gathered by the request they
belong to: each request is folded into a single line; opening it shows its log lines nested by
`trace.id`, `span.id` and `parent.id`. So for a request that passes through several applications,
you can see at a glance which steps it took, which step started which, and where it failed.

**Status:** the plugin receives the logs of the applications it starts and shows them in the
Trace Tail tool window, built from the IDE's own components.

## The tool window

The tool window has three tabs that look at the same logs from three angles:

| Tab | Shows |
|---|---|
| Tree | One row per request, opening into its steps. Beside it, the selected line's JSON and stack trace (Line) and the selected request as a sequence diagram (Sequence) |
| Flat | Every line in arrival order, as a console; stack trace frames link to the source |
| Raw | The JSON lines as received; useful to see exactly what the agent sent |

The Tree's **Timeline** column places each row on its request's own time line, from the request's
first line to its last. It shows where a request's time went: a step as a bar in its app's colour
(outlined while it runs, red when its END never came), a line inside a step as a diamond.

The **Sequence** diagram draws the selected request as a flow of who called whom. It has a lifeline
for each app, for each class whose methods log `METHOD` steps (kept next to its app), and for the
parties that write no lines: the user, the scheduler, a queue and the outside systems an `HTTP_OUT`
calls. Each call, return and note is a row in the order the lines were written; hovering over a row
shows its line as a tooltip. **Copy as Mermaid** copies the diagram as text, ready to paste into a
document or a pull request description.

Each Run or Debug window also gets a **Trace Tail** tab once its app's first line arrives. It shows
only the requests that app took part in, with the lines the other apps wrote for them, so you keep
the rest of the request in view while you focus on one app. The app is matched by the run
configuration's name, which the agent sends as `service.name`. A run window without tabs, such as a
plain Run console, gets no tab.

In the Tree tabs, **F4** or **Jump to Source** in the context menu opens the class that wrote the
line (`log.logger`), going straight to `log.origin.file.line` when the line has it. Log4j2's
asynchronous loggers send no line number, so for them only the class opens.

The toolbar's buttons:

- pause the view; new lines are not lost but wait in the IDE until resumed
- clear the view
- hide lines below a level for one app or all (for example, only `WARN` and above)
- expand or collapse all requests
- turn **Soft-Wrap** on or off: wrapped, long lines continue on the next line in the tree and the
  consoles; unwrapped, each stays on one line and the view scrolls sideways. The IDE remembers the
  choice.

In the Tree, a row folds with its arrow, a double click, or the Left and Right keys.

## How it works

The plugin listens, for each open project, on a loopback port that only this computer can reach.
When a Java run configuration (Application, Spring Boot, …) is run or debugged, the plugin adds its
agent to the JVM:

```
-javaagent:<plugin folder>/agent/trace-tail-agent.jar=<port>,<run configuration name>
```

The agent waits until the application's Logback or Log4j2 starts, then adds an appender to its root
logger. The appender sends each log event as one
[ECS](https://www.elastic.co/guide/en/ecs-logging/java/current/setup.html) JSON line to the port.
All of this happens at run time: the application needs no added dependency and no change to its
logging configuration.

## What the application needs

The application logs through Logback 1.2 or later, or Log4j2 2.17 or later, and runs on Java 8 or
later. The agent was tried with Logback 1.2.13 and 1.5.20, Log4j2 2.17.2 and 2.24.3, and Spring
Boot 3.5 on either of them.

The application's own logger levels decide which events are sent, as for its console; a line that a
logger's level drops does not reach Trace Tail either. A filter put only on the console appender,
however, does not affect Trace Tail. For example, lines hidden from the console by Spring Boot's
`logging.threshold.console` still show in Trace Tail. A logger with additivity turned off (one that
does not pass its events on to the root logger) sends nothing, because the appender sits on the
root logger.

The agent writes these as separate fields under their own names:

- the thread context (MDC) entries
- SLF4J 2's key-value pairs, as in `log.atInfo().addKeyValue("phase", "START")`
- a Log4j2 map message's entries; its `message` entry becomes the message

So the ids that identify a request must be in the thread context as `trace.id` and `span.id`. When
Spring Boot's tracing is used, Micrometer puts them there as `traceId` and `spanId`; the view
accepts those names too.

The agent hooks into Logback through the `logback.statusListenerClass` system property. If the
application sets that property itself, the agent has no place to hook in and its Logback lines are
not sent.

## What the view reads

| Field | Use |
|---|---|
| `@timestamp`, `log.level`, `message`, `service.name` | Every line |
| `trace.id`, `span.id` | Groups the lines by request; a line without `trace.id` shows only in the Flat and Raw tabs |
| `parent.id` | Nests a step under the step that started it |
| `event.action` | The event's name, such as `HTTP_IN`; a line without it shows its message instead |
| `phase` | Marks a step's start (`START`) or end (`END`) |
| `user.id` | The user who made the request |

A step's row shows its outcome (`outcome`, `status`) and the time between its `START` and `END`.
Every other field is shown as `key=value`.

The plugin keeps the last 10,000 lines in memory, so a changed level filter applies not only to new
lines but to those too.

An application's logging never waits on the IDE; the application does not slow down even if the
IDE does, or closes. The agent sends from its own thread and keeps at most 10,000 unsent lines; the
plugin reads each connection on its own thread and keeps at most 20,000 unread lines. Both drop the
oldest line when full. If the IDE closes while the application keeps running, the agent drops the
lines without a message.

## Requirements

- IntelliJ IDEA 2026.2 or later, with the bundled Java plugin
- To build: a local IntelliJ IDEA 2026.2 installation. Its path is set in `gradle.properties`
  (`platformLocalPath`), and its bundled Java 25 runtime is used as the toolchain.

## Running

Open the project in IntelliJ IDEA and run the `runIde` Gradle task. A separate sandbox IDE starts
with the plugin installed; the view is under **View → Tool Windows → Trace Tail**. The sandbox IDE
opens the `sample` folder, two small applications to try the plugin with; see
[sample/README.md](sample/README.md). Once you run a Java application in this IDE, its logs start
showing in the view.

The agent is the `agent` subproject. The build puts its jar in the plugin's `agent` folder, outside
the plugin's class path, so the agent is loaded only into the JVM of the application being run, not
into the IDE.
