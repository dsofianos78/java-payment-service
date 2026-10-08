# Episode 11 — Cancel Payment

Git tag: `episode-11-cancellation` · Previous: [Episode 10 — Idempotency](10-idempotency.md) · Next: Episode 12 — Authorization and Limits

## Goal

A payment that was created by mistake can now be **cancelled**, as long as no
money can have moved yet:

```text
CREATED ──────┐
   ↓          │
AUTHORIZED ───┼──>  CANCELLED
   ↓          ✗
PROCESSING
   ↓
COMPLETED | FAILED
```

```http
POST /payments/{paymentId}/cancel
```

## Who decides what

```text
Web adapter            POST /payments/{id}/cancel -> CancelPaymentCommand
    |
    v
CancelPaymentService   loads the payment, asks it to cancel, stores it
    |
    +--> Payment        decides whether cancelling is allowed (the domain)
```

There is no `if (status == COMPLETED)` in the controller or the service. They
ask for the transition, and `Payment.cancel()` allows it or throws.

## What changed

```text
src/main/java/com/example/payment/
├── domain/
│   ├── entity/Payment.java                           + cancel()
│   └── valueobject/PaymentStatus.java                + CANCELLED
├── application/
│   ├── port/primary/CancelPaymentUseCase.java        new
│   ├── usecase/command/CancelPaymentCommand.java     new
│   └── service/command/CancelPaymentService.java     new
└── infrastructure/adapter/primary/web/
    └── PaymentController.java                        + POST /payments/{id}/cancel
```

There's no new port or adapter, and the database doesn't change: cancelling
is a state change on a payment we already store.

## Why

### Transitions belong to the domain, not to controllers

The tempting version looks like this:

```java
// Don't: the rule now lives in the web adapter
if (payment.status() == COMPLETED || payment.status() == FAILED) {
	return ResponseEntity.status(409).build();
}
```

It works until a second entry point appears, such as a message consumer
(Episode 17) or a scheduled job. Each one needs its own copy of the rule, and
the copies drift apart. In `Payment` the rule exists once, and nothing can
get round it:

```java
public void cancel() {
	moveTo(PaymentStatus.CANCELLED, PaymentStatus.CREATED, PaymentStatus.AUTHORIZED);
}
```

`moveTo` now accepts more than one allowed starting state. Every other
transition still has exactly one.

### Why not once it's `PROCESSING`

From `PROCESSING` on, the payment system may already have moved the money
(Episode 09 stores `PROCESSING` before the call for exactly this reason).
Marking such a payment `CANCELLED` would claim something we don't know.
Taking money back after it has moved is a refund or a reversal, which is a
new payment, not a change of status.

`COMPLETED`, `FAILED` and `CANCELLED` are final. Nothing moves out of them, so
a cancelled payment can't be executed (`409`).

### Same error, same status

An invalid cancel throws the same `IllegalStateException` as any other
illegal transition. The service turns it into `InvalidPaymentStateException`,
which is `409` (Episode 09). There's no new exception and no new handler.

| Situation | HTTP |
|---|---|
| cancelled, payment `CANCELLED` | `200` |
| payment already `PROCESSING`, `COMPLETED`, `FAILED` or `CANCELLED` | `409` |
| no payment with that id | `404` |
| malformed id | `400` |

### No `Idempotency-Key`

As with execute, the lifecycle already makes repeating the request safe. A
second cancel is `409`, and nothing changes.

## Known shortcuts

- **A cancel and an execute at the same time can both pass.** Both load the
  payment as `CREATED`. The cancel saves `CANCELLED`, and the execute then
  overwrites it with `PROCESSING`. Episode 13 (transactions) closes this race,
  the same way it closes the race between two executes.
- **Anyone can cancel any payment.** Who may cancel is an authorization
  question, and that starts in Episode 12.

## Try it

```bash
./mvnw spring-boot:run
```

In [`http/payments.http`](../../http/payments.http), run the Episode 11
requests: create a payment, paste its `paymentId`, cancel it, try to execute
it (`409`), then try to cancel the completed payment from Episode 09 (`409`).

## Tests

| Test | What it proves |
|---|---|
| `domain/entity/PaymentTest` | **+3 cases.** `CREATED` and `AUTHORIZED` can be cancelled; `PROCESSING` and `COMPLETED` can't; a cancelled payment can't move again. |
| `application/service/command/CancelPaymentServiceTest` | **New.** Cancel stores `CANCELLED`; cancelling a completed payment is `InvalidPaymentStateException` and saves nothing; unknown and malformed ids. |
| `infrastructure/adapter/primary/web/PaymentControllerPortTest` | **+1 case.** The path id becomes a `CancelPaymentCommand`. |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | **+4 cases.** End to end against PostgreSQL: `CANCELLED` is stored; cancelling a completed payment is `409` and leaves it `COMPLETED`; a cancelled payment can't be executed; an unknown payment is `404`. |
| Everything else | Unchanged, except that every `PaymentController` built in a test now gets a cancel use case too. |
