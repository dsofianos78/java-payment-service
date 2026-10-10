# Bonus Episode 06 — Centralized Logs

Git tag: `bonus-06-centralized-logs` · Builds on: [Bonus Episode 05 — Distributed Tracing](bonus-05-distributed-tracing.md)

## Goal

Since Episode 15, logs are JSON on each instance's console. With more than one
instance, or after a container is replaced, the line you need is gone or on
another machine. Operations must search every instance's logs in one place.
They must also get from a slow span in Tempo (B5) to that request's log lines,
and back.

No business logic changes, there are no new use cases, and nothing new is
logged. The same lines now also go to a store.

## Why Loki (not ELK)

Grafana, Prometheus and Tempo are already in the stack. With Loki, one UI
links metrics, traces and logs. Loki indexes **labels only**, not the text of
each line, so it is light enough for the local stack. Loki 3 also accepts
OTLP directly, the protocol the service already uses for traces.

The common alternative is ELK (Elasticsearch, Logstash, Kibana). Episode 15's
ECS format was made for it. It costs a second UI (Kibana) next to Grafana,
Elasticsearch's memory (it indexes every word of every line), and a licence
to check: Elastic's licences changed twice in recent years. If full-text
search over months of logs is the main need, ELK is the better tool. Here the
need is "this payment's lines" and "this trace's lines", and labels plus a
filter answer both.

## Push, don't tail

Logs reach a store in one of two ways:

| | How | Examples |
|---|---|---|
| **Tail** | An agent next to the service reads files or container output and ships them | Promtail / Grafana Alloy, Filebeat, Fluent Bit |
| **Push** | The service sends its own records | OTLP, a Loki or Logstash appender |

The service **pushes over OTLP**, as it already does for spans. It needs no
agent to deploy and configure per host. The trace and span IDs arrive as
fields, not text to parse back out of JSON. And the same SDK, exporter
settings and endpoint style serve traces and logs.

**Console logging stays exactly as it is** (Episode 15). It is what
`docker logs` and a developer at a terminal see. It is also the fallback
when Loki is down: a container platform keeps console output even when
nothing else works.

## Infrastructure

### The path of one log line

```text
log.info("Payment {} created", id)          (SLF4J, in the application service, unchanged)
  └─ Logback root logger
       ├─ CONSOLE  ECS JSON on stdout                     Episode 15, unchanged
       └─ OTEL     OpenTelemetryAppender
                     └─ SdkLoggerProvider (Boot)
                          └─ BatchLogRecordProcessor      bounded queue, background thread
                               └─ OtlpHttpLogRecordExporter ──► Loki /otlp/v1/logs
```

### Dependencies

B5's `spring-boot-starter-opentelemetry` already brings the OpenTelemetry logs
SDK. Boot builds the `SdkLoggerProvider`, its batch processor and the OTLP
exporter once `management.opentelemetry.logging.export.otlp.endpoint` is set.
What Boot doesn't do is feed Logback's events into it. That takes one
dependency:

```xml
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>${opentelemetry-instrumentation.version}</version>  <!-- 2.28.1-alpha, built on SDK 1.62.0, Boot's -->
</dependency>
```

Boot doesn't manage its version. The instrumentation release has to match the
SDK Boot manages: 2.28.1 imports 1.62.0, the same as Boot 4.1.1.

### `logback-spring.xml`

Until now Boot configured Logback from properties alone. A second appender
needs a file. It includes Boot's own structured console appender, so
`logging.structured.format.console=ecs` still decides the console format:

```xml
<include resource="org/springframework/boot/logging/logback/defaults.xml"/>
<include resource="org/springframework/boot/logging/logback/structured-console-appender.xml"/>

<appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
    <captureMdcAttributes>correlationId</captureMdcAttributes>
</appender>

<root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="OTEL"/>
</root>

<logger name="io.opentelemetry" additivity="false">
    <appender-ref ref="CONSOLE"/>
</logger>
```

- **MDC.** The MDC holds `correlationId`, `traceId` and `spanId`. Only
  `correlationId` is captured. The appender takes the trace and span from
  the current OpenTelemetry context and sends them as the record's own
  `trace_id` and `span_id` fields, which is what Loki and Grafana look for.
- **`io.opentelemetry` stays on the console.** When Loki is down, the
  exporter logs "Failed to export logs". Sent through OTEL, that line would
  queue one more record for the failing exporter, every time.

### Connecting the appender

Logback starts before Spring creates any bean, so the appender can't be given
the SDK in XML. One class in `config/` connects them at startup:

```java
@Configuration(proxyBeanMethods = false)
public class OpenTelemetryLoggingConfiguration implements InitializingBean {

    private final OpenTelemetry openTelemetry;
    // constructor

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
```

Until then the appender holds the first startup lines in memory (up to 1000)
and sends them once installed.

### Properties

```properties
management.opentelemetry.logging.export.otlp.endpoint=http://localhost:3100/otlp/v1/logs
management.opentelemetry.resource-attributes[deployment.environment.name]=local
management.opentelemetry.logging.export.max-queue-size=2048
management.opentelemetry.logging.export.max-batch-size=512
management.opentelemetry.logging.export.schedule-delay=1s
```

