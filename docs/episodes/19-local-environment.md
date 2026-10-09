# Episode 19 — Production-Style Local Environment

Git tag: `episode-19-local-environment` · Previous: [Episode 18 — Testing Strategy](18-testing-strategy.md) · Next: [Episode 20 — Final Architecture Review](20-final-review.md)

## Goal

Run the whole service on a laptop, with no access to any real bank system:
PostgreSQL, a fake account, authorization, limit and payment system,
Kafka, Prometheus and Grafana. Run it the way production runs it: a
container image, configured only from outside.

Most of the infrastructure arrived one episode at a time. Episode 04 added
PostgreSQL, Episode 07 WireMock, Episode 15 Prometheus and Grafana, and
Episode 17 Kafka. All of them ran next to a service started with
`./mvnw spring-boot:run`, which Spring Boot connects to the containers by
itself. That is convenient, but it isn't how the service runs in
production. This episode adds the missing piece: the service itself, as
an image.

## Two ways to run it

| | `./mvnw spring-boot:run` | `docker compose --profile app up --build` |
|---|---|---|
| The service runs | on the host, from `target/classes` | in a container, from the jar in the image |
| Who starts the infrastructure | Spring Boot's Docker Compose support | Docker Compose |
| Database address and password | discovered by Spring Boot from the container | `SPRING_DATASOURCE_*` environment variables |
| External systems, Kafka | `application.properties` defaults (`localhost`) | `*_SYSTEM_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS` |
| Use it for | writing code: fast restarts, debugger | checking that the image works as it will in production |

The service in the container is in a compose **profile**, so Spring Boot,
which runs `docker compose up` without one, never starts it. The two ways
share the same infrastructure, the same data volume and port `8080`, so
run one at a time.

## Configured from outside

The image contains the jar and nothing that says where it runs.
`application.properties` keeps its `localhost` example values, and the
container replaces them through environment variables:

```yaml
environment:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/payments
  SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:19092
  ACCOUNT_SYSTEM_URL: http://account-system:8080
```

Spring Boot reads `ACCOUNT_SYSTEM_URL` for `${account-system.url}`. It
upper-cases the name and turns `.` and `-` into `_`. No code changed, and no
`application-docker.properties` was added. A real deployment does exactly
this with its own values: the same image, a different environment. If
there were a profile per environment, every new environment would mean a
new file in the repository. That is how production configuration ends up
being committed.

## The image

[`Dockerfile`](../../Dockerfile) has two stages. The first builds the jar
with the Maven wrapper. The second copies only the jar into a JRE image:

- **Tests are skipped in the build.** They need Docker (Testcontainers),
  which isn't available inside a `docker build`. `./mvnw verify` and CI
  still run all 147.
- **Not root.** The service runs as the image's `ubuntu` user (uid 1000). A
  process that is broken into gets no more than that user can do.
- **`ENTRYPOINT ["java", ...]`, not a shell.** `docker stop` sends `SIGTERM`
  to Java itself, and Spring Boot shuts down gracefully: requests in flight
  finish, and the Kafka consumer commits its offsets.

## What the containers needed

Inside a container, `localhost` means the container, not the laptop, so
every address changed. Two of them needed more than a new name:

- **Kafka needs two listeners.** A Kafka client asks the broker which
  address to use, and then reconnects to that one. With a single
  `localhost:9092` listener, the service in a container would be told to
  connect to itself. The broker now advertises `localhost:9092` to the host
  and `kafka:19092` to the compose network. Setting any `KAFKA_` variable
  replaces the image's whole default configuration, which is why
  `docker-compose.yml` lists the full set.
- **Prometheus scrapes two targets.** `payment-service:8080` over the compose
  network for the container, and `host.docker.internal:8080` for the host.
  Whichever isn't running shows as down. A host firewall (such as `ufw`)
  blocks the second one. That has been true since Episode 15. The
  container way works either way.

Two more things a container platform would also give the service:

- **Start order.** Flyway migrates the schema at start-up, so the service
  waits until PostgreSQL's `pg_isready` healthcheck passes.
- **Restarts.** `restart: on-failure` on the service and on Kafka. While
  building this episode, the `kafka-native` image segfaulted once at start-up
  in about 20 starts (a GraalVM native-image crash, before Kafka runs). That
  is probably also the Kafka container that "failed to start in time" in
  Episode 18.

## Example configuration only

Every value in `docker-compose.yml` and `application.properties` is an
example for local containers: the `payments`/`payments` database login,
`localhost` and compose-network addresses, WireMock in place of every
external system. Nothing points at a real system.

Real values never enter the repository. `.gitignore` now refuses the files
they would arrive in: `.env` (which Docker Compose reads automatically),
and key and certificate stores (`*.pem`, `*.key`, `*.p12`, `*.jks`).

## What changed

```text
compose.yaml -> docker-compose.yml     renamed (the spec's name; Spring Boot finds either)
                                       + payment-service (profile app): built from Dockerfile, configured by environment
                                       + postgres healthcheck; kafka: two listeners, restart: on-failure
Dockerfile                             new: build with ./mvnw, run the jar on a JRE as a non-root user
.dockerignore                          new: target/, .git/ and local tooling stay out of the build
observability/prometheus.yml           + payment-service:8080 target
.gitignore                             + .env, *.pem, *.key, *.p12, *.jks
application.properties, http/payments.http, PaymentEndToEndTest
                                       compose.yaml -> docker-compose.yml in comments
```

No Java code changed.

## Known shortcuts

- **One WireMock plays four systems.** In production each has its own URL.
  The four `*_SYSTEM_URL` variables already allow for that.
- **The database login is in `docker-compose.yml`.** It is an example for a
  throwaway local container. In production it would come from the
  platform's secret store, through the same `SPRING_DATASOURCE_PASSWORD`.
- **No healthcheck on the service.** The JRE image has no `curl`, and nothing
  waits on the service yet. `/actuator/health` already has the `liveness`
  and `readiness` groups a container platform would probe.
- **The image is not built in CI.** CI runs `./mvnw verify`, and the image only
  packages the same jar. Add `docker build .` to CI when the `Dockerfile`
  starts changing.
- **Grafana has a data source but no dashboard.** Unchanged since Episode 15.

## Try it

```bash
docker compose --profile app up --build
```

The first build downloads Maven's dependencies into a build cache. Later
builds reuse it. When the log shows `Started PaymentServiceApplication`,
every request in [http/payments.http](../../http/payments.http) works
unchanged against `localhost:8080`:

1. Create a payment and `execute` it, as in Episode 17: `202`, then `GET`
   shows `COMPLETED`. The message went through Kafka on `kafka:19092`.
2. `docker compose exec payment-service id`: `uid=1000(ubuntu)`.
3. Open <http://localhost:9090/targets>: `payment-service:8080` is up.
   Query `payments_total`.
4. `docker compose --profile app down`. Add `-v` to delete the database
   volume as well.

To go back to working on code, use `./mvnw spring-boot:run`. It starts
everything except the containerised service.

## Tests

No tests changed. 147 tests, passing. The environment was checked by hand,
in both ways, with the steps above.
