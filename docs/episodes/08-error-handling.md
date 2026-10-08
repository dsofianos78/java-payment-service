# Episode 08 — Error Handling

Git tag: `episode-08-error-handling` · Previous: [Episode 07 — External Account System](07-account-system.md) · Next: [Episode 09 — Execute Payment](09-payment-execution.md)

## Goal

Until now every failure was an `IllegalArgumentException`, and the controller
turned all of them into `400`. That had two problems:

- **An outage looked like a bug.** When the account system was down, the
  Feign exception escaped as a `500`.
- **The web adapter had to guess.** Any `IllegalArgumentException`, from
  anywhere, became a `400` with its message shown to the caller.

This episode gives the application its own vocabulary for what went wrong:

```text
application/exception
├── PaymentValidationException     the request can't become a payment
├── AccountNotFoundException       the account system has no such account
├── AccountUnavailableException    the account system gave no answer
└── PaymentNotFoundException       no payment has that id
```

and lets the web adapter alone decide what each one means in HTTP.

## Who says what

```text
Domain          IllegalArgumentException("Unsupported currency: JPY")
    |           knows the rule, not who asked or how
    v
Application     PaymentValidationException / AccountNotFoundException / ...
    ^           knows what went wrong for this use case
    |
Feign adapter   FeignException (503, timeout) -> AccountUnavailableException
    |
    v
Web adapter     PaymentExceptionHandler: exception -> HTTP status + problem details
                the only class that knows "400" or "503"
```

| Application exception | Thrown by | HTTP |
|---|---|---|
| `PaymentValidationException` | `CreatePaymentService`, `GetPaymentService` (domain invariants), `PaymentAmountLimitValidator`, `AccountStateValidator` (inactive account) | `400` |
| `AccountNotFoundException` | `AccountStateValidator` | `400` |
| `PaymentNotFoundException` | `GetPaymentService` | `404` |
| `AccountUnavailableException` | `AccountEnquiryFeignAdapter` | `503` |
| anything else | — | `500` |

Every error body is an RFC 9457 problem details document:

```json
{
  "type": "about:blank",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "Account system is unavailable"
}
```

## What changed

```text
src/main/java/com/example/payment/
├── application/
│   ├── exception/                               new package
│   │   ├── PaymentValidationException.java      new
│   │   ├── AccountNotFoundException.java        new
│   │   ├── AccountUnavailableException.java     new
│   │   └── PaymentNotFoundException.java        new
│   ├── port/primary/GetPaymentUseCase.java      returns GetPaymentResult, not Optional
│   ├── service/command/CreatePaymentService.java  domain IllegalArgumentException -> PaymentValidationException
│   ├── service/query/GetPaymentService.java     malformed id -> PaymentValidationException, unknown -> PaymentNotFoundException
│   └── validation/                              throw application exceptions
└── infrastructure/adapter/
    ├── primary/web/
    │   ├── PaymentController.java               no more @ExceptionHandler, no more ResponseEntity
    │   └── PaymentExceptionHandler.java         new: application exception -> HTTP
    └── secondary/feign/
        └── AccountEnquiryFeignAdapter.java      FeignException -> AccountUnavailableException

src/main/resources/application.properties        + spring.mvc.problemdetails.enabled
```

`domain/` did not change. It still throws `IllegalArgumentException` and has
no idea that HTTP exists.

## Why

### The domain stays out of it

`Currency.of("JPY")` throws `IllegalArgumentException`, as it has since
Episode 01. A domain-specific exception hierarchy would add classes without
adding meaning: the domain's only answer is "this value can't exist".

The application service is where that answer gets context. It is creating a
payment, so a broken invariant means the *payment request* is invalid:

```java
catch (IllegalArgumentException e) {
	throw new PaymentValidationException(e.getMessage(), e);
}
```

The catch wraps only the code that builds domain objects. An
`IllegalArgumentException` from anywhere else is a bug, and now it shows up
as a `500` instead of being blamed on the caller.

### Not found vs. unavailable

Episode 07 already refused to treat a `503` from the account system as "no
such account". Now each case has a name:

- **`AccountNotFoundException`**: the account system answered, and the answer
  is no. The caller sent a wrong account: `400`.
- **`AccountUnavailableException`**: there was no answer. Nothing is wrong
  with the request; retrying later may work: `503`.

Unknown states (`"DORMANT"`) still fail closed with `IllegalStateException`,
so they become `500`. That's a broken contract between two systems, not a
temporary outage, and retrying won't fix it.

### Why an unknown account is `400`, not `404`

`404` would say "`/payments` doesn't exist". The resource is there; the
request body points at an account that isn't. That's a bad request.

### Why `GetPaymentUseCase` stopped returning `Optional`

With `Optional`, the controller decided that "empty" meant `404`. That is the
application's knowledge leaking into the adapter. Now the use case says
"payment not found" itself, and the adapter only translates it.

### The cause is never shown

`AccountUnavailableException` keeps the Feign exception as its cause, for
logs. The response says only `Account system is unavailable`: callers don't
need to know our internal hostnames.

### One body format

`spring.mvc.problemdetails.enabled=true` makes Spring's own errors, such as
missing fields or unreadable JSON, use the same problem details format as
ours. Clients parse one error shape.

## Known shortcuts

- **No error codes or `type` URIs.** `type` is `about:blank`; clients match on
  `status`. Add a machine-readable code when a client needs to tell two `400`s
  apart.
- **No retries or timeouts on the account system.** Episode 16.
- **Exceptions aren't logged or counted.** Episode 15.

## Try it

```bash
./mvnw spring-boot:run
```

In [`http/payments.http`](../../http/payments.http), run the Episode 08
requests. For the `503`, stop the fake account system first:

```bash
docker compose stop account-system
```

## Tests

| Test | What it proves |
|---|---|
| `infrastructure/adapter/primary/web/PaymentControllerPortTest` | **+2 cases.** Each application exception maps to its status, with its message as `detail`, without a Spring context. The `503` doesn't leak the cause. |
| `infrastructure/adapter/secondary/feign/AccountEnquiryFeignAdapterTest` | A `503` from the account system is now `AccountUnavailableException`. |
| `application/service/command/CreatePaymentServiceTest` | Unknown accounts are `AccountNotFoundException`; inactive accounts, broken invariants and the amount limit are `PaymentValidationException`. |
| `application/service/query/GetPaymentServiceTest` | An unknown id is `PaymentNotFoundException`; a malformed one is `PaymentValidationException`. |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | Unchanged statuses end to end. The `404` now has a `detail`, and missing fields return problem details. |
| Everything else | Unchanged. |