The endpoint and the environment are example values. A deployment sets its
own, as `docker-compose.yml` does for the container
(`MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT`). The queue settings
are Boot's defaults, written out because lesson 3 depends on them.

### Loki and Grafana

`docker-compose.yml` adds `grafana/loki:3.5.7` on port 3100, configured by
`observability/loki.yaml`: one process, file system storage, retention on.
Grafana gets a Loki data source, and the two link both ways:

| From | To | How |
|---|---|---|
| A span in **Tempo** | Its trace's log lines in Loki | Tempo data source, `tracesToLogsV2`: `{service_name=~".+"} \| trace_id="${__trace.traceId}"` |
| A line in **Loki** | Its trace in Tempo | Loki data source, derived field `trace_id` (matcher type *label*, so it reads structured metadata) |

Both need fixed data source uids (`tempo`, `loki`). B5's Tempo data source had
none, so Grafana generated one and stored it. A Grafana container from B5 then
refuses to start with the new file ("data source not found"). `tempo.yml`
deletes the old Tempo data source before creating it again with the uid, so
`docker compose up` works without a manual step.

## Where it needs care — the lessons

### 1. Labels vs content

Loki indexes labels. Each distinct combination of label values is a separate
**stream**, with its own index entries and chunks. A label with a few values
costs nothing. A label with a value per payment creates a stream per payment:
millions of tiny chunks and an index bigger than the logs. Loki rejects new
streams past a per-tenant limit, and queries slow to a crawl well before
that. This is the **high cardinality** problem.

So per-request values are **never** labels. Loki 3 keeps them as
**structured metadata**: stored with each line and filterable, but not
indexed. A query selects streams by label and then filters lines by
metadata. That is fast enough when the label selector has already narrowed
the time range and the streams.

Where each field of one record ends up, checked against the running stack:

| Field | OTLP source | In Loki | Values |
|---|---|---|---|
| `service_name` | resource `service.name` (`spring.application.name`) | **label** | one per service |
| `deployment_environment_name` | resource `deployment.environment.name` | **label** | one per environment |
| `detected_level`, `severity_text` | record severity | structured metadata | 5 |
| `trace_id`, `span_id` | record trace context | structured metadata | one per request |
| `correlationId` | record attribute (MDC) | structured metadata | one per request |
| `scope_name` | instrumentation scope (the logger name) | structured metadata | one per class |
| payment ID | the message text | line content | one per payment |

The level is the one place this differs from a pure design. Five values would
make a safe label, but Loki's OTLP endpoint only promotes **resource**
attributes to labels, and severity belongs to each record. Loki instead
detects the level into `detected_level`, which Grafana's level filter and
`| detected_level="WARN"` use. Making it a real label would need a collector
in between to rewrite records. That isn't worth it for five values that
filter quickly anyway.

Finding one payment: `{service_name="payment-service"} |= "<paymentId>"`.
Finding one request: `{service_name="payment-service"} | correlationId="<id>"`.

### 2. No personal data, again

Episode 15's rule (no account IDs, amounts, references or customer IDs in
logs or metrics) now covers the log store too. The rule doesn't change, but
the stakes do. The console scrolls past one person. The store keeps every
line for a week, searchable by everyone with Grafana access.

Nothing new is logged. What leaves the process is checked: the body, and
every attribute (`correlationId`, and any exception's type, message and
stack trace). The end-to-end test completes one payment and rejects one for
an unknown account. It then asserts that no record exported so far in the
class contains `ACC-10001`, `ACC-20001`, `ACC-99999`, `CUST-1001` or a
payment's reference. Payment IDs are allowed, as in Episode 15: they are
opaque and are what support searches by.

### 3. When Loki is down

Logging must never become the reason a payment fails. Three properties
guarantee it:

- **Asynchronous.** `log.info` returns once the record is in the batch
  processor's queue. The export happens on the processor's own thread, every
  `schedule-delay` (1s) or when 512 records are waiting. A slow Loki slows
  that thread, not the request.
- **Bounded.** The queue holds 2048 records. When it is full because Loki is
  slow or down, **new records are dropped**, not buffered without limit.
  Memory use is capped, and a long outage costs log lines, not the
  service.
- **Console still has everything.** CONSOLE and OTEL are separate appenders.
  A dropped record is dropped from the OTLP path only.

Verified both ways. In the tests, the OTLP exporter points at a port where
nothing listens (`localhost:9`) for the whole end-to-end class, so all 50
tests run with Loki down. Locally, with `docker compose stop loki`, a
payment still completes and `POST /execute` still answers in about 15 ms.
The exporter's complaint appears on the console every few seconds at most,
because the SDK throttles its own errors.

**Why logs don't go through the outbox (B3).** The outbox is for business
facts that other systems act on. A `PaymentCompleted` event must never be lost
or sent for a change that rolled back, so it is written in the same
transaction as the change. A log line is an operational note. Losing some
during an outage is acceptable, and the console keeps them. Writing every
line to PostgreSQL inside the transaction would turn every log statement
into a database write and make the log store's availability a payment
dependency, the opposite of the goal.

