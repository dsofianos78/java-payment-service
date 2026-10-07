# Episode 04 — Persist Payments

Git tag: `episode-04-persistence` · Previous: [Episode 03 — Account Enquiry](03-account-enquiry.md) · Next: [Episode 05 — Query a Payment](05-query-payment.md)

## Goal

Payments must survive application restarts.

Until now a payment existed only for the length of one HTTP request. This
episode stores it in PostgreSQL, through the second secondary port:
`PaymentRepository`.

```text
Application Service  CreatePaymentService         (application)
     |
     |  uses
     v
Secondary Port       PaymentRepository            (application)
     ^
     |  implements
Secondary Adapter    PaymentPersistenceAdapter    (infrastructure)
     |
     v
                     PaymentJpaRepository -> PostgreSQL
```

The arrows are the point. The application depends on `PaymentRepository`,
and the persistence adapter depends on it too. Neither the application nor
the domain depends on JPA or PostgreSQL.

## What changed

```text
compose.yaml                                     new: local PostgreSQL for spring-boot:run
pom.xml                                          + JPA, Flyway, PostgreSQL, Testcontainers

src/main/java/com/example/payment/
├── application/
│   ├── port/secondary/
│   │   └── PaymentRepository.java               new: what the application needs
│   └── service/command/
│       └── CreatePaymentService.java            saves the payment it creates
└── infrastructure/adapter/secondary/persistence/
    ├── PaymentEntity.java                       new: the table row
    ├── PaymentEntityMapper.java                 new: Payment -> PaymentEntity
    ├── PaymentJpaRepository.java                new: Spring Data repository
    └── PaymentPersistenceAdapter.java           new: implements PaymentRepository

src/main/resources/
├── application.properties                       Hibernate validates, never creates
└── db/migration/V1__create_payment.sql          new: the payment table

src/test/java/com/example/payment/
├── TestcontainersConfiguration.java             new: PostgreSQL in Docker for tests
├── application/service/command/
│   └── CreatePaymentServiceTest.java            + payment is saved, rejected ones aren't
└── infrastructure/adapter/
    ├── primary/web/PaymentControllerTest.java   + the row is in the database
    └── secondary/persistence/
        └── PaymentPersistenceAdapterTest.java   new: real PostgreSQL
```

`Payment`, the value objects, `PaymentController` and `CreatePaymentUseCase`
did not change.

## Why

### The port is shaped by today's need

```java
public interface PaymentRepository {

	void save(Payment payment);
}
```

One method. The application stores payments and doesn't read them back yet,
so the port doesn't offer that. Episode 05 adds `GET /payments/{id}` and, with
it, `findById`. A port grows when the application needs more from it, not
when a framework happens to offer more. `JpaRepository` has dozens of
methods; the application sees one.

It speaks in domain types. It takes a `Payment`, not an entity or a row.

### Two models, on purpose

`Payment` (domain) and `PaymentEntity` (persistence) hold the same data,
and that is deliberate:

| | `domain.entity.Payment` | `PaymentEntity` |
|---|---|---|
| Purpose | Business rules | Table row |
| Fields | Value objects (`Money`, `AccountId`) | Columns (`BigDecimal`, `String`) |
| Constructor | Validates invariants | Empty one for JPA |
| Annotations | None | `@Entity`, `@Column` |
| Visibility | Public | Package-private |

Putting `@Entity` on `Payment` would save a class but cost the domain its
independence. JPA needs a no-arg constructor, non-final fields and
proxyable classes, so an object that can't be invalid would have to allow
being invalid. A column rename would also become a change to the domain
model.

`PaymentEntityMapper` is the only place that knows both shapes. It is a
plain static method, with no mapping library: seven fields don't need one.

### The adapter is the only public class

`PaymentEntity`, `PaymentJpaRepository` and `PaymentEntityMapper` are
package-private. Code outside `persistence/` can't reach them, so it can't
skip the port and query the table directly. The compiler enforces the
boundary today. Episode 14 adds ArchUnit to enforce the rest.

### The schema belongs to the database

```properties
spring.jpa.hibernate.ddl-auto=validate
```

Flyway creates the table from `db/migration/V1__create_payment.sql`.
Hibernate only checks that the entity matches it and refuses to start if it
doesn't. In a bank, schema changes are reviewed, versioned and replayable,
which a migration file is and Hibernate's auto-DDL isn't.

The table repeats the domain's rules where it's cheap: `NOT NULL`
everywhere, `amount > 0`, `reference` capped at 140 characters. The domain
remains the source of truth. The constraints catch anything that bypasses
it, such as a manual fix in production or a bad migration.

### Tests use a real PostgreSQL

`PaymentPersistenceAdapterTest` runs on Testcontainers, not an in-memory
database. H2 would accept SQL PostgreSQL rejects, and the other way round.
The point of the test is that the entity, the mapper and the migration
agree, and only the real database can confirm that.

```java
adapter.save(payment);
entityManager.flush();
entityManager.clear(); // read back from the table, not from Hibernate's cache
```

Without `clear()`, the read would come from Hibernate's first-level cache
and prove nothing about the table.

`CreatePaymentServiceTest` still needs no database and no Spring. The new
port has one method, so a list is a complete fake:

```java
private final List<Payment> saved = new ArrayList<>();

private final CreatePaymentService service = new CreatePaymentService(accountId -> ..., saved::add);
```

## Known shortcuts

- **`save()` runs a `SELECT` before the `INSERT`.** The domain assigns the
  id, so Spring Data can't tell a new payment from an existing one and
  checks first. Implementing `Persistable` removes that query; it isn't
  worth the code yet.
- **No `@Transactional` on the service.** It writes one row, and
  `JpaRepository.save` is already transactional. Episode 13 (transactions and audit)
  writes more than one thing per request and draws the transaction
  boundary there.
- **The mapper only goes one way.** Episode 05 adds `toDomain` with
  `findById`.
- **`compose.yaml` holds only PostgreSQL.** Episode 19 grows it into the
  full local environment.

## Try it

Requires Docker.

```bash
./mvnw test
./mvnw spring-boot:run
```

`spring-boot:run` starts PostgreSQL from `compose.yaml`, and stops it again
when the application stops. The data lives in a named volume, so it
survives restarts. Create a payment with the first request in
[`http/payments.http`](../../http/payments.http), restart the application,
and it is still there:

```bash
docker compose exec postgres psql -U payments -c 'select id, amount, currency, status from payment'
```

`docker compose down -v` deletes the data.

## Tests

| Test | What it proves |
|---|---|
| `infrastructure/adapter/secondary/persistence/PaymentPersistenceAdapterTest` | **New.** Every field reaches the table in PostgreSQL. Entity, mapper and Flyway migration agree. |
| `application/service/command/CreatePaymentServiceTest` | **Extended.** A valid payment is saved. A rejected one never reaches the repository. |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | **Extended.** After `POST /payments`, the payment's row exists in the database. |
| `infrastructure/adapter/primary/web/PaymentControllerPortTest` | Unchanged. The controller doesn't know payments are stored. |
| `domain/...` | Unchanged. The domain doesn't know either. |
