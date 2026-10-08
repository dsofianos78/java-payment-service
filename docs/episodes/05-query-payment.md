# Episode 05 — Query a Payment

Git tag: `episode-05-payment-query` · Previous: [Episode 04 — Persist Payments](04-persist-payments.md) · Next: [Episode 06 — Payment Validation](06-payment-validation.md)

## Goal

A client that created a payment can read it back:

```http
GET /payments/{paymentId}
```

- `200` with the payment when it exists
- `404` when no payment has that id
- `400` when the id isn't a valid payment id

This is the first **query**: it asks the application for information and
changes nothing. Everything so far has been a **command**. The two don't
have to use the same application model, and this episode shows why.

```text
Web Adapter          PaymentController            (infrastructure)
     |
     |  GetPaymentQuery in, GetPaymentResult out
     v
Primary Port         GetPaymentUseCase            (application)   new
     ^
     |  implements
Application Service  GetPaymentService            (application)   new
     |
     |  uses
     v
Secondary Port       PaymentRepository            (application)   + findById
     ^
     |  implements
Secondary Adapter    PaymentPersistenceAdapter    (infrastructure) + findById
     |
     v
                     PaymentJpaRepository -> PostgreSQL
```

## What changed

```text
src/main/java/com/example/payment/
├── domain/
│   ├── entity/Payment.java                      + restore(): rebuild an existing payment
│   └── valueobject/PaymentId.java               + of(String): parse and reject bad ids
├── application/
│   ├── port/primary/
│   │   └── GetPaymentUseCase.java               new: the first query port
│   ├── port/secondary/
│   │   └── PaymentRepository.java               + findById
│   ├── usecase/query/
│   │   ├── GetPaymentQuery.java                 new: what the caller asks
│   │   └── GetPaymentResult.java                new: what the caller gets back
│   └── service/query/
│       └── GetPaymentService.java               new: implements GetPaymentUseCase
└── infrastructure/adapter/
    ├── primary/web/
    │   ├── PaymentController.java               + GET /payments/{paymentId}
    │   └── PaymentResponse.java                 + from(GetPaymentResult)
    └── secondary/persistence/
        ├── PaymentEntityMapper.java             + toDomain
        └── PaymentPersistenceAdapter.java       + findById

src/test/java/com/example/payment/
├── application/service/
│   ├── command/CreatePaymentServiceTest.java    fake store now fails on reads
│   └── query/GetPaymentServiceTest.java         new
└── infrastructure/adapter/
    ├── primary/web/PaymentControllerPortTest.java   + GET through the port
    ├── primary/web/PaymentControllerTest.java       + read back, 404, 400
    └── secondary/persistence/
        └── PaymentPersistenceAdapterTest.java       + round trip, unknown id
```

`CreatePaymentUseCase`, `CreatePaymentService`, `PaymentEntity`,
`PaymentJpaRepository` and the migration did not change.

## Why

### A query doesn't need the command's model

Compare the two primary ports:

```java
public interface CreatePaymentUseCase {

	Payment createPayment(CreatePaymentCommand command);
}

public interface GetPaymentUseCase {

	Optional<GetPaymentResult> getPayment(GetPaymentQuery query);
}
```

Both use a plain-value object on the way in, `CreatePaymentCommand` and
`GetPaymentQuery`, so adapters can call them without building domain types.
On the way out, they differ.

The command works on the `Payment` aggregate, because changing state is
what the aggregate is for: it enforces the invariants. The query only needs
to report what is there. `GetPaymentResult` is a flat record of strings and
a `BigDecimal`:

```java
public record GetPaymentResult(
		String paymentId,
		String sourceAccountId,
		String destinationAccountId,
		BigDecimal amount,
		String currency,
		String reference,
		String status) {
```

That separation pays off in three ways:

- **Readers can't change anything.** Later episodes give `Payment`
  behaviour such as execute and cancel. A result has none, so a read
  can't accidentally become a write.
- **Each side changes for its own reasons.** A reader might want the
  account names, or a timestamp formatted for display. Those go in the
  result without adding fields to the aggregate that only exist for
  reading.
- **Reads can stop using the aggregate later.** Today the service loads a
  `Payment` and maps it. If reads ever outgrow that, the query side can
  read from a view or projection behind the same port. Neither callers nor
  the command side would notice.

Today the result happens to have the same fields as `Payment`. That's
fine. The point is that the two can now diverge, not that they already
have. This is command/query separation at the level of models, not a CQRS
framework: one database, one repository, two models in the application.

