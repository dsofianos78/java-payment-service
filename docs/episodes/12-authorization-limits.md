# Episode 12 — Authorization and Limits

Git tag: `episode-12-authorization-limits` · Previous: [Episode 11 — Cancel Payment](11-cancel-payment.md) · Next: Episode 13 — Transactions and Audit

## Goal

Before money moves, two external systems now get a say:

```text
Payment
   |
   v
Account validation    on create    AccountEnquiryPort         (Episode 06/07)
   |
   v
Authorization         on execute   PaymentAuthorizationPort   new
   |
   v
Limit check           on execute   PaymentLimitPort           new
   |
   v
Execution             on execute   PaymentExecutionPort       (Episode 09)
```

`CREATED -> AUTHORIZED` now means something: the authorization system said
yes. Until this episode every payment was authorized automatically.

## Validation is not authorization

| | Validation | Authorization |
|---|---|---|
| Question | Is this a well-formed, acceptable request? | May this payment go ahead? |
| Who answers | Us: the domain and `application/validation` | Someone else: the authorization system |
| When | On create, before anything is stored | On execute, just before money moves |
| Same input, same answer? | Yes | Not necessarily: a fraud score or mandate can change |
| When it fails | `400`, nothing is stored | `422`, the payment stays `CREATED` |

A payment to a flagged account is a perfectly valid payment. Nothing about
the request is wrong; the bank just won't send it. That's why a decline is
`422`, not `400`. It's not `403` either: `403` would say the *caller* lacks
permission, which is a different question (see Known shortcuts).

## Why limits moved from create to execute

Episode 06 checked a fixed `10000.00` maximum in `PaymentAmountLimitValidator`
on create. Real limits depend on what the account has already spent today,
and a payment can be created on Monday and executed on Friday. A check on
create would answer the wrong question at the wrong time, so the limit now
lives in an external limit system, behind `PaymentLimitPort`, and is asked
on execute. `PaymentAmountLimitValidator` is gone.

(Episode 06 said only the validator would change. Following the business
flow above, the check moved instead.)

A payment refused by its limit stays `AUTHORIZED`. Executing it again later,
when the limit allows, goes straight to the limit check: authorization isn't
asked twice. Or it can be cancelled (Episode 11).

## Who decides what

```text
ExecutePaymentService
    |  status CREATED?  -> PaymentAuthorizationPort.isAuthorized   no -> 422, stays CREATED
    |                      payment.authorize(), save AUTHORIZED
    |  payment.startProcessing()      the domain refuses anything not AUTHORIZED -> 409
    |  PaymentLimitPort.isWithinLimit                                no -> 422, stays AUTHORIZED
    |  save PROCESSING, PaymentExecutionPort.execute, save COMPLETED | FAILED
```

- **The ports return a yes or no.** The external systems make the decision;
  the adapters only translate it. Comparing amounts against a limit in the
  adapter would be business logic in an adapter.
- **The domain still guards every transition.** A `COMPLETED` or `CANCELLED`
  payment is refused by `startProcessing()` before the limit system is asked.
- **Fail closed.** A decision the adapter doesn't recognise (`MAYBE`) is a
  `500`, never a yes.

## What changed

```text
src/main/java/com/example/payment/
├── application/
│   ├── port/secondary/
│   │   ├── PaymentAuthorizationPort.java            new
│   │   └── PaymentLimitPort.java                    new
│   ├── exception/
│   │   ├── PaymentAuthorizationException.java       new: 422
│   │   ├── PaymentLimitExceededException.java       new: 422
│   │   └── ExternalSystemUnavailableException.java  new: 503
│   ├── validation/PaymentAmountLimitValidator.java  removed
│   └── service/command/
│       ├── CreatePaymentService.java                - amount limit
│       └── ExecutePaymentService.java               + authorization, limit check
└── infrastructure/adapter/
    ├── primary/web/PaymentExceptionHandler.java     + 422 / 503 for the new exceptions
    └── secondary/feign/
        ├── AuthorizationClient.java                 new: POST /authorizations
        ├── AuthorizationAdapter.java                new
        ├── LimitClient.java                         new: POST /limit-checks
        └── LimitAdapter.java                        new

src/main/resources/application.properties            + authorization-system.url, limit-system.url
wiremock/mappings/
├── accounts.json                                    + ACC-70001
├── authorizations.json                              new: ACC-70001 as creditor is DECLINED
└── limits.json                                      new: amount over 10000 is LIMIT_EXCEEDED
```

The request and response records live inside each Feign client: they are
that system's wire format and nothing else uses them.

Locally the WireMock container from Episode 07 plays all three external
systems. Each still has its own URL property, because in a real deployment
they are different systems.

| Situation | HTTP | Payment afterwards |
|---|---|---|
| authorized, within limit | `200` | `COMPLETED` or `FAILED` |
| authorization declined | `422` | `CREATED` |
| limit exceeded | `422` | `AUTHORIZED` |
| authorization or limit system down | `503` | unchanged |
| already `PROCESSING`, `COMPLETED`, `FAILED` or `CANCELLED` | `409` | unchanged |

The `409` message now reads `cannot become PROCESSING` instead of
`cannot become AUTHORIZED`: a payment that isn't `CREATED` skips
authorization, and the domain stops it at the next step.

## Known shortcuts

- **Anyone can read, execute or cancel any payment.** That's about who the
  *caller* is (authentication), not whether the *payment* is authorized.
  It's outside this tutorial's scope.
- **A crash between saving `AUTHORIZED` and the limit check is harmless**, as
  the next execute continues from `AUTHORIZED`. Two executes at the same
  time are still the race from Episode 09; Episode 13 closes it.
- **Feign default timeouts, no retries.** Episode 16 adds them.

## Try it

```bash
./mvnw spring-boot:run
```

In [`http/payments.http`](../../http/payments.http), run the Episode 12
requests: a payment to `ACC-70001` is created (`201`) but declined on
execute (`422`, still `CREATED`); a payment of `10000.01` is created but
refused by its limit (`422`, now `AUTHORIZED`).

## Tests

| Test | What it proves |
|---|---|
| `application/service/command/ExecutePaymentServiceTest` | **+3 cases.** A declined payment is neither stored nor sent; a payment over its limit is stored `AUTHORIZED` and not sent; executing an `AUTHORIZED` payment doesn't ask for authorization again. Existing cases now also see the `AUTHORIZED` save. |
| `infrastructure/adapter/secondary/feign/AuthorizationAndLimitAdapterTest` | **New.** Real Feign clients against WireMock: the payment is sent in their terms; approved/declined and within/exceeded are read; an unknown decision fails closed; a 5xx is `ExternalSystemUnavailableException`. |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | **+3 cases, −1.** End to end: decline is `422` and stays `CREATED`; `10000.01` is `422` and stays `AUTHORIZED`; `10000.00` completes. The create-time `10000.01` case is gone. |
| `infrastructure/adapter/primary/web/PaymentControllerPortTest` | The three new exceptions map to `422`, `422` and `503`. |
| `application/service/command/CreatePaymentServiceTest` | The amount-limit cases are gone with the validator. |
| `infrastructure/adapter/secondary/feign/AccountEnquiryFeignAdapterTest` | Unchanged, except it now gives the new clients a URL too. |
