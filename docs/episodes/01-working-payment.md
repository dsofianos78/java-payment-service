# Episode 01 — Create a Working Payment

Git tag: `episode-01-working-payment` · Next: [Episode 02 — Establish the Primary Port Boundary](02-primary-port-boundary.md)

## Goal

A customer can create a payment.

```http
POST /payments
Content-Type: application/json

{
  "sourceAccountId": "ACC-10001",
  "destinationAccountId": "ACC-20001",
  "amount": 250.00,
  "currency": "EUR",
  "reference": "Invoice 12345"
}
```

The service answers `201 Created` with the new payment in status `CREATED`. If the
request breaks a business rule, it answers `400 Bad Request` with an
`application/problem+json` body explaining why.

This episode is intentionally small. It has no database, no external systems,
no idempotency and no authorization. Payments are not stored yet: each one exists
only for the duration of the request.

## What changed

```text
com/example/payment/
├── PaymentApplication.java
├── domain/
│   ├── entity/
│   │   └── Payment.java
│   └── valueobject/
│       ├── AccountId.java
│       ├── Currency.java
│       ├── Money.java
│       ├── PaymentId.java
│       ├── PaymentReference.java
│       └── PaymentStatus.java
├── application/
│   ├── port/primary/
│   │   └── CreatePaymentUseCase.java
│   ├── usecase/command/
│   │   └── CreatePaymentCommand.java
│   └── service/command/
│       └── CreatePaymentService.java
└── infrastructure/adapter/primary/web/
    ├── PaymentController.java
    ├── PaymentRequest.java
    └── PaymentResponse.java
```

Only six of the target packages are used so far. The rest of the structure
(secondary ports, persistence, Feign, messaging and so on) appears when a
requirement needs it, not before.

## Why

### Start with the domain

The most important rules of a payment do not depend on HTTP, databases or Spring:

- the amount must be positive
- the currency must be one the bank supports
- money cannot be sent from an account to itself
- the reference must be present and at most 140 characters

These rules live in `domain/`, which is plain Java with no framework imports.

### Value objects carry meaning and validate themselves

Passing `String`, `String`, `BigDecimal`, `String`, `String` around invites mistakes
such as swapping the source and destination accounts. Each concept gets its own type,
and that type rejects invalid values when it is constructed:

```java
public record Money(BigDecimal amount, Currency currency) {

	public Money {
		Objects.requireNonNull(amount, "Amount is required");
		Objects.requireNonNull(currency, "Currency is required");
		if (amount.stripTrailingZeros().scale() > currency.minorUnits()) {
			throw new IllegalArgumentException(
					"Amount " + amount.toPlainString() + " has more decimals than " + currency + " allows");
		}
		amount = amount.setScale(currency.minorUnits());
	}
}
```

Some points to notice:

- `Money` is a `BigDecimal` plus a currency, never a `double`, because binary
  floating point cannot represent `0.10` exactly.
- `250` and `250.00` are normalized to the same scale, so they compare as equal.
- `10.001 EUR` is rejected. Nobody can pay a tenth of a cent.

`Currency` is a closed enum (`EUR`, `GBP`, `USD`) rather than `java.util.Currency`.
A valid ISO currency code is not the same thing as a currency this bank supports,
and the business decides the second.

### The entity guards its invariants

Some rules involve more than one value. Whether the source and destination
accounts are the same is a question about the payment as a whole, so `Payment`
checks it:

```java
public static Payment create(AccountId sourceAccountId, AccountId destinationAccountId,
		Money amount, PaymentReference reference) {
	return new Payment(PaymentId.newId(), sourceAccountId, destinationAccountId,
			amount, reference, PaymentStatus.CREATED);
}

private Payment(...) {
	...
	if (!amount.isPositive()) {
		throw new IllegalArgumentException("Payment amount must be positive");
	}
	if (sourceAccountId.equals(destinationAccountId)) {
		throw new IllegalArgumentException("Source and destination accounts must differ");
	}
}
```

