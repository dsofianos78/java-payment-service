# Episode 17 — Asynchronous Processing

Git tag: `episode-17-async-processing` · Previous: [Episode 16 — Resilience](16-resilience.md) · Next: Episode 18 — Testing Strategy

## Goal

Since Episode 09, `POST /payments/{id}/execute` has done all the work while
the caller waits: authorization, the limit check and the payment system. In
the worst case from Episode 16, that is several seconds of retries plus a
5-second payment call, all holding one HTTP request open.

This episode splits execution in two:

```text
POST /execute                            Kafka                        consumer
-------------                            -----                        --------
is it CREATED or AUTHORIZED?
publish PaymentProcessingMessage  --->  payment-processing  --->  ExecutePaymentService
202 Accepted                                                       authorize, limit check,
                                                                   payment system,
                                                                   COMPLETED | FAILED
GET /payments/{id}  <-- the caller follows the status from here -->
```

This brings in the last package from the target structure,
`infrastructure/adapter/primary/messaging`. A message is now a second way
to start a use case, next to HTTP.

## Two adapters, one use case

The consumer is a **primary adapter**, like the controller. Both turn
something from outside into an `ExecutePaymentCommand`:

```java
@KafkaListener(topics = PaymentProcessingMessage.TOPIC)
void onMessage(PaymentProcessingMessage message) {
    executePaymentUseCase.executePayment(new ExecutePaymentCommand(message.paymentId()));
}
```

`ExecutePaymentService` hasn't changed at all. It doesn't know whether a
request or a message called it. This is why Episode 02 put a port between
the controller and the service.

The request side gets its own small use case, `RequestPaymentExecutionUseCase`,
and an outgoing port to hand the payment over:

```java
public interface PaymentProcessingPort {
    void requestProcessing(PaymentId paymentId);
}
```

`PaymentProcessingPublisher` implements it with a `KafkaTemplate`. It lives
in `infrastructure/adapter/secondary/messaging`, which mirrors the primary
`messaging` package the same way `feign` and `persistence` sit next to `web`.
The application only knows "hand this payment over". It doesn't know about
topics, partitions or Kafka.

| Package | Class | Role |
|---|---|---|
| `application/port/primary` | `RequestPaymentExecutionUseCase` | new: what `POST /execute` calls |
| `application/service/command` | `RequestPaymentExecutionService` | new: check the status, queue the payment |
| `application/port/secondary` | `PaymentProcessingPort` | new: "process this payment later" |
| `infrastructure/adapter/secondary/messaging` | `PaymentProcessingPublisher` | new: publishes to Kafka |
| `infrastructure/adapter/primary/messaging` | `PaymentProcessingMessage` | new: the message, only the payment ID |
| `infrastructure/adapter/primary/messaging` | `PaymentProcessingConsumer` | new: message → `ExecutePaymentUseCase` |

### The message carries an ID, not a payment

```json
{ "paymentId": "6f1c..." }
```

The consumer reads the payment from the database. A message that waited in
the topic for a minute can't carry a stale status, and no account number or
amount is ever stored in the broker. The message key is the payment ID too.
The next section explains why.

### No write before the publish

`RequestPaymentExecutionService` reads the payment, checks its status and
publishes. It **writes nothing**. That avoids the classic dual-write
problem: if it stored `PROCESSING` and then the publish failed, the payment
would be stuck in a state no message will ever move on. As it is, a failed
publish is a `503` and the payment is exactly as it was.

The status check is not the guard. It gives the caller a quick `409` for a
payment that is already `COMPLETED` or `CANCELLED`. The real decision is
still made by the domain when the message is consumed, because the status
can change in between.

### 202, not 200

```http
POST /payments/6f1c.../execute

202 Accepted
{ "paymentId": "6f1c...", ..., "status": "CREATED" }
```

The body shows the payment as it is *now*. A moment later
`GET /payments/6f1c...` shows `COMPLETED`. Episode 16 listed "`200` with
`PROCESSING`, not `202`" as a shortcut. `202` is now literally true.

## Duplicate messages and consumer idempotency

Kafka delivers **at least once**. The same message can arrive twice, for
example when the consumer crashes after processing but before committing
its offset. Two clients that call `execute` at the same moment both pass
the status check and both publish. Duplicates are normal, not an edge case.

Many systems keep a table of message IDs they have already processed. This
one doesn't need it. Each payment already records its own state, and
Episode 13 made every status change conditional:

```text
1st message:  CREATED -> AUTHORIZED -> PROCESSING -> COMPLETED   (payment system called once)
2nd message:  payment is COMPLETED; startProcessing() refuses     (InvalidPaymentStateException)
```

The consumer treats that refusal as an answer, not an error. It logs a line
and acknowledges the message:

```java
catch (InvalidPaymentStateException | PaymentNotFoundException | PaymentValidationException
        | PaymentAuthorizationException | PaymentLimitExceededException e) {
    log.warn("Payment {} not processed: {}", message.paymentId(), e.getMessage());
}
```

The same goes for a declined authorization or an exceeded limit:
redelivering the message would get the same answer.

