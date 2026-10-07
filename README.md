# Trace Tail

An IntelliJ IDEA plugin that shows the logs of the applications you run from the IDE as a live,
trace-grouped view. Each request is folded into a single line; opening it shows its log lines
nested by `trace.id`, `span.id` and `parent.id`.

**Status:** the plugin receives the logs of the applications it starts and counts them in the
tool window's status line. The view itself still shows simulated data; showing the received logs
is the next step.

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

The plugin reads each connection on its own thread and keeps at most 20,000 unread lines, dropping
the oldest. An application's logging therefore never waits on the IDE. If the IDE closes while the
application keeps running, Log4j2 reports every failed write on the console.

## Requirements

- IntelliJ IDEA 2026.2 or later, with the bundled Java and Web Browser (JCEF) plugins
- To build: a local IntelliJ IDEA 2026.2 installation. Its path is set in `gradle.properties`
  (`platformLocalPath`), and its bundled Java 25 runtime is used as the toolchain.

## Running

Open the project in IntelliJ IDEA and run the `runIde` Gradle task. A sandbox IDE starts with
the plugin installed; the view is under **View → Tool Windows → Trace Tail**.
