# Trace Tail

An IntelliJ IDEA plugin that shows the logs of the applications you run from the IDE as a live,
trace-grouped view. Each request is folded into a single line; opening it shows its log lines
nested by `trace.id`, `span.id` and `parent.id`.

**Status:** the plugin receives the logs of the applications it starts and shows them in the
Trace Tail tool window, built from the IDE's own components.

## The tool window

| Tab | Shows |
|---|---|
| Tree | One row per request, opening into its steps. Beside it, the selected line's JSON and stack trace (Line) and the selected request as a sequence diagram (Sequence) |
| Flat | Every line in arrival order, as a console; stack trace frames link to the source |
| Raw | The JSON lines as received |

The Tree's **Timeline** column places each row on its request's own time line, from the request's
first line to its last: a step as a bar in its app's colour (outlined while it runs, red when its
END never came), a line inside a step as a diamond.

The **Sequence** diagram draws a lifeline for each app, for each class whose methods log `METHOD`
steps (kept next to its app), and for the parties that write no lines: the user, the scheduler, a
queue and the systems an `HTTP_OUT` calls. Each call, return and note is a row in the order the
lines were written; a row's tooltip is its line. **Copy as Mermaid** copies the diagram as text.

Each Run or Debug window also gets a **Trace Tail** tab once its app's first line arrives. It shows
the requests that app took part in, with the lines the other apps wrote for them. The app is
matched by the run configuration's name, which the agent sends as `service.name`. A run window
without tabs, such as a plain Run console, gets no tab.

In the Tree tabs, **F4** or **Jump to Source** in the context menu opens the class named by the
line's `log.logger`, at `log.origin.file.line` when the line has it. Log4j2's asynchronous loggers
send no line number. The toolbar pauses the
view (new lines wait in the IDE until resumed), clears it, hides lines below a level for one app or
all, expands or collapses the requests, and turns **Soft-Wrap** on or off: wrapped, long lines
continue on the next line in the tree and the consoles; unwrapped, each stays on one line and the
view scrolls sideways. The IDE remembers the choice.

In the Tree, a row folds with its arrow, a double click, or the Left and Right keys.

## How it works

The plugin listens on a loopback port for each open project. When a Java run configuration
(Application, Spring Boot, …) is run or debugged, the plugin adds its agent to the JVM:

```
-javaagent:<plugin folder>/agent/trace-tail-agent.jar=<port>,<run configuration name>
```

The agent waits until the application's Logback or Log4j2 starts, then adds an appender to its root
logger. The appender sends each event as one
[ECS](https://www.elastic.co/guide/en/ecs-logging/java/current/setup.html) JSON line to the port.
The application needs no dependency and no configuration change.

## What the application needs

The application logs through Logback 1.2 or later, or Log4j2 2.17 or later, and runs on Java 8 or
later. The agent was tried with Logback 1.2.13 and 1.5.20, Log4j2 2.17.2 and 2.24.3, and Spring
Boot 3.5 on either of them.

The application's own levels decide which events are sent, as for its console. A logger with
additivity turned off sends nothing, because the appender sits on the root logger.

The agent writes these as fields under their own names:

- the thread context (MDC) entries
- SLF4J 2's key-value pairs, as in `log.atInfo().addKeyValue("phase", "START")`
- a Log4j2 map message's entries; its `message` entry becomes the message

So the ids must be in the thread context as `trace.id` and `span.id`. The view also accepts
Micrometer's `traceId` and `spanId`.

The agent hooks into Logback through the `logback.statusListenerClass` system property. If the
application sets that property itself, its Logback lines are not sent.

## What the view reads

| Field | Use |
|---|---|
| `@timestamp`, `log.level`, `message`, `service.name` | Every line |
| `trace.id`, `span.id` | Groups the lines by request; a line without `trace.id` shows only in the Flat and Raw tabs |
| `parent.id` | Nests a step under the step that started it |
| `event.action` | The event's name, such as `HTTP_IN`; a line without it shows its message instead |
| `phase` | `START` or `END` of a step |
| `user.id` | The user who made the request |

A step's row shows its outcome (`outcome`, `status`) and its duration. Every other field is shown
as `key=value`.

The plugin keeps the last 10,000 lines, so a changed level applies to those too.

An application's logging never waits on the IDE. The agent sends from its own thread and keeps at
most 10,000 unsent lines; the plugin reads each connection on its own thread and keeps at most
20,000 unread lines. Both drop the oldest line when full. If the IDE closes while the application
keeps running, the agent drops the lines without a message.

## Requirements

- IntelliJ IDEA 2026.2 or later, with the bundled Java plugin
- To build: a local IntelliJ IDEA 2026.2 installation. Its path is set in `gradle.properties`
  (`platformLocalPath`), and its bundled Java 25 runtime is used as the toolchain.

## Running

Open the project in IntelliJ IDEA and run the `runIde` Gradle task. A sandbox IDE starts with
the plugin installed; the view is under **View → Tool Windows → Trace Tail**.

The agent is the `agent` subproject. The build puts its jar in the plugin's `agent` folder, outside
the plugin's class path.
