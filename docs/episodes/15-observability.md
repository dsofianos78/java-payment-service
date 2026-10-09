# Episode 15 — Observability

Git tag: `episode-15-observability` · Previous: [Episode 14 — Architecture Tests](14-architecture-tests.md) · Next: Episode 16 — Resilience

## Goal

The service works, but from the outside you can't see it working. A
customer asks why their payment failed and you search the logs for a time
range. Payments slow down and you find out from the customers.

This episode adds three ways to see inside:

- **Correlation IDs.** Every request gets an ID that appears in every log line
  it writes and is passed on to every external system it calls.
- **Structured logging.** Each log line is a JSON object, so a log platform
  can filter by `correlationId` instead of searching text.
- **Metrics.** Counters and a timer, exported to Prometheus and graphed in
  Grafana.

All of it lives in `infrastructure/observability`. The application reports
what happened through a port. It doesn't know Micrometer exists.

## Correlation IDs

`CorrelationIdFilter` runs before anything else on every request:

```text
X-Correlation-Id: abc-123   ->  use it
no header, or not [A-Za-z0-9._-]{1,64}  ->  new UUID
```

The ID is then:

1. put in the logging context (SLF4J `MDC`), so every log line of the request carries it;
2. returned in the response's `X-Correlation-Id` header;
3. sent to the account, authorization and limit systems by `CorrelationIdFeignInterceptor`.

The header is caller input that ends up in our logs and responses, so the
filter replaces anything unusual rather than trusting it.

## Structured logging

One line in `application.properties`:

```properties
logging.structured.format.console=ecs
```

Spring Boot then writes each line as an [Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/index.html)
JSON object. MDC entries become fields:

```json
{"@timestamp":"2026-10-09T07:58:41.615Z","log":{"level":"INFO","logger":"...CreatePaymentService"},
 "message":"Payment 38929b97-c7b8-43a9-b7a7-da351d58b202 created","correlationId":"demo-15", ...}
```

Each status change is logged once, after it commits:

```text
Payment 38929b97-... created
Payment 38929b97-... moved from CREATED to AUTHORIZED
Payment 38929b97-... moved from AUTHORIZED to PROCESSING
Payment 38929b97-... moved from PROCESSING to COMPLETED
```

### What is *not* logged

The payment ID is an opaque UUID. Logs never contain account IDs, amounts or
references. With the ID you can find those in the database, where access is
controlled. A log platform gives far more people access.

Feign's exception message is not logged either. It contains the request URL,
and for the account system that URL contains the account ID
(`/accounts/ACC-10001`). The adapters log the HTTP status only:

```text
Account system unavailable (status 503)
```

## Metrics

| Spec name | Prometheus series | Recorded by |
|---|---|---|
| `payments.created` … `payments.cancelled` | `payments_total{status="created"}` … `{status="cancelled"}` | services, via `PaymentMetricsPort` |
| `payment.execution.duration` | `payment_execution_duration_seconds` (count, sum, max) | `ExecutePaymentService`, via `PaymentMetricsPort` |
| `external.account.errors` | `external_account_errors_total` | `AccountEnquiryFeignAdapter` |
| `external.payment.errors` | `external_payment_errors_total{system="authorization"\|"limit"}` | `AuthorizationAdapter`, `LimitAdapter` |

**Why one `payments` counter tagged by status, not six counters?** The
Prometheus client reserves the `_created` suffix for counters (it holds the
time the counter was created). A counter named `payments.created` is
exported as plain `payments_total`, which says nothing. One counter tagged by
status avoids that. It is also how Prometheus expects it: one query graphs all
six:

```promql
sum by (status) (rate(payments_total[5m]))
```

**Only committed facts are counted.** The services report a status change
after its transaction commits, not inside it. A change that rolled back
never happened, so it isn't counted. A replayed idempotent request is not a
new payment, so `created` is not counted again.

**No tags carry customer data.** Metrics say how many, never whose. An
account ID as a tag would also give Prometheus a new time series per account.

`payment.execution.duration` times the call to `PaymentExecutionPort`, the
step where the money moves. For now that is the stub, so expect
microseconds.

### The port

