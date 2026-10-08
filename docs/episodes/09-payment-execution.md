# Episode 09 — Execute Payment

Git tag: `episode-09-payment-execution` · Previous: [Episode 08 — Error Handling](08-error-handling.md) · Next: [Episode 10 — Idempotency](10-idempotency.md)

## Goal

So far a payment could be created and read, but it never moved any money: it
stayed `CREATED` forever. This episode lets a payment be **executed** and gives
it a lifecycle:

```text
CREATED
   ↓  authorize()
AUTHORIZED
   ↓  startProcessing()
PROCESSING
   ↓  complete()          ↓  fail()
COMPLETED               FAILED
```

```http
POST /payments/{paymentId}/execute
```

## Who decides what

```text
Web adapter              POST /payments/{id}/execute -> ExecutePaymentCommand
    |
    v
ExecutePaymentService    loads the payment, asks for each step in order,
    |                    stores it, calls the payment system
    |
    +--> Payment          decides whether a step is allowed (the domain)
    |
    +--> PaymentExecutionPort --> StubPaymentExecutionAdapter
                                  "did the money move?" EXECUTED | REJECTED
```

- **The domain owns the lifecycle.** `Payment.complete()` throws if the payment
  is not `PROCESSING`. No service, controller or adapter can skip a step or
  finish a payment twice, because the only way to change the status is through
  these methods.
- **The application owns the order.** `ExecutePaymentService` decides that
  execution means authorize, start processing, call the payment system, then
  complete or fail.
- **The payment system only reports the outcome.** `PaymentExecutionPort`
  answers `EXECUTED` or `REJECTED`. What that means for the payment is decided
  by the service and the domain, not the adapter.

## What changed

```text
src/main/java/com/example/payment/
├── domain/
│   ├── entity/Payment.java                             + authorize, startProcessing, complete, fail
│   └── valueobject/PaymentStatus.java                  + AUTHORIZED, PROCESSING, COMPLETED, FAILED
├── application/
│   ├── exception/InvalidPaymentStateException.java     new
│   ├── port/primary/ExecutePaymentUseCase.java         new
│   ├── port/secondary/PaymentExecutionPort.java        new
│   ├── usecase/command/ExecutePaymentCommand.java      new
│   └── service/command/ExecutePaymentService.java      new
└── infrastructure/adapter/
    ├── primary/web/
    │   ├── PaymentController.java                      + POST /payments/{id}/execute
    │   └── PaymentExceptionHandler.java                + InvalidPaymentStateException -> 409
    └── secondary/stub/                                 new package
        └── StubPaymentExecutionAdapter.java            new
```

The database did not change: `status` was already a `VARCHAR(20)`.

## Why

### State transitions are domain behaviour

The rule "a completed payment can't become authorized again" doesn't depend
on HTTP, Spring or PostgreSQL. It is true of payments. So it lives in
`Payment`, next to "the amount must be positive":

```java
public void complete() {
	moveTo(PaymentStatus.COMPLETED, PaymentStatus.PROCESSING);
}
```

`Payment` used to be fully immutable. Now `status` can change, but only
through these four methods. Everything else is still `final`.

Like the invariants, a broken transition throws a plain Java exception
(`IllegalStateException`). The service turns it into
`InvalidPaymentStateException`, which the web adapter reports as `409
Conflict`: the request is valid and the payment exists, but its state doesn't
allow this.

### `PROCESSING` is stored before calling out

```java
payment.authorize();
payment.startProcessing();
paymentRepository.save(payment);          // PROCESSING
paymentExecutionPort.execute(payment);    // the money moves here
payment.complete();                       // or fail()
paymentRepository.save(payment);          // COMPLETED / FAILED
```

If the service stops between the call and the second save, the payment
reads `PROCESSING`, not `CREATED`. Nobody can execute it again by accident,
and someone can find it and check what the payment system did. A payment
that might have moved money must never look as if it hasn't.

### `AUTHORIZED` without an authorization check

Every payment is authorized for now. The step is in the lifecycle already
because the lifecycle is a business fact. Episode 12 adds
`PaymentAuthorizationPort` in front of `authorize()`, and no transition has to
change.

### A stub, not Feign

The episode is about the lifecycle, not about another HTTP integration, so
`PaymentExecutionPort` gets a stub in `infrastructure/adapter/secondary/stub`.
The service can't tell the difference, so a real adapter can replace it later
without touching the application.

The stub executes every payment, except those whose reference starts with
`REJECT`, which it rejects. That's how you can see `FAILED` by hand.

### `FAILED` is still `200`

`POST /execute` returns `200` with the payment, whatever the outcome. The
request was carried out; the payment system said no. A rejected payment is a
normal business result, not an HTTP error, and the client reads `status`.

| Situation | HTTP |
|---|---|
| executed, payment `COMPLETED` or `FAILED` | `200` |
| payment already executed (any state but `CREATED`) | `409` |
| no payment with that id | `404` |
| malformed id | `400` |

## Known shortcuts

- **Two concurrent executes of the same payment can both pass.** Both load it
  as `CREATED` before either saves. Episode 13 (transactions) closes this.
- **If the payment system throws, the payment stays `PROCESSING`.** That's
  the safe state, but nothing picks it up again yet. Episode 16 (resilience).
- **Create and execute are two calls.** Episode 17 runs execution
  asynchronously.

## Try it

```bash
./mvnw spring-boot:run
```

In [`http/payments.http`](../../http/payments.http), run the Episode 09
requests: create a payment, paste its `paymentId`, execute it, execute it
again for the `409`.

## Tests

| Test | What it proves |
|---|---|
| `domain/entity/PaymentTest` | **+4 cases.** The full lifecycle to `COMPLETED` and `FAILED`; no step can be skipped; a finished payment can't move again. |
| `application/service/command/ExecutePaymentServiceTest` | **New.** Executed → `COMPLETED`, rejected → `FAILED`; `PROCESSING` is saved before the payment system is called; a second execute is `InvalidPaymentStateException` and doesn't reach the payment system; unknown and malformed ids. |
| `infrastructure/adapter/primary/web/PaymentControllerPortTest` | **+2 cases.** The path id becomes an `ExecutePaymentCommand`; `InvalidPaymentStateException` is `409`. |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | **+4 cases.** End to end against PostgreSQL: `COMPLETED` and `FAILED` are stored, executing twice is `409`, an unknown payment is `404`. |
| Everything else | Unchanged. |
