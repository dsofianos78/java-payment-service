# Episode 20 — Final Architecture Review

Git tag: `episode-20-final-review` · Previous: [Episode 19 — Production-Style Local Environment](19-local-environment.md)

## Goal

Nineteen episodes each added one boundary when a requirement asked for it.
This episode reads the whole service as it now stands and asks three
questions:

1. **Does the code still have the reference structure?** And where it
   doesn't, is there a reason?
2. **What is each boundary responsible for?** One answer per package.
3. **Is the dependency direction enforced, or only hoped for?**

Two gaps came out of the review. Both are fixed in this episode: the
dependency rule was only partly checked, and the service had no OpenAPI
contract.

> Clean Architecture is not a package structure. The package structure is
> the visible result of boundaries that protect business rules and isolate
> change.

## The structure, compared with the reference

```text
application/
├── exception/          10 exceptions
├── port/
│   ├── primary/         5 use cases
│   └── secondary/       9 ports
├── service/
│   ├── command/         4 services
│   └── query/           1 service
├── usecase/
│   ├── command/         3 commands
│   └── query/           1 query, 1 result
└── validation/          AccountStateValidator

config/                  FeignConfiguration

domain/
├── entity/              Payment
├── service/             (empty: see below)
└── valueobject/         7 value objects

infrastructure/
├── adapter/
│   ├── primary/
│   │   ├── messaging/   PaymentProcessingConsumer, PaymentProcessingMessage
│   │   └── web/         PaymentController, request, response, exception handler
│   └── secondary/
│       ├── feign/       4 clients, 4 adapters, 1 DTO
│       ├── messaging/   PaymentProcessingPublisher   (not in the reference)
│       ├── persistence/ 3 JPA entities, 3 Spring Data repositories, 3 adapters, 1 mapper
│       └── stub/        (empty: see below)
└── observability/       correlation IDs (HTTP, Feign, Kafka), PaymentMetrics
```

Three places differ from the reference. Each is a decision, not drift:

- **`domain/service` is empty.** The reference expects domain services
  like `PaymentAuthorizationService` and `PaymentLimitPolicy`. In this
  service, authorization and limits are decided by external systems
  (Episode 12). The application asks them through `PaymentAuthorizationPort`
  and `PaymentLimitPort`. Every rule the domain owns, such as "a payment
  can't go from `COMPLETED` to `PROCESSING`" or "the amount must be positive",
  belongs to a single `Payment` and lives on it. A domain service with
  nothing to do would only be a package filler. The package is ready for
  the first rule that spans two aggregates.
- **`secondary/stub` is empty.** It held `StubAccountEnquiryAdapter` from
  Episode 03 to 07, and `StubPaymentExecutionAdapter` from Episode 09 to 16.
  Each was replaced by a Feign adapter once the real external system was
  needed, and WireMock took the stub's place in tests and locally. The
  package did its job: features worked before their external systems
  existed.
- **`secondary/messaging` is added.** Episode 17 needed an outgoing Kafka
  adapter. It sits next to `feign` and `persistence` for the same reason
  that primary `messaging` sits next to `web`: one package per technology,
  on each side. Putting the publisher in primary `messaging` would have
  mixed incoming and outgoing adapters in one package.

## The responsibility of every boundary

### Domain: the business rules

| Package | Responsible for | Never contains |
|---|---|---|
| `domain/entity` | `Payment` and its life cycle: `create`, `authorize`, `startProcessing`, `complete`, `fail`, `cancel`. It refuses every transition its status doesn't allow. | Spring, JPA, HTTP, Kafka; IDs from a database; anything about *how* a payment is stored or sent |
| `domain/valueobject` | Concepts that are valid or don't exist: `Money` (no more decimals than its currency allows), `Currency` (supported ISO codes), `AccountId`, `PaymentId`, `PaymentReference`, `PaymentStatus`, `AccountStatus` | Primitive wrappers with no rule in them |
| `domain/service` | Rules that span more than one aggregate. None yet. | |

### Application: what the service can do, and what it needs