The packages already reflect the split: `usecase/command` and
`service/command` on one side, `usecase/query` and `service/query` on the
other.

### The query carries a `String`, and the service parses it

```java
public record GetPaymentQuery(String paymentId) {
}
```

```java
return paymentRepository.findById(PaymentId.of(query.paymentId())).map(GetPaymentResult::from);
```

The controller passes the path variable through as text. `PaymentId.of`
decides what a valid id is, in the domain, just as `Currency.of` does for
currencies. A malformed id raises the same `IllegalArgumentException` as
any other invalid input, so it comes back as a `400` with
`Invalid payment id: ...`, through the handler that already exists. The
web adapter makes no decision of its own about ids.

### `Optional`, not an exception, for now

"No such payment" is a normal answer, so the port says so in its return
type. The controller turns it into HTTP:

```java
return ResponseEntity.of(getPaymentUseCase.getPayment(new GetPaymentQuery(paymentId)).map(PaymentResponse::from));
```

`ResponseEntity.of` gives `200` with the body, or `404` with none.
Episode 08 introduces `PaymentNotFoundException` and maps it to a `404`
with a problem-details body.

### The port grew because the application needed it

Episode 04 gave `PaymentRepository` one method. Now the application reads
payments, so the port has two:

```java
void save(Payment payment);

Optional<Payment> findById(PaymentId paymentId);
```

The repository still speaks in the domain's terms, `Payment` and
`PaymentId`. Turning a `Payment` into a `GetPaymentResult` is the query
service's job, not the database adapter's.

### Restoring a payment is not creating one

```java
public static Payment create(...)   // new payment: new id, status CREATED
public static Payment restore(...)  // existing payment: keeps its id and status
```

`create` is for payments that don't exist yet. A payment read from the
database already has an id and a status, and `create` would replace both.
`restore` takes them as they are.

Both go through the same private constructor, so a restored payment is
checked exactly like a new one. A row with `amount = 0`, or the same
account on both sides, fails in the mapper instead of becoming a `Payment`
that breaks a rule. `PaymentEntityMapper.toDomain` builds every value
object through its own constructor for the same reason.

### Each service's fake fails on the other operation

`PaymentRepository` has two methods now, so the list-backed lambda from
Episode 04 no longer compiles. Each service test uses an anonymous class
whose unused method throws:

```java
// CreatePaymentServiceTest
public Optional<Payment> findById(PaymentId paymentId) {
	throw new UnsupportedOperationException("CreatePaymentService should not read payments");
}

// GetPaymentServiceTest
public void save(Payment payment) {
	throw new UnsupportedOperationException("GetPaymentService should not save payments");
}
```

A command that starts reading, or a query that starts writing, fails its
test.

## Known shortcuts

- **The `404` has no body.** Episode 08 introduces `PaymentNotFoundException`
  and problem-details responses.
- **Invalid ids are a plain `IllegalArgumentException`**, like every other
  rule violation so far. Episode 08 replaces these with application
  exceptions.
- **`CreatePaymentUseCase` still returns the domain `Payment`.** It was
  written before the query side existed, and this episode changes only
  what the spec calls for.
- **Anyone can read any payment.** Episode 12 adds authorization.

## Try it

```bash
./mvnw spring-boot:run
```

In [`http/payments.http`](../../http/payments.http), run the Episode 05
create request, paste its `paymentId` into `@paymentId`, and run the read.
Restart the application and read it again: it's still there, now through
the API instead of `psql`.

## Tests

| Test | What it proves |
|---|---|
| `application/service/query/GetPaymentServiceTest` | **New.** A stored payment comes back as a `GetPaymentResult`. An unknown id is empty. A malformed id is rejected. The query never saves. No Spring, no database. |
| `infrastructure/adapter/secondary/persistence/PaymentPersistenceAdapterTest` | **+2 cases.** A saved payment reads back equal, field by field, from real PostgreSQL. An unknown id is empty. |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | **+3 cases.** `POST` then `GET` returns the same payment. An unknown id is `404`. A malformed id is `400` with `Invalid payment id`. |
| `infrastructure/adapter/primary/web/PaymentControllerPortTest` | **+1 case.** `GET` sends the path id as a `GetPaymentQuery` and maps the result, or `404`, with no Spring context. |
| `application/service/command/CreatePaymentServiceTest` | **Changed fake.** Creating a payment never reads from the repository. |
| `domain/...` | Unchanged. `restore` and `PaymentId.of` are covered through the tests above. |
