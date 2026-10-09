# Bonus Episode 03 — Payment Events (Transactional Outbox)

Git tag: `bonus-03-payment-events` · Builds on: [Bonus Episode 02 — Reconciliation](bonus-02-reconciliation.md)

## Goal

Other services need to know when a payment is done: a ledger books it, a
notification service tells the customer. Until now they would have had to
poll `GET /payments/{id}`, with a customer's token they don't have.

The business rule: every payment that reaches a final status publishes
exactly one event, eventually:

- `PAYMENT_COMPLETED`
- `PAYMENT_FAILED`
- `PAYMENT_CANCELLED`

An event is never published for a status change that was rolled back, and
never lost for one that was committed.

## The dual write

The obvious version publishes to Kafka where the status is stored:

```java
transactions.executeWithoutResult(tx -> {
    paymentRepository.updateStatus(payment, from);
    auditPort.recordTransition(payment.id(), from, payment.status());
});
kafkaTemplate.send("payment-events", ...);   // after the commit
```

These are two writes to two systems, and nothing makes them atomic:

| Order | What goes wrong |
|---|---|
| commit, then send | The broker is down, or the instance dies between the two. The payment is `COMPLETED` and nobody hears about it. |
| send, then commit | The commit fails: the compare-and-set lost, or the database blinked. Everyone hears about a payment that isn't `COMPLETED`. |

Moving the send inside the transaction doesn't help: Kafka doesn't take
part in a PostgreSQL rollback.

Episode 17 didn't have this problem. `RequestPaymentExecutionService`
publishes `PaymentProcessingMessage` but writes no database row, so there is
only one write. If it fails, the caller gets a `503` and tries again. That
is why an outbox wasn't introduced then. Here the event describes a
database change, so the two must agree.

## The transactional outbox

Write the event to the **database**, in the same transaction as the status
change. Send it to Kafka later, from there.

```text
ExecutePaymentService.store(...)              one transaction
   ├── UPDATE payment SET status = 'COMPLETED' ...
   ├── INSERT INTO payment_audit ...
   └── INSERT INTO payment_outbox ...         PaymentEventPort.record(event)
                                              commit: all three, or none

PaymentEventRelay (every second)              after the transaction
   ├── SELECT ... FROM payment_outbox WHERE published_at IS NULL ORDER BY id
   ├── send to payment-events, wait for the ack
   └── UPDATE payment_outbox SET published_at = now()
```

The event commits or rolls back with the change it describes. That's the
same guarantee the audit row has had since Episode 13, and it's the same
technique.

## Domain: `domain/event`

```text
domain/event/PaymentEvent
domain/event/PaymentEventType
```

This is a new package. The original structure didn't have one, because
nothing outside the service needed the facts. Now something does.
"Payment 6f1c... completed, 250.00 EUR from ACC-10001 to ACC-20001" is a
business fact. It belongs to the domain, with no Kafka, JSON or Spring in it.
The existing `ArchitectureTest` rules already cover `domain..`.

```java
public static PaymentEvent finalStatusReached(Payment payment, Instant occurredAt) {
    PaymentEventType type = switch (payment.status()) {
        case COMPLETED -> PaymentEventType.PAYMENT_COMPLETED;
        case FAILED -> PaymentEventType.PAYMENT_FAILED;
        case CANCELLED -> PaymentEventType.PAYMENT_CANCELLED;
        case CREATED, AUTHORIZED, PROCESSING -> throw new IllegalStateException(...);
    };
    return new PaymentEvent(UUID.randomUUID(), type, payment.id(), ...);
}
```

The domain decides which statuses are news. `PaymentStatus.isFinal()` says
the same thing for the services. The time is passed in: the domain reads no
clock. `eventId` is new for every event. It is the consumers' deduplication
key (see Delivery guarantees).

## Application: `PaymentEventPort`

```java
/** Recorded in the caller's transaction, delivered later, at least once. */
public interface PaymentEventPort {
    void record(PaymentEvent event);
}
```

The name says *record*, not *publish*. The application doesn't send
anything, and the port's contract tells it so. Three services call it,
inside the transaction that already holds the status update and the audit
row:

| Service | Events |
|---|---|
| `ExecutePaymentService` | `PAYMENT_COMPLETED`, `PAYMENT_FAILED` (only from `store`, and only for a final status) |
| `CancelPaymentService` | `PAYMENT_CANCELLED` |
| `ReconcilePaymentsService` | `PAYMENT_COMPLETED`, `PAYMENT_FAILED` |