The constructor is private and `create` is the only way in, so **an invalid
`Payment` cannot exist**. It does not matter whether the caller is a REST
controller, a message consumer in Episode 17, or a test. The rule is enforced in
one place.

Positivity is checked in `Payment`, not in `Money`. A balance or a refund can be
zero or negative; only a payment amount must be positive.

### The application layer coordinates

`CreatePaymentCommand` expresses the intent in plain values. It is not an HTTP
request and not a domain object. Any primary adapter can build one: the web
controller today, a message consumer later.

`CreatePaymentService` implements `CreatePaymentUseCase` and turns the command into
domain objects. At this stage it has nothing else to coordinate. That changes in
Episode 03, when it must check that both accounts exist before creating a payment.

### The web adapter translates and nothing more

`PaymentController` converts JSON into a command and a `Payment` into JSON.
`PaymentRequest` checks only that the request has the right shape (all fields
present) using Bean Validation:

```java
public record PaymentRequest(
		@NotBlank String sourceAccountId,
		@NotBlank String destinationAccountId,
		@NotNull BigDecimal amount,
		@NotBlank String currency,
		@NotBlank String reference) { ... }
```

It does not check whether the amount is positive or the currency is supported.
Those are business rules, and copying them into the controller would mean the
same rules are written twice and can drift apart. Episode 06 covers the different
kinds of validation in detail.

`PaymentResponse` is separate from `Payment`, so the domain can change without
breaking the API contract, and the API can change without touching the domain.

### Dependency direction

```text
PaymentController  ──>  CreatePaymentService  ──>  Payment, Money, ...
 (infrastructure)          (application)              (domain)
```

Every arrow points inward. The domain knows nothing about the application, and
the application knows nothing about HTTP.

## Known shortcuts

These are deliberate and are fixed in later episodes:

- **The controller depends on the concrete `CreatePaymentService`**, not on the
  `CreatePaymentUseCase` port. Episode 02 inverts this and
  explains why it matters.
- **Error handling is a single `@ExceptionHandler` in the controller** that maps
  `IllegalArgumentException` to `400`. Episode 08 replaces it with application
  exceptions and a dedicated handler.
- **Nothing is stored.** Episode 04 introduces `PaymentRepository` and PostgreSQL.

## Try it

Run the tests:

```bash
./mvnw test
```

Start the application and create a payment:

```bash
./mvnw spring-boot:run

curl -i -X POST localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -d '{"sourceAccountId":"ACC-10001","destinationAccountId":"ACC-20001","amount":250.00,"currency":"EUR","reference":"Invoice 12345"}'
```

```json
{
  "paymentId": "5073f727-fe90-4a62-a347-47fbaad4a798",
  "sourceAccountId": "ACC-10001",
  "destinationAccountId": "ACC-20001",
  "amount": 250.00,
  "currency": "EUR",
  "reference": "Invoice 12345",
  "status": "CREATED"
}
```

Send the same account as source and destination:

```json
{
  "status": 400,
  "title": "Bad Request",
  "detail": "Source and destination accounts must differ",
  "instance": "/payments"
}
```

More requests, including every error case, are in
[`http/payments.http`](../../http/payments.http). You can run it with the
IntelliJ HTTP Client or the VS Code REST Client extension.

If port 8080 is already in use, start the application with
`./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8099` and change
`@baseUrl` in `payments.http` to match.

## Tests

| Test | What it proves |
|---|---|
| `domain/entity/PaymentTest` | A valid payment is created in `CREATED`; non-positive amounts and identical accounts are rejected. |
| `domain/valueobject/CurrencyTest` | Supported currencies are accepted (case-insensitive); others are rejected. |
| `domain/valueobject/MoneyTest` | Amounts are normalized to two decimals; fractions of a cent are rejected. |
| `domain/valueobject/PaymentReferenceTest` | The reference is required and limited to 140 characters. |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | The HTTP contract: `201` on success, `400` for business rule violations and missing fields. |

The domain tests run in milliseconds with no Spring context, which is a direct
benefit of keeping the domain free of frameworks.
