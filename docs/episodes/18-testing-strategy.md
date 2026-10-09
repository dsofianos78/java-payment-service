# Episode 18 — Testing Strategy

Git tag: `episode-18-testing-strategy` · Previous: [Episode 17 — Asynchronous Processing](17-async-processing.md) · Next: Episode 19 — Production-Style Local Environment

## Goal

Seventeen episodes added 143 tests, one or two at a time, each next to the
code it covered. This episode steps back and asks two questions:

1. **Where is each test?** Under the package of the code it tests, so the
   test tree has the same shape as the production tree.
2. **What level is it?** A test that needs Docker and a Spring context
   costs a thousand times more than one that calls a constructor. Each
   behaviour should be tested at the cheapest level that can catch the bug.

Most of the answer was already in place. Three things were out of line, and
this episode fixes them.

## The levels

| Level | What's real | What's faked | Tests | Time |
|---|---|---|---|---|
| Domain unit | `domain` only | nothing | `PaymentTest`, `MoneyTest`, `CurrencyTest`, `PaymentReferenceTest` | ms |
| Application unit | services, validator, domain | every secondary port, as a lambda or an anonymous class | `*ServiceTest`, `AccountStateValidatorTest` | ms |
| Adapter | the controller, Spring MVC's JSON and error handling | the primary ports, as lambdas | `PaymentControllerPortTest` | ms |
| Persistence integration | JPA, Flyway, PostgreSQL (Testcontainers) | nothing below the adapter | `PaymentPersistenceAdapterTest`, `IdempotencyPersistenceAdapterTest`, `PaymentTransactionsTest` | seconds |
| External integration | Feign, Resilience4j, HTTP | the external systems, as WireMock | `AccountEnquiryFeignAdapterTest`, `AuthorizationAndLimitAdapterTest`, `PaymentExecutionAdapterTest` | seconds |
| Architecture | the compiled classes | nothing runs | `ArchitectureTest` | ~1 s |
| End-to-end | the whole service: PostgreSQL and Kafka (Testcontainers) | the external systems (WireMock), the HTTP server (MockMvc) | `PaymentEndToEndTest` | ~12 s + start-up |

The ports make the cheap levels possible. `CreatePaymentService` only knows
`AccountEnquiryPort`, `PaymentRepository` and `IdempotencyPort`, so its 17
tests pass a lambda for each and run in 30 ms. The domain knows nothing
outside itself, so it needs no fakes at all.

The expensive levels each answer a question the cheap ones can't:

- **Persistence:** does the SQL work on PostgreSQL? `PaymentTransactionsTest`
  is here, not under unit tests. It checks that a failed audit insert rolls
  back the status change, which only a real database can show.
- **External:** does the Feign client send the right JSON, map a `404` to
  "not found", and retry or open the circuit when it should? WireMock gives
  real HTTP with controlled answers.
- **End-to-end:** does the wiring hold? HTTP to Kafka to the consumer to the
  payment system, with correlation IDs and metrics along the way.

## Where the tests live

```text
src/test/java/com/example/payment/
├── PaymentEndToEndTest.java                         renamed from …/web/PaymentControllerTest
├── TestcontainersConfiguration.java
├── domain/
│   ├── entity/PaymentTest.java
│   └── valueobject/CurrencyTest, MoneyTest, PaymentReferenceTest
├── application/
│   ├── service/command/*ServiceTest, PaymentTransactionsTest
│   ├── service/query/GetPaymentServiceTest
│   └── validation/AccountStateValidatorTest.java    new
└── infrastructure/
    ├── adapter/primary/web/PaymentControllerPortTest.java
    ├── adapter/secondary/feign/*AdapterTest
    ├── adapter/secondary/persistence/*AdapterTest
    └── architecture/ArchitectureTest.java           moved from the root package
```

## What changed

- **`PaymentControllerTest` → `PaymentEndToEndTest`**, moved to the root
  package. From Episode 01 it has started the whole application, not just
  the controller, and since Episode 17 it also covers Kafka and the consumer.
  The old name and package made it look like an adapter test. It's the one
  test that covers every package, so it sits beside `PaymentApplication`.
- **`ArchitectureTest`** moves to `infrastructure/architecture`, as the spec's
  tree places it. Its rules are unchanged.
- **`AccountStateValidatorTest`** is new. The validator was only tested
  through `CreatePaymentServiceTest`. Its own four cases now name the rule
  directly: both active passes; inactive source, inactive destination and
  unknown account each fail with their own message.

No production code changed.

## Known shortcuts

- **No `domain/service` or `application/usecase` tests.** The domain has no
  services yet; nothing has needed one. The use-case records hold no logic.
  `GetPaymentResult.from` is checked field by field in `GetPaymentServiceTest`.
- **No separate messaging adapter test.** `PaymentProcessingConsumer` and
  `PaymentProcessingPublisher` only translate, and `PaymentEndToEndTest` runs
  them against a real Kafka, duplicates included. Add one when they gain
  behaviour of their own.
- **Each Spring test context starts its own PostgreSQL and Kafka.** A full
  build starts four of each. On one run, a Kafka container failed to start
  in time and its two test classes errored. The rerun was green. Sharing
  one container per JVM would also share the Kafka topic between the
  end-to-end and transaction contexts, whose consumers would then take each
  other's messages. Worth fixing with the Episode 19 local environment, or
  when it fails more than rarely.
- **All levels run in one `./mvnw test`.** No Failsafe split, no JUnit
  tags. The whole build takes under a minute, so splitting it saves nothing yet.

## Try it

Run one level at a time:

```bash
./mvnw test -Dtest='com.example.payment.domain.**'          # domain, no Docker
./mvnw test -Dtest='com.example.payment.application.**.*ServiceTest,AccountStateValidatorTest'
./mvnw test -Dtest=PaymentEndToEndTest                       # needs Docker
```

The first two finish in seconds with Docker stopped. That's the point of
keeping the business rules behind ports.

## Tests

| Test | What it proves |
|---|---|
| `AccountStateValidatorTest` | **New.** Two active accounts pass. An inactive source or destination is a `PaymentValidationException` naming which one. An account the account system doesn't know is an `AccountNotFoundException`. |
| `PaymentEndToEndTest` | **Renamed** from `PaymentControllerTest` and moved to the root package. Same 29 cases. |
| `ArchitectureTest` | **Moved** to `infrastructure/architecture`. Same six rules. |
| All others | Unchanged. 147 tests in all, passing. |