What if the duplicate arrives while the first one is still processing? Both
messages have the same key, so they are on the same partition, and one
consumer handles a partition's messages one at a time. Even if they did run
together (after a partition rebalance, say), only one conditional update from
`AUTHORIZED` to `PROCESSING` can succeed. That is exactly what
`concurrentExecutesSendThePaymentOnce` in `PaymentTransactionsTest` has proved
since Episode 13.

`aDuplicateMessageDoesNotSendThePaymentTwice` publishes the same message
twice and checks that the payment system saw one request and the audit
trail has three rows.

## Eventual consistency

Between the `202` and the consumer, `GET` shows `CREATED`. That is
not a bug. The two sides of the system are briefly out of step and then
catch up. What the caller has to change:

- **Follow the status.** Poll `GET /payments/{id}` until it is `COMPLETED`,
  `FAILED` or (after a lost answer, Episode 16) stays `PROCESSING`.
- **Refusals no longer come back as errors.** In Episode 12 a declined
  payment was a `422` on `execute`. Now the decline happens after the
  response. The payment stays `CREATED` (or `AUTHORIZED` for a limit), and
  the reason is in the log line above. See Known shortcuts.

The client-facing guarantees don't change: a payment is executed at most
once, and `COMPLETED` means the money moved.

## Ordering

Kafka guarantees order **within a partition**, not across a topic. The key
is the payment ID, so all messages for one payment are on one partition, in
the order they were published. Messages for different payments can run in
any order relative to each other, and that is fine because they are independent.

The retry topics below give up even that per-payment order: a message being
retried can be overtaken by a newer one for the same payment. That is safe
here for the same reason duplicates are. Whichever message runs second finds
the payment already moved on and is refused.

## Failure recovery

| What fails | What happens |
|---|---|
| Broker down when `execute` is called | The publish gives up after 2s (`max.block.ms`). `503 Message broker is unavailable`. Nothing changed; call `execute` again later. |
| Authorization or limit system down while consuming | `ExternalSystemUnavailableException` is thrown. The message is retried after 5s, 10s and 20s through `payment-processing-retry-0/1/2`, then parked in `payment-processing-dlt`. The payment stays `CREATED`/`AUTHORIZED`; `execute` it again when the system is back. |
| Payment system doesn't answer | Unchanged from Episode 16: the payment stays `PROCESSING` and the message is acknowledged. It is not retried: the payment may already have been paid. |
| Our service dies while processing | The offset wasn't committed, so the message is delivered again on restart. The payment's status says where it got to; the domain refuses to repeat a step. |
| A message that isn't valid JSON | `ErrorHandlingDeserializer` turns it into a failure that goes straight to the DLT, rather than failing on every poll forever. |

All of this is configuration, in [application.properties](../../src/main/resources/application.properties):

```properties
spring.kafka.retry.topic.enabled=true
spring.kafka.retry.topic.attempts=4
spring.kafka.retry.topic.backoff.delay=5s
spring.kafka.retry.topic.backoff.multiplier=2
```

### Why retry topics and not retry in place?

The default would retry the failing message in place, blocking every message
behind it on the partition. During an outage of the authorization system,
that is all of them. With retry topics, a failing message moves aside, and
the main topic keeps flowing for payments that might still succeed. The cost
is per-payment order, which, as above, doesn't matter here.

### A deadline for the broker

Episode 16 gave every external call a deadline. The broker is an external
system too. By default a producer waits up to a minute for metadata and two
minutes for delivery:

```properties
spring.kafka.producer.properties.max.block.ms=2000
spring.kafka.producer.properties.request.timeout.ms=3000
spring.kafka.producer.properties.delivery.timeout.ms=5000
```

The publisher waits for the broker's acknowledgement (`.join()`), so a
`202` means the message is stored, not just buffered in our memory.

## Correlation IDs across the broker

Episode 15's correlation ID followed a request into every external call.
The consumer runs on a Kafka thread, so the request's MDC doesn't reach it.
Two small interceptors in `infrastructure/observability` carry the ID
across:

- `CorrelationIdProducerInterceptor` puts the current `X-Correlation-Id` on
  every published record as a header. It is the message version of
  `CorrelationIdFeignInterceptor`.
- `CorrelationIdRecordInterceptor` reads it back into the MDC before the
  listener runs, and clears it afterwards. It is the message version of
  `CorrelationIdFilter`, and it applies the same validation to the header.

```text
main                     corr-episode-17  Payment 992d... queued for processing
...ListenerContainer#0   corr-episode-17  Payment 992d... moved from CREATED to AUTHORIZED
...ListenerContainer#0   corr-episode-17  Payment 992d... moved from AUTHORIZED to PROCESSING
...ListenerContainer#0   corr-episode-17  Payment 992d... moved from PROCESSING to COMPLETED
```

The calls the consumer makes to the authorization, limit and payment
systems carry the same ID (`correlationIdFollowsThePaymentThroughKafka`).

## What we did not do

The spec says "Do not turn the project into a distributed microservice
ecosystem merely for demonstration." So there is one service, which
publishes and consumes its own topic. There is no separate "payment
processor" service and no event-sourcing framework. There is also no
`PaymentCompleted` event, because nothing listens for it yet. A topic with
no consumer is a contract nobody has asked for. When someone needs to hear
about completed payments, a `PaymentEventPort` next to `AuditPort` is where
it would go.