### 4. Retention

`observability/loki.yaml`:

```yaml
limits_config:
  retention_period: 168h        # 7 days

compactor:
  retention_enabled: true       # without this, retention_period is ignored
  delete_request_store: filesystem
```

Logs are operational: they answer "what happened in the last few days". A
week covers an incident found on Monday that started on Friday. Production
usually keeps 14 to 30 days, depending on cost and policy. Logs are **not**
the record of what happened to a payment. That record is the audit table
(Episode 13), kept as long as the payment data, in the database, in the same
transaction as each change.

## Logs vs audit vs events

Three kinds of record, three stores, three sets of rules:

| | Logs | Audit (Episode 13) | Events (B3) |
|---|---|---|---|
| Answers | What did the service do, and why did it fail? | What happened to this payment, and when? | What should other systems know? |
| Written | Anywhere, best effort | With the change, same transaction | With the change, same transaction (outbox) |
| Loss | Acceptable | Never | Never (at least once) |
| Store | Console, Loki (7 days) | PostgreSQL, kept with the payment | `payment-events` topic |
| Read by | Developers, operations | Support, compliance | Other services |
| Personal data | None | What the business needs | What consumers need |

## Architecture

No change to domain or application. They log through SLF4J as before, and
the appender lives in `config/` and `logback-spring.xml`. B5's ArchUnit rule
(no `io.opentelemetry` in the domain or application) already covers the new
types. No new boundary appeared, so no new rule was added.

## What changed

```text
pom.xml                                                  + opentelemetry-logback-appender-1.0
docker-compose.yml                                       + loki; payment-service gets the logs endpoint
observability/loki.yaml                                  new
observability/grafana/datasources/loki.yml               new: derived field trace_id → Tempo
observability/grafana/datasources/tempo.yml              + uid, trace to logs, replaces B5's uid-less data source
src/main/resources/logback-spring.xml                    new: CONSOLE (unchanged) + OTEL
src/main/resources/application.properties                + logs endpoint, environment, queue settings
src/main/java/com/example/payment/config/
└── OpenTelemetryLoggingConfiguration.java               new: installs the appender
```

No domain or application class changed.

## Testing logs

There is no Loki in tests. Boot's batch processor exports to **every**
`LogRecordExporter` bean, so the test adds an `InMemoryLogRecordExporter`
next to the OTLP one. Records take the production path: the same appender,
processor and batches. They reach the in-memory exporter, and fail to reach
Loki. Assertions wait for them (`await`), because a batch leaves up to a
second after the line.

`OpenTelemetryAppender.install` sets a static: the last application context
to start wins. The test suite caches several contexts, so the end-to-end test
installs its own context's SDK before each test.

## Known shortcuts

- **No log-based alerting.** Loki's ruler can alert on LogQL (for example,
  "Unknown status from payment system" more than N times in 5 minutes). The
  metrics (Episode 15) cover the alerts that matter today.
- **Single tenant.** `auth_enabled: false`. A shared Loki would separate
  teams with `X-Scope-OrgID` and per-tenant limits.
- **Local file system storage.** One process, one disk. Production runs Loki
  on object storage (S3, GCS) with separate read and write paths.
- **No sampling of debug logs.** At INFO the service logs a few lines per
  payment. Turning DEBUG on in production would send everything. A filter
  on the OTEL appender, or a collector, would sample it.
- **The level is not a label** (lesson 1). Loki's OTLP endpoint can't make it
  one without a collector.
- **A log line's trace may not exist.** Every line carries a `trace_id`, but
  production keeps 10% of traces (B5). For the other 90%, "View trace" finds
  nothing in Tempo, though the trace ID still groups the lines in Loki.

## Try it

```bash
./mvnw spring-boot:run
```

1. Create and execute a payment ("Episode 09" in
   [http/payments.http](../../http/payments.http)).
2. Open Grafana at http://localhost:3000, go to Explore, choose **Loki**, and
   run `{service_name="payment-service"} |= "<paymentId>"`. The lines from the
   request and from the Kafka consumer are all there, each with `trace_id`,
   `span_id` and `correlationId` under its fields.
3. Click **View trace** next to `trace_id`. Tempo opens the payment's trace.
4. On any span, click **Logs for this span**. Loki shows that trace's lines.
5. `docker compose stop loki`, then execute another payment. It completes,
   and its lines are in the service's console. `docker compose start loki`.

## Tests

| Test | Change |
|---|---|
| `PaymentEndToEndTest` | **+4.** The whole class runs with Loki down (the OTLP endpoint points at a closed port), and records also reach an in-memory exporter. A line logged in a request is exported with the request's `trace_id`, `span_id` and `correlationId`, and that trace has the request's server span. The consumer's "moved from PROCESSING to COMPLETED" line carries the same trace ID as the request's "queued for processing" line and the `execute` span. No exported record (body or attributes) contains an account ID, the customer ID or a reference. With the OTLP exporter present and failing, a payment completes, and its line reaches the console and the in-memory exporter. |

232 tests, passing.
