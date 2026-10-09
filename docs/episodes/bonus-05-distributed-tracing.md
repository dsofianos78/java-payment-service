# Bonus Episode 05 — Distributed Tracing

Git tag: `bonus-05-distributed-tracing` · Builds on: [Bonus Episode 04 — Refunds](bonus-04-refunds.md)

## Goal

A payment crosses many systems: the HTTP request, the account system, Kafka,
the consumer, the authorization, limit and payment systems, the outbox relay
and `payment-events`. When one payment is slow or stuck, operations must see
the whole path as one tree, with how long each hop took.

Episode 15 listed "No distributed tracing" as a known shortcut. This episode
closes it. No business logic changes, and there are no new use cases.

## Correlation ID vs trace

Episode 15's correlation ID answers one question: *which log lines belong to
this request?* Search for it and you get every line, across the request, the
consumer and the calls to the external systems.

It can't say where the time went, or which call caused which. Ten log lines
with the same ID are a flat list. A **trace** is a tree of **spans**. Each
span is one hop (the request, a Kafka send, a Feign call) with a start, a
duration and a parent. The tree crosses processes and threads in the W3C
`traceparent` header:

```text
traceparent: 00-4b8d6954d84f0c2d0c97c5181784c181-6f092cf5c9ef620c-01
                └─ trace ID (whole tree) ─────────┘ └─ parent span ┘ └ sampled
```

**Both stay.** `X-Correlation-Id` is the client-facing ID: clients send it,
support asks for it, and it's in the response. Trace IDs are generated inside
the service, and clients never see them. Episode 15's filter and interceptors
are unchanged. Log lines now carry both. A scheduled run has no
correlation ID, only its trace:

```json
{"message":"Payment 9a54… created","traceId":"46b4f2b0…","spanId":"95027988…","correlationId":"corr-1234", …}
```

From a support ticket, search the logs for the correlation ID, take the
`traceId` from any line, and open the trace in Tempo.

## Infrastructure

### Dependencies

```text
spring-boot-starter-opentelemetry   Micrometer Tracing, the OpenTelemetry bridge, the OTLP exporter
feign-micrometer                    without it, Spring Cloud OpenFeign doesn't observe calls: no span, no header
```

No span is written by hand for what Spring already instruments:

| Hop | Instrumented by | Switched on by |
|---|---|---|
| HTTP request | Spring MVC | the starter |
| Feign call | Spring Cloud OpenFeign | `feign-micrometer` on the classpath |
| `@Scheduled` run | Spring Framework | the starter |
| Kafka send / listener | Spring Kafka | `spring.kafka.template.observation-enabled`, `spring.kafka.listener.observation-enabled` |

Kafka is the one you have to turn on (see "Async execution" below).

### Tempo

`docker-compose.yml` gets Grafana Tempo. It receives spans over OTLP/HTTP on
`4318` ([observability/tempo.yaml](../../observability/tempo.yaml)), and
Grafana has it as a second data source next to Prometheus
([observability/grafana/datasources/tempo.yml](../../observability/grafana/datasources/tempo.yml)).

```properties
management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces
management.otlp.metrics.export.enabled=false
```

The containerised service gets `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://tempo:4318/v1/traces`.

The second line matters. The OpenTelemetry starter also brings an OTLP
*metrics* registry, which pushes every metric to `localhost:4318/v1/metrics`
once a minute. Tempo takes traces only, so each push failed with a `WARN`.
Metrics already go to Prometheus (Episode 15), so the OTLP push is off.

### Sampling

```properties
management.tracing.sampling.probability=0.1     # application.properties
management.tracing.sampling.probability=1.0     # application-local.properties
```

Every kept trace costs something at every hop: building the spans, sending
them, storing them. A payment is about 15 spans. At production volume, 10% of
traces still shows every slow path many times an hour. Locally, there is only
the one request you just sent, so everything is kept.

This is **head sampling**: the keep-or-drop decision is made once, where the
trace starts, and it travels in the `traceparent` flags (`-01` kept, `-00`
dropped). Every later hop follows it, so a trace is either whole or absent,
never half there. Its weakness is that the decision is made before anyone
knows whether the trace will be interesting. The slow payment you want is
dropped 90% of the time. **Tail sampling** decides after the trace ends: keep
every error, every trace over 2 seconds, and 1% of the rest. It needs a
collector in front of Tempo that holds whole traces in memory. It's mentioned
here, not built.

## Where the default breaks

Add the starter, and HTTP requests and Feign calls are traced. Four places
need more.

### 1. Async execution (Episode 17)

`POST /execute` publishes to `payment-processing`, and the consumer does the
real work. Without Kafka observation, the send has no span and the message
has no `traceparent`. The consumer then starts a **new** trace, and the
request and the four external calls show up as two unrelated trees.

The two `observation-enabled` properties fix it. The `KafkaTemplate` opens a
`payment-processing send` span and writes its `traceparent` into the message
headers. The listener reads it and opens `payment-processing process` as that
span's child. What Tempo shows for one payment (leaving out Spring
Security's spans, which sit between the request and its children):

