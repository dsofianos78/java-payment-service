# Episode 07 — External Account System

Git tag: `episode-07-account-system` · Previous: [Episode 06 — Payment Validation](06-payment-validation.md) · Next: Episode 08 — Error Handling

## Goal

Since Episode 03 the account answers came from `StubAccountEnquiryAdapter`, a
map in memory. This episode replaces it with a real HTTP call to an external
account system, through Feign.

The port stays exactly as it is:

```text
application/port/secondary/AccountEnquiryPort
```

```text
Application                CreatePaymentService -> AccountStateValidator
    |
    v
AccountEnquiryPort         (application, unchanged)
    ^
    |  implements
Feign Adapter              AccountEnquiryFeignAdapter    (infrastructure)
    |
    v
External Account System    GET /accounts/{accountNumber}   (WireMock here)
```

Swapping the adapter is the test of Episode 03's port. If the port was right,
nothing in `application` or `domain` changes. It doesn't.

## The external system speaks its own language

The account system is fictional, but it behaves like a real one: it was
designed by another team, for many clients, not for us.

```http
GET /accounts/ACC-80001
```

```json
{
  "accountNumber": "ACC-80001",
  "accountName": "Fictional Current Account",
  "currency": "EUR",
  "state": "FROZEN"
}
```

| Their answer | Our `AccountStatus` |
|---|---|
| `200`, `"state": "OPEN"` | `ACTIVE` |
| `200`, `"state": "FROZEN"` | `BLOCKED` |
| `200`, `"state": "CLOSED"` | `CLOSED` |
| `404` | `Optional.empty()`: no such account |
| `200`, any other state | `IllegalStateException`: fail closed |
| `5xx`, timeout, connection refused | `FeignException`: **not** "no such account" |

Every row of that table lives in one class, `AccountEnquiryFeignAdapter`. The
application never sees `"FROZEN"`, `accountNumber` or a Feign exception.

## What changed

```text
pom.xml                                          + Spring Cloud OpenFeign, + WireMock (test)
compose.yaml                                     + account-system (WireMock) for local runs
wiremock/mappings/accounts.json                  new: the fictional accounts, shared by compose and tests

src/main/java/com/example/payment/
├── config/
│   └── FeignConfiguration.java                  new: @EnableFeignClients for the feign package
└── infrastructure/adapter/secondary/
    ├── feign/
    │   ├── AccountClient.java                   new: their HTTP API, as a Feign interface
    │   ├── AccountResponse.java                 new: their JSON
    │   └── AccountEnquiryFeignAdapter.java      new: implements AccountEnquiryPort, maps theirs -> ours
    └── stub/
        └── StubAccountEnquiryAdapter.java       deleted

src/main/resources/application.properties        + account-system.url

src/test/java/com/example/payment/infrastructure/adapter/
├── secondary/feign/
│   └── AccountEnquiryFeignAdapterTest.java      new: real Feign against WireMock
└── primary/web/
    └── PaymentControllerTest.java               runs against WireMock, + unknown account case
```

`application/` and `domain/` did not change. `CreatePaymentServiceTest` did
not change either: it still uses a map as its fake account system, because
the port is still one method.

## Why

### Three classes, three jobs

- **`AccountClient`** describes *their* API: a path, a verb, a response type.
  Feign writes the implementation. It knows nothing about payments.
- **`AccountResponse`** is *their* JSON. It declares only the fields the
  adapter reads. Jackson ignores `accountName` and `currency`, so when the
  account team adds a field, we don't break.
- **`AccountEnquiryFeignAdapter`** is the translator. It is the only public
  class in the package. `AccountClient` and `AccountResponse` are
  package-private, so nothing else in the codebase can depend on them.

### Why not just put `@FeignClient` on `AccountEnquiryPort`?

It's one fewer class, and it would put Feign annotations, their URL and their
JSON shape into `application`. The port would then change every time the
account system changed. The port describes what *we* need; the client
describes what *they* offer. The adapter is where the two meet.

### A 404 is an answer; a 503 is not

```java
catch (FeignException.NotFound e) {
	return Optional.empty();
}
```

Only `404` means "no such account". If a `503` were also treated as empty,
an outage in the account system would show up as "Source account does not
exist", which is wrong and would send people looking for the wrong problem.
Everything else stays an exception for now.

### Unknown states fail closed

```java
case null, default -> throw new IllegalStateException("Unknown account state from account system: " + state);
```

If the account system starts sending `"DORMANT"`, mapping it to `ACTIVE`
could move money out of an account the bank has stopped. Mapping it to
`BLOCKED` would quietly reject payments with a misleading reason. Failing
loudly is the only safe choice for money.

### `@EnableFeignClients` lives in `config`

On `PaymentApplication` it would also apply to test slices such as
`@DataJpaTest`, which would then try to build an HTTP client. In
`config/FeignConfiguration` it is part of normal component scanning, and it
is limited to the feign adapter package.

### One set of fake accounts

`wiremock/mappings/accounts.json` is used twice: the `account-system`
container in `compose.yaml` serves it for `./mvnw spring-boot:run`, and
`PaymentControllerTest` loads the same file into an in-process WireMock. The
accounts you try by hand are the accounts the tests prove.

## Known shortcuts

- **Account system errors are a `500`.** Episode 08 introduces
  `AccountNotFoundException` and `AccountUnavailableException` and maps the
  latter to `503`.
- **Feign's default timeouts, no retries, no circuit breaker.** Episode 16
  adds them.
- **Fixed local port `8089`** for the WireMock container, so
  `account-system.url` can be a plain property. Episode 19 builds the full
  local environment.

## Try it

```bash
./mvnw spring-boot:run
```

Spring Boot starts PostgreSQL and the fake account system from
`compose.yaml`. In [`http/payments.http`](../../http/payments.http), run the
Episode 07 requests: a payment from the frozen `ACC-80001` is rejected, and
you can ask the account system directly to see its raw answer.

## Tests

| Test | What it proves |
|---|---|
| `infrastructure/adapter/secondary/feign/AccountEnquiryFeignAdapterTest` | **New.** The real Feign client over real HTTP against WireMock, with only Feign and Jackson started. `OPEN`/`FROZEN`/`CLOSED` map to `ACTIVE`/`BLOCKED`/`CLOSED`; `404` is empty; an unknown state fails closed; a `503` is an error, not "no such account". |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | Now runs against WireMock with the shared mappings, instead of the stub. **+1 case:** an account the account system returns `404` for is `400` with `Source account ACC-99999 does not exist`. |
| Everything else | Unchanged. |
