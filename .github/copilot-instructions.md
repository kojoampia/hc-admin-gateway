# Project Guidelines

## ⚠ First: confirm which repository you are in

This product is several independent repositories sitting side by side in a plain directory, so a checkout that looks right can be the
wrong one. **Run `git remote -v` before drawing any conclusion from `git`** — before `git log`, `git branch` or `git status`, not after.
It must name the repository you were asked to change; if it names another, **stop and report rather than editing the shared checkout**.

The wrong answer is not an error. It is a plausible repository with unfamiliar history, and a session's working directory can also move
after a correct start. So a `git log` that surprises you — an unrecognised `main`, a branch of yours that is suddenly missing — means
find out which repository you are reading. It does not mean your work was lost.

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
- Downstream routing is discovery-driven: `spring.cloud.gateway.server.webflux.discovery.locator` publishes `/services/{serviceId-lowercased}/**` per Consul-registered service, and `default-filters: [JWTRelay]` relays the bearer token. `application-dev.yml` adds one static route, `/services/hcadminservice/**` → `http://localhost:5507`.
  - ⚠ **Note the `server.webflux` level.** Spring Cloud Gateway moved its configuration there in the 2025.x release train, and anything left directly under `spring.cloud.gateway.*` still binds as a property while nothing reads it — so a mistake here is silent. `config/application.yml:117-121` records that this block was one level up, which left both the `JWTRelay` default filter and the discovery locator inert.
- Authorization rules live in `config/SecurityConfiguration`; see `AGENTS.md` for the public/admin/authenticated matcher breakdown.
  - ⚠ One rule is **not** in that breakdown: `/api/auth-activity/**` is `hasAuthority(ROLE_ADMIN)` **alone** (`SecurityConfiguration.java:99`), and it must stay **above** the blanket `/api/**` → `authenticated()` at `:100` or it is never evaluated. The reasoning is at `:94-98`.

### Service naming

`hc-admin-service` registers in Consul as `hcadminservice`, so the discovery locator publishes `/services/hcadminservice/**` and the Angular console — `app/`, `hc-admin-app` — calls exactly that. The static dev route in `application-dev.yml:53-56` carries **the same name** and is a convenience, not a second contract. If a `/services/...` call 404s, check the Consul catalogue first.

⚠ **`admin-service` is not a name anything in this system serves.** That route said `/services/admin-service/**` until 2026-09-01, and the mismatch 404ed every entity call through this gateway; `application-dev.yml:49-52` carries the reason in a comment beside the route. This file said `admin-service` until 2026-09-18, which is the same correction reaching the code and missing the document — the way `AGENTS.md` had it until 2026-09-10. If you meet the name in git history, that is what it was.

## Build And Test

- Development run:
  - `./mvnw`
  - or `npm run app:start` (this repo does have a `pom.xml`, unlike the Angular console `app/`, which has none — its image is built from `deploy/docker/app.Dockerfile`)
- Build for production:
  - `./mvnw -Pprod clean verify`
  - `./mvnw -Pprod,war clean verify`
- Unit/integration tests:
  - `./mvnw verify`
  - `npm run backend:unit:test`
  - Single integration test class: `./mvnw -q verify -Dit.test=UserResourceIT`
  - Single integration test method: `./mvnw -q verify -Dit.test=UserResourceIT#createUser`
  - Single unit test class: `./mvnw -q -Dtest=GatewayResourceTest test`
  - **`pom.xml:777-778`'s `**/*IT*` exclude does not stop `-Dtest=SomeIT test` from selecting the IT.** Naming a test empties the exclude list: in `maven-surefire-common-3.5.6` (pinned at `pom.xml:56`) `getExcludeList(boolean)` returns `Collections.emptyList()` whenever `isSpecificTestSpecified()` — itself `isNotBlank(getTest())` — is true, branching over `getExcludes()` entirely. Prefer `-Dit.test=` with `verify` anyway: that runs the class under failsafe, which is where the integration-test configuration lives (`pom.xml:686-696`, note its own `argLine` at `:696` against surefire's at `:780`).
  - ⚠ **The trap is the opposite one — a bare `./mvnw test` runs no integration test at all.** With nothing named, the excludes do apply and every `*IT`/`*IntTest` is skipped silently on a green build. Use `verify` when you mean to exercise them.
- Quality checks:
  - `npm run backend:nohttp:test`
  - `./mvnw -Pprod clean verify sonar:sonar -Dsonar.login=admin -Dsonar.password=admin`
- Seeding coverage: `InitialSetupMigrationTest` and `AdminBootstrapInitializerTest`. Local accounts come from `src/main/resources/hc-admin-gw-data.json` (edit the JSON, not the Java). Do not reintroduce a collection drop or code-derived credentials — `InitialSetupMigrationTest.shouldNeverDropCollections` (`:196`) and `shouldTakePasswordsFromTheJsonRatherThanDerivingThem` (`:112`) guard those two. ⚠ **Password logging is a rule with no test behind it**: no seeding test captures log output, so that one is held by reading the code rather than by a gate. `AdminBootstrapInitializer` handles the production first-admin and must never gain a default password — `AdminBootstrapInitializerTest` asserts it stays inert when the property is unset, blank or null (`:41-62`).

## Conventions

- `pom.xml:20` sets `java.version` to **25**; the enforcer accepts JDK 17+ (`:675-678`) and Maven >= **3.9.9** (`:19`).
- Use profile-driven runs/builds (`dev` default, `prod` for release artifacts).
- Integration test naming follows Maven defaults:
  - Unit tests: `*Test.java`
  - Integration tests: `*IT.java` or `*IntTest.java`
- Prefer existing npm scripts in `package.json` when they exist instead of ad-hoc shell commands.

## Environment Prerequisites

- Consul is required at `http://localhost:8500`; app startup fails without it.
- MongoDB is a required dependency for local development.
- ⚠ **Kafka is on the classpath and this gateway binds nothing to it.** `config/application.yml:216` sets `spring.cloud.function.definition: ''` and no `spring.cloud.stream.bindings` block exists in any config file, so no binding is created and nothing connects to a broker. That emptiness is a decision, argued at length at `application.yml:188-215`; `broker/OutboundEventPublisher` is kept as the seam a future publisher must go through. The `kafka.yml` helper below stays for that day.
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
