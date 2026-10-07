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
matched by the run configuration's name, which is also `tracetail.app`, so keep `serviceName` as in
the example below. A run window without tabs, such as a plain Run console, gets no tab.

In the Tree tabs, **F4** or **Jump to Source** in the context menu opens the class named by the
line's `log.logger`, at `log.origin.file.line` when the layout sends it. The toolbar pauses the
view (new lines wait in the IDE until resumed), clears it, hides lines below a level for one app or
all, expands or collapses the requests, and turns **Soft-Wrap** on or off: wrapped, long lines
continue on the next line in the tree and the consoles; unwrapped, each stays on one line and the
view scrolls sideways. The IDE remembers the choice.

In the Tree, a row folds with its arrow, a double click, or the Left and Right keys.

## How it works

The plugin listens on a loopback port for each open project. When a Java run configuration
(Application, Spring Boot, …) is run or debugged, the plugin adds two system properties:

| Property | Value |
|---|---|
| `tracetail.port` | The port the plugin listens on |
| `tracetail.app` | The run configuration's name |

An application sends its logs only if its Log4j2 configuration reacts to these properties. Each log
event travels as one [ECS](https://www.elastic.co/guide/en/ecs-logging/java/current/setup.html)
JSON line.

## Connecting an application

The application needs Log4j2 2.15 or later and `co.elastic.logging:log4j2-ecs-layout`. Add the
appender and its reference inside a `SystemPropertyArbiter`, so that they exist only when the
plugin started the application:

```xml
<Appenders>
    <!-- your existing appenders -->
    <SystemPropertyArbiter propertyName="tracetail.port">
        <Socket name="TraceTailAppender" host="127.0.0.1" port="${sys:tracetail.port}">
            <EcsLayout serviceName="${sys:tracetail.app}"/>
        </Socket>
    </SystemPropertyArbiter>
</Appenders>
<Loggers>
    <Root level="info">
        <!-- your existing appender references -->
        <SystemPropertyArbiter propertyName="tracetail.port">
            <AppenderRef ref="TraceTailAppender"/>
        </SystemPropertyArbiter>
    </Root>
</Loggers>
```

If the trace and span ids are in the thread context under other names, map them to the ECS names
with `KeyValuePair` elements inside `EcsLayout`, for example
`<KeyValuePair key="trace.id" value="${ctx:traceId}"/>`.

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

The plugin reads each connection on its own thread and keeps at most 20,000 unread lines, dropping
the oldest. An application's logging therefore never waits on the IDE. If the IDE closes while the
application keeps running, Log4j2 reports every failed write on the console.

## Requirements

- IntelliJ IDEA 2026.2 or later, with the bundled Java plugin
- To build: a local IntelliJ IDEA 2026.2 installation. Its path is set in `gradle.properties`
  (`platformLocalPath`), and its bundled Java 25 runtime is used as the toolchain.

## Running

Open the project in IntelliJ IDEA and run the `runIde` Gradle task. A sandbox IDE starts with
the plugin installed; the view is under **View → Tool Windows → Trace Tail**.
