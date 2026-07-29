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

### ⚠ The blueprint is inert

`src/main/resources/hc-admin-gw-data.json` exists and matches the structure below, but **no Java code reads it** — grepping for `hc-admin-gw-data` across `src/` returns nothing. All user seeding is done in code by `config/dbmigrations/InitialSetupMigration`.

### Blueprint contents

```json
{
  "dev": [
    {
      "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
      "login": "admin",
      "password": "!!Admin1234$",
      "authorities": ["ROLE_ADMIN", "ROLE_USER"]
    },
    {
      "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12",
      "login": "operator",
      "password": "!!Operator1234$",
      "authorities": ["ROLE_OPERATOR", "ROLE_USER"]
    },
    { "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13", "login": "user", "password": "!!User1234$", "authorities": ["ROLE_USER"] }
  ],
  "test": [
    { "id": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b11", "login": "deactivated", "activated": false, "authorities": ["ROLE_USER"] },
    { "id": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b12", "login": "noauth", "authorities": [] },
    { "id": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b13", "login": "malformed", "firstName": "", "lastName": null, "authorities": ["ROLE_USER"] }
  ]
}
```

Each user carries `id`, `login`, `email`, `firstName`, `lastName`, `activated`, and `authorities`; `dev` users additionally carry `password`.

### Blueprint vs. reality

`InitialSetupMigration` is the only thing that actually creates users, and it differs in every field that matters:

|                                                             | Blueprint (`hc-admin-gw-data.json`)  | Actual (`InitialSetupMigration`)    |
| ----------------------------------------------------------- | ------------------------------------ | ----------------------------------- |
| Loaded by                                                   | nothing                              | runs on every startup, all profiles |
| `admin` id / password                                       | `a0eebc99-…-a11` / `!!Admin1234$`    | `user-1` / `Admin@01234`            |
| `user` id / password                                        | `a0eebc99-…-a13` / `!!User1234$`     | `user-2` / `User@0123`              |
| `operator` id / password                                    | `a0eebc99-…-a12` / `!!Operator1234$` | random UUID / `Operator@1234567`    |
| `operator` authorities                                      | `ROLE_OPERATOR`, `ROLE_USER`         | `ROLE_OPERATOR` only                |
| `test`-profile users (`deactivated`, `noauth`, `malformed`) | defined here                         | never created                       |

Passwords in the code path are derived from the login — capitalised login + `@` + ascending digits — and are logged at INFO on startup.

### ⚠ Two consequences worth knowing

- **`InitialSetupMigration` drops the `User` and `Authority` collections on every boot.** Its constructor calls `cleanup()`, and it is an `ApplicationRunner` rather than a Mongock `@ChangeUnit`, so the changelog does not guard it from re-running. Accounts created through the API do not survive a restart.
- **Cross-service `managedBy` references are dangling.** `hc-admin-service`'s seed data was written to reference `a0eebc99-…-a11` and `…a12` for relational integrity with the gateway. Those UUIDs do not exist in any running database — the real admin and user ids are `user-1` and `user-2`.

To make this blueprint real, either write a loader for `hc-admin-gw-data.json` or reconcile `InitialSetupMigration` with the IDs above. Neither has been done.

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
