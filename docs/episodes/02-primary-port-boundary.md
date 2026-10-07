# Episode 02 — Establish the Primary Port Boundary

Git tag: `episode-02-primary-port-boundary` · Previous: [Episode 01 — Create a Working Payment](01-working-payment.md) · Next: [Episode 03 — Account Enquiry](03-account-enquiry.md)

## Goal

Make the architectural boundary explicit. The web controller depends on the
primary port `CreatePaymentUseCase`, not on the class that implements it.

```text
Web Adapter          PaymentController        (infrastructure)
     |
     v
Primary Port         CreatePaymentUseCase     (application)
     ^
     |  implements
Application Service  CreatePaymentService     (application)
     |
     v
Domain               Payment, Money, ...      (domain)
```

The HTTP behaviour does not change. `POST /payments` works exactly as in
Episode 01.

## What changed

```text
src/main/java/.../infrastructure/adapter/primary/web/
└── PaymentController.java          depends on CreatePaymentUseCase

src/test/java/.../infrastructure/adapter/primary/web/
└── PaymentControllerPortTest.java  new: controller tested against a stub port
```

That is the whole diff: one import, one field type and one constructor
parameter, plus a test. The episode is mostly about why such a small change
matters.

## Why

### Four roles, one request

| Role | Class | Job |
|---|---|---|
| Primary adapter | `PaymentController` | Translates an outside protocol (HTTP + JSON) into a call the application understands, and the result back. |
| Primary port | `CreatePaymentUseCase` | States what the application offers: "you can ask me to create a payment". |
| Application service | `CreatePaymentService` | Implements the port. Coordinates the work: builds domain objects and, from Episode 03 on, talks to secondary ports. |
| Domain | `Payment`, `Money`, ... | Holds the business rules. Knows nothing about any of the above. |

*Primary* (or *driving*) means the outside world starts the conversation. An
HTTP client, and later a message consumer, drives the application through the
primary port. Episode 03 introduces the other direction, secondary ports,
where the application drives the outside world.

### The port belongs to the application

```java
package com.example.payment.application.port.primary;

public interface CreatePaymentUseCase {

	Payment createPayment(CreatePaymentCommand command);
}
```

The interface lives in `application/`, next to the service, not in the web
package. The application publishes the contract and adapters consume it. If the
interface lived in the web package, the application would have to depend on
infrastructure in order to implement it, and the arrow would point the wrong
way.

### Dependency inversion

Before:

```java
private final CreatePaymentService createPaymentService;
```

After:

```java
private final CreatePaymentUseCase createPaymentUseCase;

public PaymentController(CreatePaymentUseCase createPaymentUseCase) {
	this.createPaymentUseCase = createPaymentUseCase;
}
```

Spring still injects `CreatePaymentService`, since it is the only bean that
implements the interface. What changed is what the controller is *allowed to
know*. It compiles against a contract, not against an implementation, so:

- **The service can change freely.** In Episode 03 `CreatePaymentService` gains
  an `AccountEnquiryPort` dependency. In Episode 04 it gains `PaymentRepository`.
  The controller does not change in either episode, because the port does not change.
- **Other adapters use the same contract.** A Kafka consumer in Episode 17 calls
  `CreatePaymentUseCase` exactly as the controller does, and gets the same rules.
- **The adapter can be tested on its own.** See below.

### The payoff in a test

`CreatePaymentUseCase` has a single method, so a lambda is a complete
implementation. The new test hands one to the controller and drives it over
HTTP, with no Spring context, no service and no domain logic involved:

```java
MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(command -> {
	received.set(command);
	return Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
			new Money(new BigDecimal("9.99"), Currency.GBP), new PaymentReference("From stub"));
})).build();
```

It proves the two things the adapter is responsible for: the JSON request
becomes the right `CreatePaymentCommand`, and the returned `Payment` becomes
the right JSON. The stub deliberately returns a payment different from the
request, so the test fails if the controller echoes the request back
instead of mapping the result.

`PaymentControllerTest` still runs the full stack with `@SpringBootTest`. The two
tests check different things: one tests the adapter alone, the other tests that
the whole stack is wired together.

### Why not one interface per class?

Ports exist where a boundary exists. `CreatePaymentUseCase` sits between
infrastructure and the application, so it earns an interface. Value objects,
the `Payment` entity and the command record get no interfaces, because nothing
outside their own layer needs to substitute them.

## Known shortcuts

Unchanged from Episode 01:

- **Error handling** is still a single `@ExceptionHandler` in the controller.
  Episode 08 replaces it.
- **Nothing is stored.** Episode 04 introduces `PaymentRepository`.
- **The boundary is enforced by convention only.** Nothing stops someone from
  injecting `CreatePaymentService` directly again. Episode 14 turns the rules
  into ArchUnit tests.

## Try it

```bash
./mvnw test
```

The requests in [`http/payments.http`](../../http/payments.http) behave exactly
as in Episode 01.

## Tests

| Test | What it proves |
|---|---|
| `infrastructure/adapter/primary/web/PaymentControllerPortTest` | **New.** The controller maps request to command and payment to response, using only the port. |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | The HTTP contract end to end, unchanged. |
| `domain/...` | Unchanged from Episode 01. |