```text
application/port/secondary/PaymentMetricsPort     statusChanged(status), executionTook(duration)
infrastructure/observability/PaymentMetrics       implements it with Micrometer
```

The services call the port. `PaymentMetrics` turns the calls into Micrometer
meters. The external-error counters are counted in the Feign adapters, which
are infrastructure themselves, so they use `PaymentMetrics` directly.
`ArchitectureTest` still passes: `application` never sees Micrometer.

## Prometheus and Grafana

`spring-boot-starter-actuator` and `micrometer-registry-prometheus` expose
`/actuator/prometheus`. Only `health` and `prometheus` are exposed.

`compose.yaml` gains two containers, which Spring Boot starts with the app:

| Container | URL | What |
|---|---|---|
| `prometheus` | http://localhost:9090 | Scrapes `host.docker.internal:8080/actuator/prometheus` every 5 s ([observability/prometheus.yml](../../observability/prometheus.yml)) |
| `grafana` | http://localhost:3000 | Anonymous admin, Prometheus already added as the data source |

> **Linux with a host firewall (e.g. ufw):** the firewall may drop traffic from
> containers to the host, and Prometheus shows the target as *down* with
> `context deadline exceeded`. Allow it, for example
> `sudo ufw allow from 172.16.0.0/12 to any port 8080`.

## What changed

```text
pom.xml                                                    + actuator, micrometer-registry-prometheus
compose.yaml                                               + prometheus, grafana
observability/prometheus.yml                               new
observability/grafana/datasources/prometheus.yml           new
src/main/resources/application.properties                  + structured logging, exposed endpoints
src/main/java/com/example/payment/
├── application/port/secondary/PaymentMetricsPort.java     new
├── application/service/command/
│   ├── CreatePaymentService.java                          logs and counts CREATED
│   ├── ExecutePaymentService.java                         logs and counts each transition, times execution
│   └── CancelPaymentService.java                          logs and counts CANCELLED
└── infrastructure/
    ├── adapter/secondary/feign/
    │   ├── AccountEnquiryFeignAdapter.java                counts and logs (status only) account-system errors
    │   ├── AuthorizationAdapter.java                      same, for the authorization system
    │   └── LimitAdapter.java                              same, for the limit system
    └── observability/
        ├── CorrelationIdFilter.java                       new
        ├── CorrelationIdFeignInterceptor.java             new
        └── PaymentMetrics.java                            new
```

The domain is unchanged.

## Known shortcuts

- **No Grafana dashboard.** The data source is ready. Add a panel with the
  query above. A provisioned dashboard is a JSON file in
  `observability/grafana/dashboards` when it's worth keeping.
- **No distributed tracing.** The correlation ID covers "find everything
  about this request". Spans and timings per hop (Micrometer Tracing,
  OpenTelemetry) are a step further.
- **Grafana runs with anonymous admin.** Fine for a local container, never
  for anything shared.
- **Logs are JSON in the terminal too.** Delete the
  `logging.structured.format.console` line locally if you prefer plain text.

## Try it

```bash
./mvnw spring-boot:run
```

Send the Episode 15 requests in [http/payments.http](../../http/payments.http),
then execute the payment. In the console, every line of that request has
`"correlationId":"episode-15-demo"`.

Open http://localhost:9090 and query `payments_total`, or open Grafana at
http://localhost:3000 → Explore.

## Tests

| Test | What it proves |
|---|---|
| `PaymentControllerTest` | **3 new.** The caller's correlation ID is returned and reaches the account system. A malformed one is replaced by a UUID. After create and execute, `/actuator/prometheus` shows the status counters and the execution timer. |
| `AccountEnquiryFeignAdapterTest` | **Changed.** An account-system 503 increments `external.account.errors`. |
| `AuthorizationAndLimitAdapterTest` | **Changed.** Authorization and limit outages increment `external.payment.errors` with their `system` tag. |
| `CreatePaymentServiceTest`, `ExecutePaymentServiceTest`, `CancelPaymentServiceTest` | **Changed.** Pass a mock `PaymentMetricsPort`. Behaviour unchanged. |
| `ArchitectureTest` | Unchanged and passing: Micrometer stays out of `application` and `domain`. |
