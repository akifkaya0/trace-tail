# Trace Tail sample

Three small applications to try the plugin with. `shop` and `chat` log through Logback and `stock`
through Log4j2; none depends on the plugin.

## Running

The `runIde` task opens this folder in the sandbox IDE. Run **shop + stock** to start both, or
start `shop` and `stock` one by one. If the IDE asks for a JDK, choose 17 or later.

`shop` sends itself a request every two seconds, going through the scenarios below in turn. Its
Run console takes these commands:

| Command | Does |
|---|---|
| `stop`, `go` | Stops or resumes the requests |
| a scenario's name | Sends that request once |
| `burst <count>` | Sends that many orders, 20 at a time |

## Scenarios

| Scenario | Order | Shows |
|---|---|---|
| `order` | 2 chairs | A request through both apps: nested METHOD steps, a call to `stock`, a call to an outside payment system, and a queue message that `stock` handles on its own later |
| `rejected` | 50 tables | A rejection: `stock` has too few |
| `error` | a lamp | An exception in `stock`: an ERROR line with a stack trace there, WARN lines in `shop` |
| `timeout` | a sofa | The payment system does not answer, so the order fails |
| `unfinished` | a desk | A step whose END line never comes |
| `slow` | the stock report | An 8-second call, shown as running until it ends |

Every 20 seconds `stock` recounts its stock as a scheduled job, which starts a request of its own.

## In the code

- `tracing/Step` writes each step's START and END lines, as a filter or an aspect would in a real
  application.
- The ids are in the MDC. `shop` uses `trace.id` and `span.id`, `stock` Micrometer's `traceId` and
  `spanId`.
- The other fields are SLF4J 2's key-value pairs. The low-stock warning is a Log4j2 map message.
- The ids travel between the apps in the `X-Trace-Id`, `X-Parent-Id` and `X-User-Id` headers.
- `shop` listens on port 18080 and `stock` on 18081.

## chat

Run **chat** on its own. Its members talk over STOMP; it signs them in against LDAP and mails them,
so its diagrams show the user, an `ldap` and a `mail` lifeline. The WebSocket, the LDAP directory
and the mail server are stand-ins in the same process that write no lines.

At start-up it binds to LDAP to check the connection, a request of its own. Every 30 seconds a
`digest` job searches LDAP for the members and mails each of them. Every 5 seconds it sends a
`MESSAGE /topic/general` frame to each user subscribed to it, a `WS_OUT` request of its own that no
frame started. Every two seconds a user sends the next scenario's frames; its Run console takes
`stop`, `go` and the scenario names.

| Scenario | Frames | Shows |
|---|---|---|
| `connect` | `CONNECT` | LDAP `bind`, then a `search` for the user's groups |
| `subscribe` | `SUBSCRIBE /topic/general`, `SEND /app/history` | A `MESSAGE /user/queue/history` frame back to the user (`WS_OUT`) |
| `invite` | `SEND /app/invite` | An LDAP `search` for the address, then a mail (`MAIL_OUT` `send`) |
| `heartbeat` | `HEARTBEAT` | A frame without a destination |
| `error` | `SEND /app/history` for an unknown room | Outcome `ERROR` with a stack trace, and an `ERROR` frame to the user |
| `flood` | 8 × `SEND /app/history` | A user may send three frames a second; the rest are `REJECTED` |
| `disconnect` | `UNSUBSCRIBE`, `DISCONNECT` | The end of a session |