No event when the compare-and-set loses: that transaction changed nothing.
No event for `AUTHORIZED` or `PROCESSING`, or for an execution whose outcome
is unknown. Reconciliation will publish one when it learns the outcome.

## Infrastructure

```text
db/migration/V5__create_payment_outbox.sql
infrastructure/adapter/secondary/persistence/OutboxEntity, OutboxJpaRepository, OutboxPersistenceAdapter
infrastructure/adapter/secondary/messaging/PaymentEventMessage, PaymentEventRelay
```

### The outbox row holds the event, not the message

`payment_outbox` has typed columns: event ID, type, payment, accounts,
amount, currency, `occurred_at`. `published_at` stays null until the event
is sent. `id` is the publish order. A partial index covers only the
unpublished rows, which are the only ones the relay looks for.

It could have held the JSON of the Kafka message instead. Then the
persistence adapter would need `PaymentEventMessage` from the messaging
package, and the messaging relay would need the persistence adapter: two
adapters depending on each other. With columns, persistence stores a domain
event and reads it back, and the relay alone decides the wire format:

```text
PaymentEventRelay (messaging) ──> OutboxPersistenceAdapter (persistence) ──> PaymentEvent (domain)
          └── PaymentEventMessage.from(event)    the wire format, only here
```

### `OutboxPersistenceAdapter`

It implements `PaymentEventPort` with one `INSERT`, joining the caller's
transaction. It also has `findUnpublished`, `markPublished` and
`countUnpublished` for the relay.

### `PaymentEventRelay`: not a primary adapter

B2's `ReconciliationScheduler` is also `@Scheduled`, but it's a primary
adapter: it triggers a use case, and the application decides what happens.
The relay calls no use case and makes no business decision. It moves rows
from one technology to another. It is the second half of the
`PaymentEventPort` adapter, the half that runs after the transaction, so it
lives with the secondary messaging adapter.

```java
for (Pending pending : outbox.findUnpublished(batchSize)) {
    try {
        kafkaTemplate.send(TOPIC, paymentId, PaymentEventMessage.from(pending.event())).join();
    }
    catch (CompletionException | KafkaException e) {
        return;   // stop here: a later event of the same payment must not overtake this one
    }
    outbox.markPublished(pending);
}
```

It waits for the broker's ack before marking a row published, as Episode
17's publisher does. The timeouts are the same `spring.kafka.producer.*`
deadlines. When a send fails, the relay stops. The next run starts again
from that row.

```properties
payment.events.relay-interval=1s
payment.events.relay-batch-size=100
```

## Delivery guarantees

- **At least once.** The relay can die after the ack and before
  `published_at` is set. The next run sends the row again. Consumers
  deduplicate by `eventId`. There is no exactly-once claim, and Kafka
  transactions wouldn't give one across PostgreSQL and Kafka anyway.
- **Order per payment.** The message key is the payment ID, so all of one
  payment's events go to one partition. The relay sends in outbox `id`
  order and never skips a failed row. Today a payment has one final event;
  B4's refunds add more per payment, and then the order matters.
- **Several instances.** Every instance runs a relay, and two may send the
  same row. Event-ID deduplication keeps that correct. A lock
  (`SELECT ... FOR UPDATE SKIP LOCKED`, ShedLock) would save the duplicates,
  and would also keep two instances from interleaving one payment's events.
  It isn't added. See Known shortcuts.
- **Not CDC.** Debezium could read `payment_outbox` from the PostgreSQL WAL
  instead of polling it. It's the same pattern with a Kafka Connect cluster
  to run. It's the upgrade path if one-second polling isn't enough.

## Observability

```text
payment_events_pending      unpublished outbox rows: how far behind the relay is
```

The gauge reads the table on each Prometheus scrape. Normally it is 0, or
for a moment the last second's events. If it keeps climbing, the broker is
down or the relay is stuck.

Events are messages, not logs. They carry accounts and amounts, because a
ledger needs them. Logs and metrics still don't (Episode 15): the relay logs
only event IDs.

## What changed