## What changed

```text
pom.xml                                                        + spring-boot-starter-kafka; test: testcontainers-kafka, awaitility
compose.yaml                                                   + kafka (apache/kafka-native, localhost:9092)
src/main/resources/application.properties                      + group, JSON (de)serializers, producer deadlines, retry topics
http/payments.http                                             + Episode 17 requests; earlier execute requests note the 202
src/main/java/com/example/payment/
├── application/
│   ├── port/primary/RequestPaymentExecutionUseCase.java       new
│   ├── port/secondary/PaymentProcessingPort.java              new
│   └── service/command/RequestPaymentExecutionService.java    new
└── infrastructure/
    ├── adapter/primary/
    │   ├── messaging/PaymentProcessingMessage.java            new
    │   ├── messaging/PaymentProcessingConsumer.java           new
    │   └── web/PaymentController.java                         execute -> RequestPaymentExecutionUseCase, 202
    ├── adapter/secondary/messaging/PaymentProcessingPublisher.java   new
    └── observability/
        ├── CorrelationIdProducerInterceptor.java              new
        ├── CorrelationIdRecordInterceptor.java                new
        └── CorrelationIdFilter.java                           VALID shared with the record interceptor
```

The domain is unchanged. `ExecutePaymentService` is unchanged apart from
its port's Javadoc. `ArchitectureTest` passes without a new rule:
`application` still depends on nothing in `infrastructure`, and Kafka
appears only in adapters, observability and configuration.

### No Docker Compose service connection for Kafka

Spring Boot 4.1 connects to PostgreSQL in `compose.yaml` automatically,
but has no such support for Kafka. The broker therefore uses a fixed port,
`localhost:9092`, which is also Spring's default `bootstrap-servers`. Tests
use Testcontainers, where `@ServiceConnection` does work for Kafka.

## Known shortcuts

- **Refusals are only in the log.** A declined authorization or an exceeded
  limit leaves the payment `CREATED`/`AUTHORIZED` with no visible reason for
  the caller. A real API would record the refusal, e.g. a `REJECTED` status
  with a reason, or a notification. That is a domain change, not a
  messaging one.
- **Nothing reads the DLT.** A parked message is logged at `ERROR` and stays
  in `payment-processing-dlt`. Recovery is to call `execute` again, which is
  safe for the reasons above. A real system would alert on the DLT and
  replay it.
- **Still no reconciliation job.** Payments left `PROCESSING` by a lost
  answer (Episode 16) still need someone to ask the payment system. A
  scheduled job that finds them and asks is the natural next step. The
  consumer deliberately does **not** retry them.
- **One partition, one consumer.** The topic is created with Spring's
  defaults. More partitions and instances scale it out; the key keeps each
  payment on one of them.
- **The caller polls.** No webhook or server-sent event tells them the
  payment finished.

## Try it

```bash
./mvnw spring-boot:run
```

Send the Episode 17 requests in [http/payments.http](../../http/payments.http):

1. Create a payment and `execute` it: `202` with `"status": "CREATED"`. A
   moment later `GET` shows `COMPLETED`, and the three status-change log
   lines show the `correlationId` you sent, on a `KafkaListenerEndpointContainer` thread.
2. `execute` it again: `409`, and nothing is published.
3. `docker compose stop kafka`, then `execute` a new payment: `503 Message broker is unavailable`
   after 2 seconds. `docker compose start kafka`.
4. Make the authorization system fail:
   ```bash
   curl -X POST localhost:8089/__admin/mappings -d '{"priority":0,"request":{"method":"POST","url":"/authorizations"},"response":{"status":503}}'
   ```
   `execute` a new payment: `202`. Over the next ~35 seconds the log shows
   the attempts on `retry-0`, `retry-1` and `retry-2`, then
   `Sending to DLT with name payment-processing-dlt`. The payment is still `CREATED`.
   Put WireMock back with `curl -X POST localhost:8089/__admin/mappings/reset`,
   `execute` it again, and it completes.

## Tests

| Test | What it proves |
|---|---|
| `RequestPaymentExecutionServiceTest` | **New.** `CREATED` and `AUTHORIZED` payments are queued and returned unchanged. `COMPLETED` or `CANCELLED` is a `409` and nothing is queued. Unknown is `404`, malformed is `400`, broker down reaches the caller. Nothing is ever written. |
| `PaymentControllerTest` | **Changed.** Runs against a real Kafka (Testcontainers). `execute` is `202`; each test then waits for the status the consumer stores. **2 new:** a duplicate message reaches the payment system once, with three audit rows; the request's correlation ID reaches the payment system through Kafka. The authorization and limit cases now check the payment stays `CREATED`/`AUTHORIZED`, since no `422` comes back. |
| `PaymentControllerPortTest` | **Changed.** `execute` calls `RequestPaymentExecutionUseCase` and answers `202`. |
| `PaymentTransactionsTest` | Unchanged. It calls `ExecutePaymentUseCase` directly, which is what the consumer calls. |
