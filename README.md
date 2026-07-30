# HC Admin Gateway

The edge service of the Health Connect **admin** stack, and the only component in it that owns users, authorities, and authentication. Built on Spring Cloud Gateway (reactive/WebFlux).

This application was generated using JHipster 8.3.0, you can find documentation and help at [https://www.jhipster.tech/documentation-archive/v8.3.0](https://www.jhipster.tech/documentation-archive/v8.3.0).

This is a "gateway" application intended to be part of a microservice architecture, please refer to the [Doing microservices with JHipster][] page of the documentation for more information.

This application is configured for Service Discovery and Configuration with Consul. On launch, it will refuse to start if it is not able to connect to Consul at [http://localhost:8500](http://localhost:8500). For more information, read our documentation on [Service Discovery and Configuration with Consul][].

## At a glance

|                    |                                                                                         |
| ------------------ | --------------------------------------------------------------------------------------- |
| Java / Spring Boot | 26 / 4.0.6 (enforcer accepts JDK 17+, Maven >= 3.2.5)                                   |
| Stack              | Spring Cloud Gateway, WebFlux — **reactive**, no blocking calls                         |
| Database           | MongoDB, default db `adminGateway`; Mongock scans `net.jojoaddison.config.dbmigrations` |
| Ports              | **5504** (dev profile), **5503** (prod profile)                                         |
| Discovery          | Consul at `localhost:8500`; registers as `adminGateway`                                 |
| Messaging          | Kafka via Spring Cloud Stream (`kafkaConsumer` on `sse-topic`)                          |
| Package root       | `net.jojoaddison`                                                                       |

### Place in the stack

```
hc-admin-dashboard (Angular, :4200)
  └─ hc-admin-gateway (:5504 dev / :5503 prod)   ← this repo
       └─ hc-admin-service (:5507 dev / :8080 prod)
```

### Routing

`spring.cloud.gateway.discovery.locator` is enabled with `lower-case-service-id: true`, so every Consul-registered service is automatically published at `/services/{serviceId-lowercased}/**` with the prefix rewritten away. `default-filters: [JWTRelay]` applies `JWTRelayGatewayFilterFactory` to every route, validating the bearer token and relaying it downstream. `application-dev.yml` adds one static route: `/services/admin-service/**` → `http://localhost:5507` with `StripPrefix=2`.

**Known routing mismatch:** `hc-admin-service` registers as `hcadminservice`, the static dev route is `/services/admin-service/**`, and the Angular dashboard calls `/services/hc-admin-ms/...`. None of the three agree — check this first when admin entity calls 404 through the gateway.

## Project Structure

Node is required for generation and recommended for development. `package.json` is always generated for a better development experience with prettier, commit hooks, scripts and so on.

In the project root, JHipster generates configuration files for tools like git, prettier, eslint, husky, and others that are well known and you can find references in the web.

`/src/*` structure follows default Java structure.

- `.yo-rc.json` - Yeoman configuration file
  JHipster configuration is stored in this file at `generator-jhipster` key. You may find `generator-jhipster-*` for specific blueprints configuration.
- `.yo-resolve` (optional) - Yeoman conflict resolver
  Allows to use a specific action when conflicts are found skipping prompts for files that matches a pattern. Each line should match `[pattern] [action]` with pattern been a [Minimatch](https://github.com/isaacs/minimatch#minimatch) pattern and action been one of skip (default if omitted) or force. Lines starting with `#` are considered comments and are ignored.
- `.jhipster/*.json` - JHipster entity configuration files
- `/src/main/docker` - Docker configurations for the application and services that the application depends on

## Development

To start your application in the dev profile, run:

```
./mvnw
```

### Seeded accounts (dev / test)

`config/dbmigrations/InitialSetupMigration` seeds local accounts from `src/main/resources/hc-admin-gw-data.json`, which is the **single source of truth** for them — ids, logins, emails, passwords and authorities all come from that file.

Under the `dev` profile:

| Login      | Password           | Id               | Authorities                  |
| ---------- | ------------------ | ---------------- | ---------------------------- |
| `admin`    | `Admin@01234`      | `a0eebc99-…-a11` | `ROLE_ADMIN`, `ROLE_USER`    |
| `user`     | `User@0123`        | `a0eebc99-…-a13` | `ROLE_USER`                  |
| `operator` | `Operator@1234567` | `a0eebc99-…-a12` | `ROLE_OPERATOR`, `ROLE_USER` |

Under the `test` profile it instead seeds three edge-case fixtures — `deactivated` (not activated), `noauth` (no authorities) and `malformed` (blank/null names). These declare no password, so their login is used as one.

> Seeding is **additive**: an account is created only when no user with that login exists, so accounts you create through the API survive restarts. Nothing is ever dropped and passwords are never logged.
>
> The ids are a cross-service contract — `hc-admin-service`'s seed data references the admin and operator ids as `managedBy` / `createdBy` values. Changing an id in the JSON without updating that seed data breaks referential integrity between the two services.

### Bootstrapping the first administrator (production)

`InitialSetupMigration` is deliberately `@Profile({dev, test})`: its credentials are fixed and publicly known, so they must never reach production. A fresh production database therefore has no account to log in with.

`config/AdminBootstrapInitializer` fills that gap without shipping any default credentials. It runs in every profile but does nothing unless a password is configured, and nothing if the account already exists:

```bash
export GATEWAY_ADMIN_PASSWORD='<a real secret>'
```

On the next start it creates a single activated administrator with `ROLE_ADMIN` and `ROLE_USER`. Leave the variable set — the bootstrap is idempotent and will skip once the account exists. Optional overrides:

| Property                 | Env var                  | Default                        |
| ------------------------ | ------------------------ | ------------------------------ |
| `gateway.admin.password` | `GATEWAY_ADMIN_PASSWORD` | _(unset — bootstrap disabled)_ |
| `gateway.admin.login`    | `GATEWAY_ADMIN_LOGIN`    | `admin`                        |
| `gateway.admin.email`    | `GATEWAY_ADMIN_EMAIL`    | `admin@localhost`              |

If the variable is unset the gateway starts normally and logs nothing at INFO; check for the debug message `gateway.admin.password is not set; skipping administrator bootstrap` if you expected an account and did not get one.

If your local MongoDB requires authentication, use the local env + launcher workflow so you do not need to retype connection details each run:

```bash
cp .env.local.example .env.local
./run-local.sh
```

The launcher reads `SPRING_MONGODB_URI` from `.env.local` and exports it before starting Maven. You can also pass Maven arguments through:

```bash
./run-local.sh -ntp -DskipTests spring-boot:run
```

For further instructions on how to develop with JHipster, have a look at [Using JHipster in development][].

## Building for production

### Packaging as jar

To build the final jar and optimize the HC Admin Gateway application for production, run:

```
./mvnw -Pprod clean verify
```

To ensure everything worked, run:

```
java -jar target/*.jar
```

Refer to [Using JHipster in production][] for more details.

### Packaging as war

To package your application as a war in order to deploy it to an application server, run:

```
./mvnw -Pprod,war clean verify
```

### JHipster Control Center

JHipster Control Center can help you manage and control your application(s). You can start a local control center server (accessible on http://localhost:7419) with:

```
docker compose -f src/main/docker/jhipster-control-center.yml up
```

## Testing

### Spring Boot tests

To launch your application's tests, run:

```
./mvnw verify
```

Run a single test class or method:

```bash
./mvnw -q -Dtest=UserResourceIT test
./mvnw -q -Dtest=UserResourceIT#createUser test
```

Conventions:

- Unit tests are `*Test.java`; integration tests are `*IT.java`. `SpringBootTestClassOrderer` runs plain unit tests before context-booting ones.
- Testcontainers are wired through `src/test/resources/META-INF/spring.factories`: MongoDB is always provisioned, Kafka only for classes annotated `@EmbeddedKafka`. Docker must be running.
- `TechnicalStructureTest` enforces package-layer boundaries with ArchUnit — a new class in the wrong slice fails the build.
- `JHipsterBlockHoundIntegration` detects blocking calls on reactive threads.

Seeding is covered by `src/test/java/net/jojoaddison/config/dbmigrations/InitialSetupMigrationTest.java`, which asserts create/skip behaviour, the three stable ids, the derived passwords, and — as regression guards — that no collection is dropped and existing authorities are reused.

## Others

### Code quality using Sonar

Sonar is used to analyse code quality. You can start a local Sonar server (accessible on http://localhost:9001) with:

```
docker compose -f src/main/docker/sonar.yml up -d
```

Note: we have turned off forced authentication redirect for UI in [src/main/docker/sonar.yml](src/main/docker/sonar.yml) for out of the box experience while trying out SonarQube, for real use cases turn it back on.

You can run a Sonar analysis with using the [sonar-scanner](https://docs.sonarqube.org/display/SCAN/Analyzing+with+SonarQube+Scanner) or by using the maven plugin.

Then, run a Sonar analysis:

```
./mvnw -Pprod clean verify sonar:sonar -Dsonar.login=admin -Dsonar.password=admin
```

If you need to re-run the Sonar phase, please be sure to specify at least the `initialize` phase since Sonar properties are loaded from the sonar-project.properties file.

```
./mvnw initialize sonar:sonar -Dsonar.login=admin -Dsonar.password=admin
```

Additionally, Instead of passing `sonar.password` and `sonar.login` as CLI arguments, these parameters can be configured from [sonar-project.properties](sonar-project.properties) as shown below:

```
sonar.login=admin
sonar.password=admin
```

For more information, refer to the [Code quality page][].

### Using Docker to simplify development (optional)

You can use Docker to improve your JHipster development experience. A number of docker-compose configuration are available in the [src/main/docker](src/main/docker) folder to launch required third party services.

For example, to start a MongoDB database in a docker container, run:

```bash
# One-time: create mongo.env from the example template and adjust credentials
cp src/main/docker/mongo-env.example src/main/docker/mongo.env

docker compose -f src/main/docker/mongodb.yml up -d
```

`mongodb.yml` uses `mongo:8` and reads credentials from `src/main/docker/mongo.env` (gitignored).
The example template sets `MONGO_INITDB_ROOT_USERNAME=admin`, `MONGO_INITDB_ROOT_PASSWORD=Admin123!`, and `MONGO_INITDB_DATABASE=adminGateway` — update `mongo.env` to suit your environment.

The container port is bound to `127.0.0.1:27017` so it is not exposed outside the local machine.

Because authentication is enabled, set matching credentials in `.env.local` (see `.env.local.example`) before starting the app:

```bash
./run-local.sh
```

To stop the container and remove it, run:

```bash
docker compose -f src/main/docker/mongodb.yml down
```

#### MongoDB cluster (sharded, optional)

A sharded cluster configuration is also available for testing replication scenarios:

```bash
docker compose -f src/main/docker/mongodb-cluster.yml up -d
```

This uses a custom `MongoDB.Dockerfile` (based on `mongo:7.0.6`) with `mongodb/scripts/init_replicaset.js` to initialise the replica set automatically.

You can also fully dockerize your application and all the services that it depends on.
To achieve this, first build a docker image of your app by running:

```
npm run java:docker
```

Or build a arm64 docker image when using an arm64 processor os like MacOS with M1 processor family running:

```
npm run java:docker:arm64
```

Then run:

```
docker compose -f src/main/docker/app.yml up -d
```

When running Docker Desktop on MacOS Big Sur or later, consider enabling experimental `Use the new Virtualization framework` for better processing performance ([disk access performance is worse](https://github.com/docker/roadmap/issues/7)).

For more information refer to [Using Docker and Docker-Compose][], this page also contains information on the docker-compose sub-generator (`jhipster docker-compose`), which is able to generate docker configurations for one or several JHipster applications.

## Troubleshooting

### MongoDB: `Command listIndexes requires authentication` on startup

Mongock runs index management at startup and fails immediately when the MongoDB instance requires authentication but no credentials are supplied.

**Fix:** use the local env launcher instead of `./mvnw` directly.

```bash
# One-time setup
cp .env.local.example .env.local
# Edit .env.local and set SPRING_MONGODB_URI to your local credentials
./run-local.sh
```

### `.env.local` not found

```
Missing /path/to/hc-admin-gateway/.env.local.
```

Run `cp .env.local.example .env.local` and update `SPRING_MONGODB_URI` with your credentials.

### `SPRING_MONGODB_URI is not set`

The line in `.env.local` must start with `SPRING_MONGODB_URI=` (no spaces, no quotes around the value). Example:

```
SPRING_MONGODB_URI=mongodb://admin:password@localhost:27017/adminGateway?authSource=admin&waitQueueMultiple=1000
```

### Wrong `authSource`

If MongoDB reports error 13 (Unauthorized) even with credentials, verify the `authSource` parameter matches the database where the user was created. The default root user created by `MONGO_INITDB_ROOT_USERNAME` is stored in the `admin` database, so use `authSource=admin`.

### Consul not reachable

```
Application run failed … Connection refused … localhost:8500
```

Start Consul before launching the app:

```bash
docker compose -f src/main/docker/consul.yml up -d
# or
npm run docker:consul:up
```

### All dependent services at once

```bash
npm run services:up
```

This starts Consul, MongoDB, and Kafka together.

### Login fails with credentials that worked before

The seeded passwords are **not** the same as the logins — use `Admin@01234`, `User@0123`, `Operator@1234567` (see [Seeded accounts](#seeded-accounts-dev--test)). If a user you created through the API is missing, check you are not on a fresh database; seeding itself no longer deletes anything.

### Requests to a microservice return 404 through the gateway

Check which path the caller is using against what the gateway actually publishes:

- discovery locator serves `/services/{consul-service-name-lowercased}/**` — for `hc-admin-service` that is `/services/hcadminservice/**`
- the dev profile also defines a static `/services/admin-service/**` route
- the Angular dashboard currently calls `/services/hc-admin-ms/...`, which matches neither

Confirm the service is registered in Consul at http://localhost:8500 before assuming a gateway bug.

---

## Continuous Integration (optional)

To configure CI for your project, run the ci-cd sub-generator (`jhipster ci-cd`), this will let you generate configuration files for a number of Continuous Integration systems. Consult the [Setting up Continuous Integration][] page for more information.

[JHipster Homepage and latest documentation]: https://www.jhipster.tech
[JHipster 8.3.0 archive]: https://www.jhipster.tech/documentation-archive/v8.3.0
[Doing microservices with JHipster]: https://www.jhipster.tech/documentation-archive/v8.3.0/microservices-architecture/
[Using JHipster in development]: https://www.jhipster.tech/documentation-archive/v8.3.0/development/
[Service Discovery and Configuration with Consul]: https://www.jhipster.tech/documentation-archive/v8.3.0/microservices-architecture/#consul
[Using Docker and Docker-Compose]: https://www.jhipster.tech/documentation-archive/v8.3.0/docker-compose
[Using JHipster in production]: https://www.jhipster.tech/documentation-archive/v8.3.0/production/
[Running tests page]: https://www.jhipster.tech/documentation-archive/v8.3.0/running-tests/
[Code quality page]: https://www.jhipster.tech/documentation-archive/v8.3.0/code-quality/
[Setting up Continuous Integration]: https://www.jhipster.tech/documentation-archive/v8.3.0/setting-up-ci/
[Node.js]: https://nodejs.org/
[NPM]: https://www.npmjs.com/