```text
src/main/resources/db/migration/V5__create_payment_outbox.sql     new
src/main/resources/application.properties                         + payment.events.relay-interval, .relay-batch-size
src/main/java/com/example/payment/
├── domain/
│   ├── event/PaymentEvent.java, PaymentEventType.java            new
│   └── valueobject/PaymentStatus.java                            + isFinal()
├── application/
│   ├── port/secondary/PaymentEventPort.java                      new
│   └── service/command/
│       ├── ExecutePaymentService.java                            + event for a final status, in store's transaction
│       ├── CancelPaymentService.java                             + PAYMENT_CANCELLED, in its transaction
│       └── ReconcilePaymentsService.java                         + event, in each payment's transaction
└── infrastructure/adapter/secondary/
    ├── persistence/OutboxEntity, OutboxJpaRepository,
    │   OutboxPersistenceAdapter.java                             new
    └── messaging/PaymentEventMessage, PaymentEventRelay.java     new
```

Episode 17's `PaymentProcessingPublisher` and its topic are unchanged.
This service doesn't consume `payment-events`. Its consumers live elsewhere.

## Known shortcuts

- **No lock between relays.** Several instances may send the same row, and
  may interleave one payment's events. Consumers deduplicate by `eventId`.
  Add `SKIP LOCKED` or ShedLock when duplicates cost something, or when
  per-payment order across instances matters.
- **The outbox only grows.** Published rows are kept, which is handy for
  replaying, but nothing deletes them. Delete rows published more than N
  days ago (or partition the table by month) once the volume shows.
- **Polling.** An event waits up to a second plus the send. CDC would be
  near-instant, at the cost of more infrastructure.
- **A failed event write fails the request.** It's the same transaction, so
  an outbox write that fails rolls back `COMPLETED` too. The money has moved,
  and the payment stays `PROCESSING`, exactly as with a failed audit write
  (Episode 13). B2's reconciliation then completes it and records the event.
  `PaymentTransactionsTest` shows the rollback.
- **One stuck row blocks the relay.** A row the broker always refuses (a
  message too large, say) stops every event behind it. Nothing in these
  events can be too large, so that's acceptable here. Elsewhere, park such
  rows after N attempts.

## Try it

```bash
docker compose up -d && ./mvnw spring-boot:run
```

1. Create and execute a payment (the "Episode 09" requests in
   [http/payments.http](../../http/payments.http)). GET it until it is
   `COMPLETED`.
2. Watch the topic. The compose broker is `kafka-native`, which has no CLI
   tools, so borrow them from the full image:

   ```bash
   docker run --rm --network java-payment-service_default apache/kafka:4.1.1 \
     /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 \
     --topic payment-events --from-beginning --property print.key=true
   ```

   One line, keyed by the payment ID:
   `{"eventId":"...","type":"PAYMENT_COMPLETED","paymentId":"...","amount":250.00,"currency":"EUR",...}`
3. `docker compose stop kafka`, then create, execute and cancel a payment.
   The cancel commits. `payment_events_pending` on `/actuator/prometheus`
   reads 1, and the relay logs "will retry". `docker compose start kafka`:
   the event arrives and the gauge returns to 0.

## Tests

| Test | Change |
|---|---|
| `PaymentEventTest` | **new, 5.** Each final status has its event type; the event carries the payment and a fresh event ID; `CREATED`, `AUTHORIZED`, `PROCESSING` have none. |
| `ExecutePaymentServiceTest` | One `PAYMENT_COMPLETED` for a completed payment, `PAYMENT_FAILED` for a rejected one, none for an unknown outcome, none when another request changed it first. |
| `CancelPaymentServiceTest` | `PAYMENT_CANCELLED`; none when an execute got there first. |
| `ReconcilePaymentsServiceTest` | An event for each settled payment; none while there's no answer, none when another run won. |
| `PaymentTransactionsTest` | **+1.** An outbox write that fails rolls back `COMPLETED` and its audit row: no change without its event. The happy path leaves one `PAYMENT_COMPLETED` row. |
| `OutboxPersistenceAdapterTest` | **new, 2.** Real PostgreSQL: events come back in order and equal to what was recorded; a published one doesn't come back. |
| `PaymentEventRelayTest` | **new, 2.** Sends in order and marks each published after its ack; stops at the first failed send, so nothing overtakes it. Mocked template and outbox. |
| `PaymentEndToEndTest` | **+1.** A completed payment arrives on `payment-events` from the real Kafka exactly once, keyed by the payment, with the outbox row's event ID. |

187 tests, passing.