```text
http post /payments/{paymentId}/execute                  SERVER    request thread
├── HTTP GET  /accounts/{accountNumber}                  CLIENT
└── payment-processing send                              PRODUCER
    └── payment-processing process                       CONSUMER  listener thread
        ├── HTTP POST /authorizations                    CLIENT
        ├── HTTP POST /limit-checks                      CLIENT
        ├── HTTP POST /payment-orders                    CLIENT
        └── payment-events relay                         INTERNAL  relay thread, later (2. below)
            └── payment-events send                      PRODUCER
```

**Retry topics** stay in the same trace. When the consumer throws, Spring
Kafka republishes the message to `payment-processing-retry-0` with the same
observed template. That send is a child of the failing `process` span, and
the retry consumer continues from it. This was checked by hand (the limit
system answering 503 for one payment):

```text
payment-processing process          ← failed, 3 Feign attempts inside
└── payment-processing-retry-0 send
    └── payment-processing-retry-0 process
        └── payment-processing-retry-1 send …
```

It isn't a permanent test. It waits at least 5 seconds for the first retry,
and its failing calls count against the limit system's circuit breaker, which
every other end-to-end test shares.

### 2. The outbox (B3)

The relay sends an event up to a second after the status change, on its
scheduler thread, in a run that no request started. By default, the
`payment-events send` span belongs to that run's trace, and the trace of the
payment that caused it ends at the commit.

Fix: the outbox row keeps the trace it was written in.

```sql
-- V7__add_outbox_trace_context.sql
ALTER TABLE payment_outbox ADD COLUMN trace_context VARCHAR(55);
```

- **Write.** `OutboxPersistenceAdapter.record` takes the current span and
  injects it with the same propagator that writes Kafka and HTTP headers. It
  stores the `traceparent` value in the column, in the same INSERT as the
  event. When there's no current span, it stores `null`.
- **Read.** `findUnpublished` returns it in `Pending.traceContext()`.
  `spanFor(pending)` extracts it into a span builder whose parent is the span
  that recorded the change.
- **Send.** `PaymentEventRelay` starts a `payment-events relay` span from that
  builder and sends inside it. The `KafkaTemplate`'s own send span takes the
  current span as its parent (Micrometer prefers a span you started yourself
  over the parent observation, here the scheduled run). So the send lands in
  the payment's trace, under the consumer span that completed it.

The gap in Tempo between the consumer span's end and the relay span's start
is the outbox delay. Until now, nothing showed that number.

**Why the trace context is not a field of `PaymentEvent`.** The event is a
business fact: payment X completed, for this amount, at this time. Which
trace recorded it is a fact about *our process*. It means nothing to a
consumer of `payment-events`, and it would change if the same fact were
recorded again. If it were in the event, the domain would carry a technical
field that no domain code reads or writes, and the ArchUnit rule below would
have to allow it. It lives in the adapter that owns the row, and in the one
other adapter that reads the row.

Rows written before V7, or outside any trace, have `null`. The relay span is
then a child of the relay run's own trace, which is the default behaviour.

### 3. Schedulers

Reconciliation (B2) and the relay run on a timer. Nothing called them, so
each run is a **new root trace**: `task reconciliationScheduler.run`,
`task paymentEventRelay.relay`. That's correct, and no parent is invented. A
reconciliation run that settles three payments is its own trace, with three
`HTTP GET /payment-orders/{paymentId}` children. It is linked to those
payments' earlier traces only through the payment ID in the logs.

The relay is the exception for its sends (2. above). The run itself is still
a root.

### 4. No personal data in spans

Episode 15's rule (no account IDs, amounts, references or customer IDs in
logs or metrics) now covers traces too. Each instrumented hop was checked:

| Hop | What the span carries | Result |
|---|---|---|
| HTTP server | `uri=/payments/{paymentId}/execute` (template). `http.url` is the expanded path, so a payment ID. | Payment IDs are allowed (Episode 15). No account ID is ever in a path. |
| Feign | `http.url=/accounts/{accountNumber}` (the **template**), method, status, client class | **No account ID.** feign-micrometer's default convention uses the method's template, not the expanded URL. |
| Feign, failed | A 404 is a response, so the client records no error. The `FeignException`, whose message contains the full URL, is thrown later by the error decoder, outside the span. | No URL in any span event. |
| Kafka | topic, operation, client and consumer group, offset | The message key (payment ID) isn't recorded. |
| Spring Security | authentication type, authorities, decision | No subject. The customer ID isn't in any span. |
| JDBC | not instrumented (no datasource-micrometer) | No SQL and no bound parameters. |

So nothing needed redacting. The default could change in a later Feign
version, so the end-to-end test checks the result, not the configuration: no
span name, attribute or event contains an account ID, the customer ID or a
payment reference. That covers every span the whole test class produced,
plus a payment rejected for an unknown account.

## Architecture

The domain and the application know nothing about tracing. No service has a
`Tracer`, an `Observation` or a `Span`. They log and record events as
before, and the adapters around them are traced. A new ArchUnit rule, like
B1's Spring Security rule:

