# Trace Tail

An IntelliJ IDEA plugin that shows the logs of the applications you run from the IDE as a live,
trace-grouped view. Each request is folded into a single line; opening it shows its log lines
nested by `trace.id`, `span.id` and `parent.id`.

**Status:** early skeleton. The tool window shows the view with simulated data; receiving real
logs is the next step.

## Requirements

- IntelliJ IDEA 2026.2 or later
- To build: a local IntelliJ IDEA 2026.2 installation. Its path is set in `gradle.properties`
  (`platformLocalPath`), and its bundled Java 25 runtime is used as the toolchain.

## Running

Open the project in IntelliJ IDEA and run the `runIde` Gradle task. A sandbox IDE starts with
the plugin installed; the view is under **View → Tool Windows → Trace Tail**.
