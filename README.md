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
./mvnw spring-boot:run   # start on http://localhost:8080 (PostgreSQL starts from compose.yaml)
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

## License

[MIT](LICENSE)
