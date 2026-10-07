# Java Payment Service

A payment service for a **fictional bank**, built step by step to show how a
Clean/Hexagonal Architecture emerges from real requirements. No real bank, customer
data, API contract or credential appears anywhere in this repository.

> Clean Architecture is not a package structure. The package structure is the visible
> result of boundaries that protect business rules and isolate change.

## Getting started

Requirements: Java 25. Maven is provided by the wrapper.

```bash
./mvnw verify            # build and run all tests
./mvnw spring-boot:run   # start on http://localhost:8080
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
| 02 | [Establish the Primary Port Boundary](docs/episodes/02-primary-port-boundary.md) | `episode-02-primary-port-boundary` |

## License

[MIT](LICENSE)