| Package | Responsible for | Never contains |
|---|---|---|
| `application/port/primary` | What the outside world may ask: create, get, request execution, execute, cancel | HTTP or message types |
| `application/port/secondary` | What the application needs from the outside world, in its own words: "find this payment", "is this account active", "is this payment within its limit", "move the money", "process this later" | Feign, JPA or Kafka types |
| `application/usecase/command`, `query` | The input and output of each use case: plain records the adapters fill and read | Validation annotations, JSON names |
| `application/service/command`, `query` | The order of the steps, the transaction boundaries, idempotency and audit. They call the domain to decide, and the ports to act. | Business rules (they live in the domain), protocol details |
| `application/validation` | Rules that need a port: both accounts exist and are active | Rules about one value (those are domain invariants) |
| `application/exception` | Failures in business terms: not found, invalid state, not authorized, limit exceeded, external system unavailable | HTTP status codes |

### Config: wiring

| Package | Responsible for | Never contains |
|---|---|---|
| `config` | Spring wiring that belongs to no single adapter: turns on Feign clients | Behaviour. Nothing depends on it. |

### Infrastructure: translation

Every adapter does one thing: translate between a technology and the
application's language.

| Package | Translates | Never contains |
|---|---|---|
| `adapter/primary/web` | HTTP to use cases, and results and exceptions back to HTTP (`201`, `202`, `400`, `404`, `409`, `422`, `503`) | Decisions: the [architecture test](../../src/test/java/com/example/payment/infrastructure/architecture/ArchitectureTest.java) refuses any domain constructor, state change or status constant here |
| `adapter/primary/messaging` | A Kafka message to `ExecutePaymentUseCase` | Retry logic of its own (Spring Kafka's retry topics do it) |
| `adapter/secondary/feign` | The application's questions to the external account, authorization, limit and payment systems, through timeouts, retries and circuit breakers | External DTOs outside this package |
| `adapter/secondary/messaging` | "Process this payment later" to a Kafka record | Topic names outside this package |
| `adapter/secondary/persistence` | `Payment` to JPA entities and back; idempotency keys and audit rows | A JPA entity that leaves this package |
| `adapter/secondary/stub` | (Nothing now.) Fixed answers in place of an external system that doesn't exist yet | |
| `observability` | Correlation IDs across HTTP, Feign and Kafka; payment metrics | Account IDs, amounts or references in logs and metrics |

## Dependency direction

The reference states one rule:

```text
Infrastructure -> Application -> Domain
```

not the reverse.

Episode 14's architecture test checked two arrows of this: the domain and
the application don't depend on infrastructure. It didn't check that the
domain doesn't depend on the application, or that nothing depends on
`config`. A `Payment` that threw `PaymentNotFoundException` would have
passed the build.

The two separate rules are now one rule that states the whole direction:

```java
@ArchTest
static final ArchRule dependenciesPointInwards = layeredArchitecture().consideringOnlyDependenciesInLayers()
        .layer("Config").definedBy("com.example.payment.config..")
        .layer("Infrastructure").definedBy("com.example.payment.infrastructure..")
        .layer("Application").definedBy("com.example.payment.application..")
        .layer("Domain").definedBy(DOMAIN)
        .whereLayer("Config").mayNotBeAccessedByAnyLayer()
        .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Config")
        .whereLayer("Application").mayOnlyBeAccessedByLayers("Infrastructure", "Config")
        .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Config");
```

It reads like the diagram. `Config` sits outside everything: it may see every
layer, and no layer may see it. The production code passed it unchanged.
To check that the rule bites, a field of type `PaymentNotFoundException`
was added to the domain for one run. The build failed, then the field was
removed.

The other four rules stay. They check what a direction rule can't: the
domain uses no Spring, JPA or Feign, uses no JPA entity, and the web
adapter makes no business decisions.

## Capabilities, compared with the reference

| Capability | Where | Episode |
|---|---|---|
| Create payment | `CreatePaymentService` | 01 |
| Validate | domain invariants, `AccountStateValidator` | 06 |
| Enquire accounts | `AccountEnquiryPort` → account system | 03, 07 |
| Authorize | `PaymentAuthorizationPort` → authorization system | 12 |
| Check limits | `PaymentLimitPort` → limit system | 12 |
| Persist | `PaymentRepository` → PostgreSQL | 04 |
| Process | `ExecutePaymentService` → payment system: `COMPLETED` or `FAILED` | 09 |
| Query payment | `GetPaymentService` | 05 |
| Cancel payment | `CancelPaymentService` | 11 |
| Idempotency | `IdempotencyPort` | 10 |
| Audit, transactions | `AuditPort`, `TransactionOperations` | 13 |
| External integrations | Feign adapters | 07, 12, 16 |
| Async processing | Kafka, both directions | 17 |
| Error handling | `PaymentExceptionHandler`, problem details | 08 |
| Resilience | timeouts, retries, circuit breakers | 16 |
| Observability | correlation IDs, JSON logs, Prometheus | 15 |
| Architecture enforcement | `ArchitectureTest` | 14, 20 |
| Integration testing | Testcontainers, WireMock | 04, 07, 18 |
| Local infrastructure | `docker-compose.yml`, `Dockerfile` | 19 |
| **OpenAPI** | `/v3/api-docs`, `/swagger-ui.html` | **20** |

One step runs in a different order from the reference flow. The reference
reads *Validate → Enquire → Authorize → Check limits → Persist → Process*.
Here, a payment is persisted after the account enquiry, and is authorized and
checked against its limit when it is *executed*. A limit depends on what the
account has spent by the time the money moves, not when the payment was
entered (Episode 12). Persisting first also gives the client an ID to
execute, query or cancel.

## OpenAPI

The only capability with no episode was OpenAPI. One dependency adds it:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>${springdoc.version}</version>
</dependency>
```

springdoc reads `PaymentController`, its records and its validation
annotations, and publishes the contract at `/v3/api-docs` and a browsable
page at `/swagger-ui.html`. No annotation was added to the controller.
The contract is generated from the adapter, so it can't go out of date.
It lives where it belongs: in the web adapter, the only place that knows
about HTTP.

## What changed

```text
pom.xml                         + springdoc-openapi-starter-webmvc-ui 3.1.1
ArchitectureTest                domainDoesNotDependOnInfrastructure, applicationDoesNotDependOnInfrastructure
                                -> dependenciesPointInwards (one layered rule: Infrastructure -> Application -> Domain, config outside)
PaymentEndToEndTest             + publishesTheApiContract
docs/episodes/20-final-review.md new
README.md                       + episode 20 row
```

No production Java code changed.

## Known shortcuts

- **The API docs are public.** springdoc warns at start-up that
  `/v3/api-docs` and `/swagger-ui.html` are on by default. That suits a
  local tutorial. A real deployment decides this per environment, from
  outside, as in Episode 19:
  `SPRINGDOC_API_DOCS_ENABLED=false`, `SPRINGDOC_SWAGGER_UI_ENABLED=false`.
- **The contract has no descriptions.** It lists paths, bodies and status
  codes as the code declares them. Add `@Operation` and `@ApiResponse` when
  a client team needs more than that.
- **`domain/service` and `secondary/stub` are empty.** On purpose, as
  explained above.
- **Earlier shortcuts still stand.** Each episode's "Known shortcuts"
  section lists them: one WireMock plays four systems, a payment left
  `PROCESSING` after a timeout needs reconciliation, Grafana has no
  dashboard.

## Try it

```bash
./mvnw spring-boot:run
```

1. Open <http://localhost:8080/swagger-ui.html>. The four payment
   operations are listed, with the request body and its fields.
2. `curl -s localhost:8080/v3/api-docs | jq '.paths | keys'`:
   `/payments`, `/payments/{paymentId}`, `/payments/{paymentId}/cancel`,
   `/payments/{paymentId}/execute`.
3. Create and execute a payment from the Swagger page. It behaves exactly as
   the requests in [http/payments.http](../../http/payments.http).

## Tests

| Test | Change |
|---|---|
| `infrastructure/architecture/ArchitectureTest` | **2 rules → 1.** `dependenciesPointInwards` replaces the two "no dependency on infrastructure" rules, and adds the arrows they didn't check. |
| `PaymentEndToEndTest` | **+1.** `/v3/api-docs` is served and lists all four operations. |

147 tests, passing.
