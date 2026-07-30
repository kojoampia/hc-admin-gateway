# hc-admin-gateway — Design Plans & Blueprints

Consolidated record of the data blueprints written for this gateway. It replaces `hc-admin-gw-data.md` and `hc-admin-ms-data.md`, which were merged here and deleted.

**These are historical plans, not specifications to execute.** Both blueprints have been superseded by code, and in one case the blueprint was never wired up at all. Where blueprint and code disagree, the code is the authority. Do not re-run any of this as a prompt.

Operational docs live elsewhere and are still current: [`README.md`](README.md) for setup and commands, [`AGENTS.md`](AGENTS.md) and [`.github/copilot-instructions.md`](.github/copilot-instructions.md) for working conventions.

---

## Contents

1. [User identity blueprint](#1-user-identity-blueprint) — and why the real seeded accounts differ
2. [Microservice routing contract](#2-microservice-routing-contract) — the canonical `/services/hcadminservice/...` path
3. [Frontend consumption guidance](#3-frontend-consumption-guidance)

---

## 1. User identity blueprint

Originally a blueprint for `hc-admin-gw-data.json`, covering user identity, authentication, and authorization for `dev` and `test` profiles.

### This blueprint is live

`src/main/resources/hc-admin-gw-data.json` is read at startup by `config/dbmigrations/InitialSetupMigration` under the `dev` and `test` profiles. It was inert for a period — accounts were hardcoded in Java and free to drift from it — which is what the rest of this section records.

### File contents

```json
{
  "dev": [
    {
      "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
      "login": "admin",
      "password": "Admin@01234",
      "authorities": ["ROLE_ADMIN", "ROLE_USER"]
    },
    {
      "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12",
      "login": "operator",
      "password": "Operator@1234567",
      "authorities": ["ROLE_OPERATOR", "ROLE_USER"]
    },
    { "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13", "login": "user", "password": "User@0123", "authorities": ["ROLE_USER"] }
  ],
  "test": [
    { "id": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b11", "login": "deactivated", "activated": false, "authorities": ["ROLE_USER"] },
    { "id": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b12", "login": "noauth", "authorities": [] },
    { "id": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b13", "login": "malformed", "firstName": "", "lastName": null, "authorities": ["ROLE_USER"] }
  ]
}
```

Each user carries `id`, `login`, `email`, `firstName`, `lastName`, `activated`, and `authorities`; `dev` users additionally carry `password`.

### The blueprint is now live

`hc-admin-gw-data.json` is no longer inert — `InitialSetupMigration` reads it as the **single source of truth** for local accounts. Ids, logins, emails, passwords and authorities all come from the file; the Java constructs no users of its own. Edit the JSON to change an account.

|                                                        | Blueprint        | Delivered                                               |
| ------------------------------------------------------ | ---------------- | ------------------------------------------------------- |
| Loaded by                                              | nothing          | `InitialSetupMigration`, under `dev` / `test`           |
| `admin`                                                | `a0eebc99-…-a11` | same, `Admin@01234`, `ROLE_ADMIN` + `ROLE_USER`         |
| `user`                                                 | `a0eebc99-…-a13` | same, `User@0123`, `ROLE_USER`                          |
| `operator`                                             | `a0eebc99-…-a12` | same, `Operator@1234567`, `ROLE_OPERATOR` + `ROLE_USER` |
| `test` fixtures (`deactivated`, `noauth`, `malformed`) | defined here     | seeded under the `test` profile                         |

The file's original passwords (`!!Admin1234$` and friends) were replaced with the values already in circulation, so adopting it as the source of truth changed no working credential. Fixtures declare no password and fall back to their login.

The operator now gets `ROLE_USER` in addition to `ROLE_OPERATOR`, per the blueprint — the previous hardcoded path granted only `ROLE_OPERATOR`.

### Fixed

- **Destructive seeding.** The constructor called a `cleanup()` that dropped the `User` and `Authority` collections on every start, so accounts created through the API were destroyed on the next restart. Removed — seeding is additive, guarded by an existence check per login. `InitialSetupMigrationTest.shouldNeverDropCollections` locks this in.
- **Passwords logged in plaintext.** Seed users were logged at INFO as `Creating user with login: {} and password: {}`. Only the login is logged now.
- **Unstable and dangling ids.** Users were seeded as `user-1`, `user-2`, and a fresh `UUID.randomUUID()` for the operator, so the `managedBy` / `createdBy` references in `hc-admin-service`'s seed data resolved to nothing and the operator id changed every startup. Ids now come from the blueprint and match.
- **Dead blueprint.** Nothing read `hc-admin-gw-data.json`; accounts were hardcoded in Java, free to drift from the documented contract. The file is now the source of truth, and `test`-profile fixtures are created for the first time.
- **No production bootstrap.** `InitialSetupMigration` became `@Profile({dev, test})`, leaving a fresh production database with no way to log in. `config/AdminBootstrapInitializer` now runs in every profile, creating one administrator when `gateway.admin.password` (env `GATEWAY_ADMIN_PASSWORD`) is set and no such account exists. It ships no default credential and is idempotent — see the README for the full property list.
- **Orphaned test.** `DevelopmentUsersInitializerTest` referenced `DevelopmentUsersInitializer`, removed when seeding was consolidated here, which broke test compilation. Its create/skip coverage now lives in `InitialSetupMigrationTest`.

---

## 2. Microservice routing contract

This gateway-side copy of the `hc-admin-ms-data.json` brief is the document that records the **correct** downstream path.

### The canonical path is `/services/hcadminservice/api/...`

It matches the Consul `service-name` of `hc-admin-service`, and therefore the route the gateway's discovery locator publishes (`spring.cloud.gateway.discovery.locator` with `lower-case-service-id: true`).

Three spellings are currently in circulation and only one resolves:

| Where                                            | Value                         | Resolves?                                          |
| ------------------------------------------------ | ----------------------------- | -------------------------------------------------- |
| Consul registration / discovery locator          | `/services/hcadminservice/**` | ✅ yes                                             |
| Gateway static dev route (`application-dev.yml`) | `/services/admin-service/**`  | ⚠ dev profile only, hardcoded to `localhost:5507` |
| Angular dashboard entity services                | `/services/hc-admin-ms/...`   | ❌ nothing serves this                             |

When reconciling the mismatch, **this document has the right name**. The fix belongs in the frontend's `getEndpointFor(...)` calls.

### Data model note

The brief modelled three entity types — Healthcare Facilities, System Audits, and Provider Metrics — with an `entityId` / `status` / `createdAt` / `managedBy` / `payload` envelope. The delivered `hc-admin-ms-data.json` instead covers eleven collections (`addresses`, `contacts`, `facilities`, `audits`, `organisations`, `persons`, `teams`, `profiles`, `dutyRosters`, `pricingPlans`, `systemCatalogs`) using real domain field names. **There is no `metrics` collection.** See `hc-admin-service`'s `admin-api.md` for the delivered shape and for the bug that currently prevents the seed data loading at all.

---

## 3. Frontend consumption guidance

The blueprints included guidance for the Angular dashboard. Restated here with paths corrected; the frontend repo's own docs are the working reference.

**Authentication.** Log in by POSTing credentials to `/api/authenticate`. The currently authenticated user is available from `/api/account`. Fetching all users (`/api/users`) requires `ROLE_ADMIN`.

**Domain data.** Route all microservice calls through the gateway so its routing and security filters apply:

```typescript
@Injectable({ providedIn: 'root' })
export class FacilityService {
  private apiUrl = '/services/hcadminservice/api/facilities';

  constructor(private http: HttpClient) {}

  getFacilities(): Observable<Facility[]> {
    return this.http.get<Facility[]>(this.apiUrl);
  }

  getFacilityById(id: string): Observable<Facility> {
    return this.http.get<Facility>(`${this.apiUrl}/${id}`);
  }
}
```

In practice the dashboard builds this URL through `ApplicationConfigService.getEndpointFor('api/facilities', 'hcadminservice')` rather than hardcoding it — that indirection is the convention there, and the second argument is the value that must be corrected from `'hc-admin-ms'`.

The blueprint's broader intent — eliminate hardcoded mock data from frontend state stores and fetch through `HttpClient` — is tracked in the dashboard's own consolidated `admin-web.md`.
