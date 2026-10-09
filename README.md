# Java Payment Service

A payment service for a **fictional bank**, built step by step to show how a
Clean/Hexagonal Architecture emerges from real requirements. No real bank, customer
data, API contract or credential appears anywhere in this repository.

> Clean Architecture is not a package structure. The package structure is the visible
> result of boundaries that protect business rules and isolate change.

## Getting started

Requirements: Java 25 and Docker. Maven is provided by the wrapper.

```bash
./mvnw verify            # build and run all tests
./mvnw spring-boot:run   # start on http://localhost:8080 (PostgreSQL, a fake account system, Prometheus and Grafana start from compose.yaml)
```

Example requests for each episode are in [http/payments.http](http/payments.http).

## Episodes

Each episode is a git tag. Check one out to see the code exactly as that episode leaves it:

```bash
git checkout episode-01-working-payment
```

| # | Episode | Tag |
|---|---|---|
| 01 | [Create a Working Payment](docs/episodes/01-working-payment.md) | `episode-01-working-payment` |
| 02 | [Establish the Primary Port Boundary](docs/episodes/02-primary-port-boundary.md) | `episode-02-primary-port` |
| 03 | [Account Enquiry](docs/episodes/03-account-enquiry.md) | `episode-03-account-enquiry` |
| 04 | [Persist Payments](docs/episodes/04-persist-payments.md) | `episode-04-persistence` |
| 05 | [Query a Payment](docs/episodes/05-query-payment.md) | `episode-05-payment-query` |
| 06 | [Payment Validation](docs/episodes/06-payment-validation.md) | `episode-06-validation` |
| 07 | [External Account System](docs/episodes/07-account-system.md) | `episode-07-account-system` |
| 08 | [Error Handling](docs/episodes/08-error-handling.md) | `episode-08-error-handling` |
| 09 | [Execute Payment](docs/episodes/09-payment-execution.md) | `episode-09-payment-execution` |
| 10 | [Idempotency](docs/episodes/10-idempotency.md) | `episode-10-idempotency` |
| 11 | [Cancel Payment](docs/episodes/11-cancel-payment.md) | `episode-11-cancellation` |
| 12 | [Authorization and Limits](docs/episodes/12-authorization-limits.md) | `episode-12-authorization-limits` |
| 13 | [Transactions and Audit](docs/episodes/13-transactions-audit.md) | `episode-13-transactions-audit` |
| 14 | [Architecture Tests](docs/episodes/14-architecture-tests.md) | `episode-14-architecture-tests` |
| 15 | [Observability](docs/episodes/15-observability.md) | `episode-15-observability` |

## License

[MIT](LICENSE)