```java
@ArchTest
static final ArchRule coreDoesNotDependOnTracing = noClasses()
        .that().resideInAnyPackage(DOMAIN, "com.example.payment.application..")
        .should().dependOnClassesThat().resideInAnyPackage("io.micrometer.tracing..", "io.opentelemetry..");
```

The only hand-written tracing code is in two infrastructure classes,
`OutboxPersistenceAdapter` and `PaymentEventRelay`, at the one hop no
library knows about: a database row as a carrier.

## What changed

```text
pom.xml                                                         + starter-opentelemetry, feign-micrometer;
                                                                  test: starter-opentelemetry-test, opentelemetry-sdk-testing
docker-compose.yml                                              + tempo; payment-service gets the OTLP endpoint
observability/tempo.yaml                                        new
observability/grafana/datasources/tempo.yml                     new
src/main/resources/application.properties                       + Kafka observation, OTLP endpoint, sampling 0.1
src/main/resources/application-local.properties                 + sampling 1.0
src/main/resources/db/migration/V7__add_outbox_trace_context.sql new
src/main/java/com/example/payment/infrastructure/adapter/secondary/
├── persistence/OutboxEntity.java                               + trace_context
├── persistence/OutboxPersistenceAdapter.java                   + writes the current traceparent; Pending.traceContext; spanFor
└── messaging/PaymentEventRelay.java                            + sends inside a span parented by the stored trace
```

No domain or application class changed.

## Testing tracing

Boot 4 keeps tracing on in tests but switches **export** off. One detail
cost a debugging round: with export off, Boot also leaves out the W3C
propagator (`ContextPropagators` is a no-op). Spans are created, but no
`traceparent` is written anywhere. The consumer starts a new trace and the
outbox column stays `null`. So `PaymentEndToEndTest` uses:

```java
@AutoConfigureMetrics   // the OpenTelemetry test starter brings Boot's metrics test support, which hides /actuator/prometheus without it
@AutoConfigureTracing   // export on, as in production, so headers are propagated
```

It turns off the OTLP exporter only (`management.tracing.export.otlp.enabled=false`,
so there's no Tempo in tests). A `SimpleSpanProcessor` hands each span to an
`InMemorySpanExporter` the moment it ends, and the tests read the spans from
there.

## Known shortcuts

- **No tail sampling.** Head sampling at 10% drops 90% of slow payments too.
  The next step is an OpenTelemetry Collector with the tail-sampling
  processor in front of Tempo: keep all errors and slow traces.
- **No link from metrics to traces.** A latency spike on a Grafana panel
  doesn't take you to an example trace. Half of it is already there: with
  tracing on, Boot adds exemplars (the current trace ID) to
  `/actuator/prometheus` in the OpenMetrics format. Nothing uses them yet.
  The next step is Prometheus with `--enable-feature=exemplar-storage`, plus
  a Tempo link on the Prometheus data source. Span metrics (Tempo's metrics
  generator: rate, errors and duration per span name) are not built either.
- **No retention tuning.** Tempo keeps its defaults, on the container's disk.
- **The relay starts a trace every second.** Each run, even one with nothing
  to send, is a root trace: 86,400 a day locally, 8,640 at 10%. Drop empty
  runs with a `SpanExportingPredicate`, or let tail sampling do it.
- **Spring Security's spans are kept.** `security filterchain before/after`,
  `authorize request` and `secured request` add five spans to every request.
  They show where authentication time goes. An `ObservationPredicate` can
  drop them if they turn out to be noise.

## Try it

```bash
docker compose up -d && ./mvnw spring-boot:run
```

1. Create and execute a payment ("Episode 09" in
   [http/payments.http](../../http/payments.http)).
2. Find the payment's lines in the service's log. Each has a `traceId`.
3. Open Grafana at http://localhost:3000, go to Explore, choose **Tempo**,
   and paste the trace ID. The request and its account lookup, the Kafka
   hop, the consumer's three calls and, a moment later, the relay's send are
   all in one tree.
4. In the Prometheus data source nothing changed: metrics still go there,
   not to Tempo.

## Tests

| Test | Change |
|---|---|
| `ArchitectureTest` | **+1.** `coreDoesNotDependOnTracing`: no `io.micrometer.tracing` or `io.opentelemetry` in the domain or application. |
| `PaymentEndToEndTest` | **+4.** Create and execute one payment: the `execute` request span, `payment-processing send`, `payment-processing process` and the `/payment-orders` call share one trace ID. The outbox row's `traceparent` has that trace ID, and the `payment-events relay` span's parent is the span it names, with `payment-events send` as the relay span's child. No span name, attribute or event contains an account ID, the customer ID or a reference. A log line inside a request carries `traceId` and `spanId`, and that trace has the request's server span. |
| `OutboxPersistenceAdapterTest` | No-op `Tracer` and `Propagator`. The trace context round trip is the end-to-end test's subject. |
| `PaymentEventRelayTest` | Sends in no trace (`Tracer.NOOP`). Same assertions. |

228 tests, passing.
