# Project Overview

This is `hc-admin-gateway` (`adminGateway`) — the edge service of the Health Connect **admin** stack. It is a JHipster 8.3.0 **reactive** gateway built on Spring Cloud Gateway, and it is the only component in this stack that owns users, authorities, and authentication.

There is **no frontend and no JPA/SQL layer in this project**. The Angular SPA lives in the sibling `hc-admin-dashboard` repo; the admin domain data lives in `hc-admin-service`.

- Package root: `net.jojoaddison`
- Ports: **5504** (dev profile), **5503** (prod profile)
- Database: **MongoDB** (`adminGateway` db), migrations via **Mongock**
- Service discovery: **Consul** at `http://localhost:8500` — the app refuses to start without it

## Documentation map

| File                                                                 | What it is                                                                          |
| -------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `AGENTS.md` (this file)                                              | Working conventions — read first                                                    |
| [`README.md`](README.md)                                             | Setup, commands, troubleshooting                                                    |
| [`admin-gateway.md`](admin-gateway.md)                               | **Design plans and blueprints** — the consolidated data blueprints for this gateway |
| [`.github/copilot-instructions.md`](.github/copilot-instructions.md) | Condensed conventions for Copilot                                                   |

`admin-gateway.md` replaced `hc-admin-gw-data.md` and `hc-admin-ms-data.md`. **Its contents are historical — do not execute them as prompts.** It is worth reading for two things: the [blueprint-vs-reality comparison](admin-gateway.md#blueprint-vs-reality) of the seeded user accounts, and the [routing contract](admin-gateway.md#2-microservice-routing-contract) that establishes `/services/hcadminservice/...` as the canonical downstream path.

## Architecture and Design

- **Reactive throughout.** This is a WebFlux application. REST controllers return `Mono<T>` / `Flux<T>`; never introduce blocking calls. `JHipsterBlockHoundIntegration` is on the test classpath to catch blocking on reactive threads.
- **Layer boundaries are enforced by ArchUnit** in `src/test/java/net/jojoaddison/TechnicalStructureTest.java`. Respect the slices: `config` → `web` → `service` → `security` → `repository` → `domain`. Put REST endpoints in `web/rest` and business logic in `service`.
- **Routing to microservices is discovery-driven.** `application.yml` enables `spring.cloud.gateway.discovery.locator` with `lower-case-service-id: true`, which auto-creates a route per Consul-registered service:
  - predicate: `/services/{serviceId.toLowerCase()}/**`
  - filter: `RewritePath` strips the `/services/{serviceId}` prefix
  - `default-filters: [JWTRelay]` applies `JWTRelayGatewayFilterFactory` to every route, which validates the incoming bearer token with `ReactiveJwtDecoder` and relays it downstream.
  - `application-dev.yml` additionally declares one **static** route, `admin-service-route`: `Path=/services/admin-service/**` + `StripPrefix=2` → `http://localhost:5507`. See "Known routing mismatch" below.
- **Authentication and user management live here.** `AuthenticateController` issues JWTs, `AccountResource` / `UserResource` / `PublicUserResource` / `AuthorityResource` manage accounts, and `DomainUserDetailsService` loads users. Downstream services (`hc-admin-service`) are configured with `skipUserManagement` and act purely as OAuth2 resource servers trusting the relayed JWT.
- **Seeding is destructive.** `config/dbmigrations/InitialSetupMigration` sits in the Mongock scan package but is an `ApplicationRunner`, not a `@ChangeUnit` — so it runs on every startup in every profile, and its constructor calls `cleanup()`, which **drops the `Authority` and `User` collections**. Locally created accounts do not survive a restart. It re-seeds `admin` / `Admin@01234` (`ROLE_ADMIN`, `ROLE_USER`), `user` / `User@0123` (`ROLE_USER`), and `operator` / `Operator@1234567` (`ROLE_OPERATOR`), logging the generated passwords at INFO. Treat all three as local-only credentials and do not rely on this class as a migration mechanism.
- **Authorization rules** are centralised in `config/SecurityConfiguration` (reactive `pathMatchers`):
  - public: `/api/authenticate`, `/api/register`, `/api/activate`, `/api/account/reset-password/{init,finish}`, `/management/health/**`, `/management/info`, `/management/prometheus`, `/services/*/management/health/readiness`
  - `ROLE_ADMIN`: `/api/admin/**`, `/management/**`, `/v3/api-docs/**`, `/services/*/v3/api-docs`
  - authenticated: everything else under `/api/**` and `/services/**`
  - Fine-grained checks beyond roles use `security/AccessControl` and `security/Permission`.
- **Kafka / SSE bridge.** `spring.cloud.function.definition: kafkaConsumer;kafkaProducer` with Spring Cloud Stream bindings in `application.yml` (`kafkaConsumer-in-0` on `sse-topic`, group `admin-gateway`). `AdminGatewayKafkaResource` publishes via `StreamBridge`; the consumer fans messages out to SSE clients.
- **Configuration is layered**: `bootstrap.yml` / `bootstrap-prod.yml` handle Consul discovery + config bootstrap; `application.yml` holds shared settings; `application-dev.yml` and `application-prod.yml` set the profile-specific port, Mongo URI, and route config. `application-tls.yml` enables the PKCS12 keystore in `config/tls/`.
- `web/filter/ModifyServersOpenApiFilter` rewrites the `servers` block of aggregated downstream OpenAPI docs so Swagger UI targets the gateway rather than the microservice directly.

### Known routing mismatch

Three different identifiers refer to the same downstream service and they do not agree:

| Where                                                         | Value                        |
| ------------------------------------------------------------- | ---------------------------- |
| Consul registration (`application.yml` of `hc-admin-service`) | `hcadminservice`             |
| Gateway dev static route predicate                            | `/services/admin-service/**` |
| Angular entity services (`hc-admin-dashboard`)                | `services/hc-admin-ms/...`   |

Discovery-based routing therefore serves `/services/hcadminservice/**`, the static dev route serves `/services/admin-service/**`, and the frontend calls neither. If admin entity calls 404 through the gateway, this is the cause — check it before looking elsewhere.

## Code Quality and Style

- Java is 4-space indented and formatted by **Spotless** during Maven builds. Prettier (with `prettier-plugin-java`) covers `.java`, `.yml`, `.json`, `.html`, and `.md`; husky + lint-staged run it pre-commit.
- Use SLF4J for logging; `CRLFLogConverter` is wired in to neutralise CRLF injection in log output. Never log tokens, passwords, or PII.
- Prefer `Optional` over returning null. Handle errors through the JHipster `web/rest/errors` `ExceptionTranslator`, which emits RFC 7807 `ProblemDetail` responses — do not invent a second error format.
- Follow the existing DTO + MapStruct mapper pattern (`service/dto`, `service/mapper`) for anything crossing the REST boundary.
- No static initialisation blocks; wire everything through constructor injection.

## Build and Test

Infrastructure first — the app will not start without Consul:

```bash
npm run docker:consul:up
npm run docker:db:up
npm run docker:kafka:up
npm run services:up          # all of the above
```

Then:

```bash
./mvnw                       # run, dev profile, port 5504
./run-local.sh               # same, but exports SPRING_MONGODB_URI from .env.local first
./mvnw -Pprod clean verify    # production jar, port 5503
./mvnw -Pprod,war clean verify

./mvnw verify                            # unit + integration tests
npm run backend:unit:test                # same, with logging suppressed
./mvnw -q -Dtest=UserResourceIT test                        # single class
./mvnw -q -Dtest=UserResourceIT#createUser test             # single method
npm run backend:nohttp:test              # checkstyle / nohttp
npm run prettier:check | npm run prettier:format
```

If your local MongoDB requires authentication, `cp .env.local.example .env.local`, set `SPRING_MONGODB_URI`, then use `./run-local.sh` (extra Maven args pass through).

### Test conventions

- Unit tests `*Test.java`; integration tests `*IT.java`. `SpringBootTestClassOrderer` runs plain unit tests before context-booting ones.
- `@IntegrationTest` boots the full reactive context. Testcontainers are wired through `src/test/resources/META-INF/spring.factories`: `TestContainersSpringContextCustomizerFactory` supplies the MongoDB replica-set URI, and `KafkaTestContainersSpringContextCustomizerFactory` only starts Kafka for classes annotated `@EmbeddedKafka`.
- **Known breakage:** `src/test/java/net/jojoaddison/config/DevelopmentUsersInitializerTest.java` instantiates `net.jojoaddison.config.DevelopmentUsersInitializer`, which does not exist under `src/main`. Test compilation fails until that class is restored or the test is removed.

## Security Considerations

- All `/api/**` and `/services/**` traffic is authenticated by default; do not weaken the matchers in `SecurityConfiguration` when adding routes.
- The gateway is the trust boundary — it validates the JWT once and relays it downstream via `JWTRelay`. Downstream services must not re-implement login.
- Validate and sanitise input at the edge; rely on Bean Validation on DTOs rather than ad-hoc checks in controllers.
- Passwords are hashed with the configured `PasswordEncoder` (BCrypt); never store or log plaintext credentials.
- TLS is terminated in front of the gateway in production (or via the `tls` profile); the dev profile is plain HTTP on localhost only.
- `SecurityMetersService` records authentication failures — keep new auth paths instrumented so invalid-token and expired-token counters stay meaningful.
- Handle personal data in line with GDPR/HIPAA obligations: minimise what is persisted here and keep it out of logs and traces.

## Performance

- Never block a reactive thread. Wrap unavoidable blocking work in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.
- Paginate list endpoints with the JHipster `PaginationUtil` helpers rather than returning unbounded collections.
- The gateway HTTP client pool is capped at 1000 connections (`spring.cloud.gateway.httpclient.pool.max-connections`); tune there rather than per-route.
- Keep the service stateless so instances scale horizontally behind Consul.
- Actuator + Micrometer metrics are exposed at `/management/prometheus`; Zipkin tracing is configured with 100% sampling in prod (`src/main/docker/zipkin.yml`).

## Technology Stack

- **Java 26** (`java.version` in `pom.xml`; enforcer accepts JDK 17+)
- **Spring Boot 4.0.6**, Spring WebFlux, Spring Cloud Gateway, Spring Cloud Consul
- **Spring Security** OAuth2 resource server + JWT
- **MongoDB** (reactive and blocking drivers), **Mongock** for migrations
- **Apache Kafka** via Spring Cloud Stream
- **SpringDoc OpenAPI** for API docs
- **Maven** (`./mvnw`), Spotless, Checkstyle, jib for container images
- **JUnit 5**, Mockito, AssertJ, Testcontainers (MongoDB, Kafka), ArchUnit, BlockHound
- **Docker Compose** files under `src/main/docker/` for Consul, MongoDB, Kafka, Prometheus/Grafana, Zipkin, Sonar
- **GitHub Actions** for CI

## Key References

- `README.md` — operational workflows and Docker Compose usage
- `.github/copilot-instructions.md` — condensed version of this document
- `pom.xml` — profiles, Java/Maven constraints, plugin setup
- `package.json` — canonical npm script wrappers
- `src/test/java/net/jojoaddison/TechnicalStructureTest.java` — the authoritative layer rules
