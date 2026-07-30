# Project Guidelines

## Code Style

- Use Maven Wrapper for Java tasks: `./mvnw`.
- Java uses 4-space indentation and is formatted by Spotless during Maven builds.
- JSON/YAML/HTML/Markdown formatting follows Prettier rules in `.prettierrc` and `.editorconfig`.
- Preferred formatting commands:
  - `npm run prettier:check`
  - `npm run prettier:format`

## Architecture

- This is a JHipster 8.3.0 Spring Boot reactive gateway (`net.jojoaddison`) with MongoDB (Mongock migrations) + Kafka. Ports: **5504** dev, **5503** prod.
- All REST controllers should use Spring WebFlux return types (`Mono<T>`, `Flux<T>`); avoid blocking patterns.
- Keep layer boundaries aligned with ArchUnit rules in `src/test/java/net/jojoaddison/TechnicalStructureTest.java`:
  - `config`
  - `web` (REST controllers, filters)
  - `service` (optional)
  - `security`
  - `repository` (optional)
  - `domain`
- Put REST endpoints in `src/main/java/net/jojoaddison/web/rest` and business logic in `src/main/java/net/jojoaddison/service`.
- This gateway owns authentication and user management for the admin stack (`AuthenticateController`, `UserResource`, `AccountResource`, Mongock `InitialSetupMigration`). Downstream services trust the relayed JWT and must not re-implement login.
- Downstream routing is discovery-driven: `spring.cloud.gateway.discovery.locator` publishes `/services/{serviceId-lowercased}/**` per Consul-registered service, and `default-filters: [JWTRelay]` relays the bearer token. `application-dev.yml` adds one static route, `/services/admin-service/**` → `http://localhost:5507`.
- Authorization rules live in `config/SecurityConfiguration`; see `AGENTS.md` for the full public/admin/authenticated matcher breakdown.

### Service naming

`hc-admin-service` registers in Consul as `hcadminservice`, so the discovery locator publishes `/services/hcadminservice/**` and the Angular dashboard calls exactly that. The static `/services/admin-service/**` dev route is a convenience, not a second contract. If a `/services/...` call 404s, check the Consul catalogue first.

## Build And Test

- Development run:
  - `./mvnw`
  - or `npm run app:start` (this repo does have a `pom.xml`, unlike the dashboard)
- Build for production:
  - `./mvnw -Pprod clean verify`
  - `./mvnw -Pprod,war clean verify`
- Unit/integration tests:
  - `./mvnw verify`
  - `npm run backend:unit:test`
  - Single class: `./mvnw -q -Dtest=UserResourceIT test`
  - Single method: `./mvnw -q -Dtest=UserResourceIT#createUser test`
- Quality checks:
  - `npm run backend:nohttp:test`
  - `./mvnw -Pprod clean verify sonar:sonar -Dsonar.login=admin -Dsonar.password=admin`
- Seeding coverage: `InitialSetupMigrationTest` and `AdminBootstrapInitializerTest`. Local accounts come from `src/main/resources/hc-admin-gw-data.json` (edit the JSON, not the Java). Do not reintroduce a collection drop, password logging, or code-derived credentials — those tests guard all three. `AdminBootstrapInitializer` handles the production first-admin and must never gain a default password.

## Conventions

- `pom.xml` sets `java.version` to **26**; the enforcer accepts JDK 17+ and Maven >= 3.2.5.
- Use profile-driven runs/builds (`dev` default, `prod` for release artifacts).
- Integration test naming follows Maven defaults:
  - Unit tests: `*Test.java`
  - Integration tests: `*IT.java` or `*IntTest.java`
- Prefer existing npm scripts in `package.json` when they exist instead of ad-hoc shell commands.

## Environment Prerequisites

- Consul is required at `http://localhost:8500`; app startup fails without it.
- MongoDB and Kafka are required dependencies for local development.
- Useful service helpers:
  - `npm run docker:consul:up`
  - `npm run docker:db:up`
  - `npm run docker:kafka:up`
  - `npm run services:up`

## Key References

- See `AGENTS.md` for the full architecture, security matcher rules, and stack breakdown.
- See `README.md` for operational workflows and Docker compose usage.
- See `pom.xml` for profiles, Java/Maven constraints, and test plugin setup.
- See `package.json` for standard local commands used by this repository.
