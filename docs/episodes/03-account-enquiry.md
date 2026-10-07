# Episode 03 — Account Enquiry

Git tag: `episode-03-account-enquiry` · Previous: [Episode 02 — Establish the Primary Port Boundary](02-primary-port-boundary.md) · Next: Episode 04 — Persist Payments

## Goal

A payment cannot be created unless both accounts are valid:

- the source account exists
- the destination account exists
- the source account is active
- the destination account is active

The payment service does not own account data. Another system knows which
accounts exist and what state they are in. This episode introduces the first
**secondary port**, the contract through which the application asks for that
information.

```text
Web Adapter          PaymentController            (infrastructure)
     |
     v
Primary Port         CreatePaymentUseCase         (application)
     ^
     |  implements
Application Service  CreatePaymentService         (application)
     |
     |  uses
     v
Secondary Port       AccountEnquiryPort           (application)
     ^
     |  implements
Secondary Adapter    StubAccountEnquiryAdapter    (infrastructure)
```

## What changed

```text
src/main/java/com/example/payment/
├── domain/valueobject/
│   └── AccountStatus.java                  new: ACTIVE, BLOCKED, CLOSED
├── application/
│   ├── port/secondary/
│   │   └── AccountEnquiryPort.java         new: what the application needs
│   └── service/command/
│       └── CreatePaymentService.java       checks both accounts through the port
└── infrastructure/adapter/secondary/stub/
    └── StubAccountEnquiryAdapter.java      new: fixed fictional accounts

src/test/java/com/example/payment/
├── application/service/command/
│   └── CreatePaymentServiceTest.java       new: account rules, no Spring
└── infrastructure/adapter/primary/web/
    └── PaymentControllerTest.java          + closed account -> 400
```

`PaymentController` and `CreatePaymentUseCase` did not change. Episode 02
said they wouldn't.

## Why

### Two directions

Episode 02 introduced a primary port: something the application **offers**.
A secondary port is the opposite: something the application **needs**.

| | Primary port | Secondary port |
|---|---|---|
| Example | `CreatePaymentUseCase` | `AccountEnquiryPort` |
| Who calls it | An adapter (the controller) | The application (the service) |
| Who implements it | The application (the service) | An adapter (the stub) |
| Named after | What a caller can do | The capability the application needs |

Both interfaces live in `application/`. In both cases the application owns
the contract, and infrastructure plugs into it.

### The application decides what it needs

```java
public interface AccountEnquiryPort {

	/** The account's status, or empty if no such account exists. */
	Optional<AccountStatus> findStatus(AccountId accountId);
}
```

The port is shaped by the question the application asks, not by whatever an
account system happens to return. A real account API might return balances,
owners, addresses and product codes. The payment service only needs one fact:
does this account exist, and if so, what state is it in? So that is all the
port offers.

It speaks in domain types (`AccountId`, `AccountStatus`), never in an
external system's DTOs. When Episode 07 connects to a real (fictional)
account system over HTTP, the Feign adapter translates that system's JSON
into an `AccountStatus`. Nothing in `application/` or `domain/` changes.

### Infrastructure decides how

```java
@Component
public class StubAccountEnquiryAdapter implements AccountEnquiryPort {

	private static final Map<String, AccountStatus> ACCOUNTS = Map.of(
			"ACC-10001", AccountStatus.ACTIVE,
			"ACC-20001", AccountStatus.ACTIVE,
			"ACC-30001", AccountStatus.ACTIVE,
			"ACC-80001", AccountStatus.BLOCKED,
			"ACC-90001", AccountStatus.CLOSED);
	...
}
```

A stub is a real implementation of the port, not a test double. It lets the
service run end to end today, before any account system exists. The
`infrastructure/adapter/secondary/stub` package exists for exactly this.

### The rule lives in the application service

```java
requireActive(source, "Source");
requireActive(destination, "Destination");
```

"Both accounts must be active" is a business rule, so it doesn't belong in an
adapter. The stub only answers the question. It doesn't decide what the answer
means, and the Feign adapter in Episode 07 won't either. If the rule lived in
an adapter, it would have to be rewritten each time the adapter is replaced.

The rule doesn't belong in `Payment` either. The domain can only check facts
it is given. Asking another system for those facts is coordination, and
coordination is what the application service does.

### Local checks before remote calls

The service builds all value objects first, and only then asks about accounts:

```java
AccountId source = new AccountId(command.sourceAccountId());
AccountId destination = new AccountId(command.destinationAccountId());
Money amount = new Money(command.amount(), Currency.of(command.currency()));
PaymentReference reference = new PaymentReference(command.reference());

requireActive(source, "Source");
requireActive(destination, "Destination");
```

A request in an unsupported currency is rejected without the account system
ever being called. Today the call is a map lookup and costs nothing. From
Episode 07 on it goes over the network, and from Episode 16 on it can time
out.

### The payoff in a test

Like `CreatePaymentUseCase`, the port has a single method, so a lambda can
implement it. `CreatePaymentServiceTest` builds the service with a map and
records each enquiry. It needs no Spring and no mocking library:

```java
private final CreatePaymentService service = new CreatePaymentService(accountId -> {
	enquiries.add(accountId);
	return Optional.ofNullable(accounts.get(accountId.value()));
});
```

That is the reason for the port: the service can be tested against any
account system, including one written inline in the test.

## Known shortcuts

- **Account errors are plain `IllegalArgumentException`s**, so they come back
  as `400` like any other rule violation. Episode 08 introduces
  `AccountNotFoundException` and `AccountUnavailableException` and maps each
  one to its own HTTP response.
- **The stub's accounts are hard-coded.** Episode 07 replaces the stub with a
  Feign adapter that calls an external account system, tested with WireMock.
- **Nothing is stored.** Episode 04 introduces `PaymentRepository`, the second
  secondary port.

## Try it

```bash
./mvnw test
./mvnw spring-boot:run
```

[`http/payments.http`](../../http/payments.http) has two new requests: an
unknown source account and a closed destination account, both expecting `400`.

Fictional accounts in the stub:

| Account | Status |
|---|---|
| `ACC-10001`, `ACC-20001`, `ACC-30001` | `ACTIVE` |
| `ACC-80001` | `BLOCKED` |
| `ACC-90001` | `CLOSED` |
| anything else | does not exist |

## Tests

| Test | What it proves |
|---|---|
| `application/service/command/CreatePaymentServiceTest` | **New.** Unknown or inactive source/destination is rejected. Active accounts produce a payment. An invalid request never reaches the port. |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | **+1 case.** A closed account comes back as `400` through the full stack, wired to the stub. |
| `infrastructure/adapter/primary/web/PaymentControllerPortTest` | Unchanged. The controller doesn't know accounts are checked. |
| `domain/...` | Unchanged. |
