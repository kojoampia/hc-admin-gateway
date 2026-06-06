# Gateway Data Blueprint: `hc-admin-gw-data.json`

This document provides a data blueprint for the `hc-admin-gw` service, covering user identity, authentication, and authorization. The following JSON structure should be used to populate the backend database for `dev` and `test` profiles to ensure consistent and testable user data.

## Technical Summary: Angular Frontend Consumption

The Angular `hc-admin-db` frontend should eliminate all hardcoded user mock data from its state stores and services. Instead, it must rely on `HttpClient` to interact with the gateway's REST API.

**Authentication:** The `AuthService` or similar should handle user login by POSTing credentials to `/api/authenticate`.

**User Management:** A `UserService` should be implemented to fetch user data.

- **Fetching all users (ADMIN only):**

  ```typescript
  import { HttpClient } from '@angular/common/http';
  import { Injectable } from '@angular/core';
  import { Observable } from 'rxjs';
  import { User } from './user.model'; // Define a User interface matching the schema

  @Injectable({ providedIn: 'root' })
  export class UserService {
    private apiUrl = '/api/users';

    constructor(private http: HttpClient) {}

    getUsers(): Observable<User[]> {
      return this.http.get<User[]>(this.apiUrl);
    }
  }
  ```

- **Accessing Account Data:** The currently authenticated user's data can be retrieved from `/api/account`.

By externalizing user data to the backend, the frontend remains decoupled and can seamlessly switch between mock and real data sources based on the active Spring profile.

## JSON Data: `hc-admin-gw-data.json`

```json
{
  "dev": [
    {
      "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
      "login": "admin",
      "email": "admin@localhost",
      "firstName": "Admin",
      "lastName": "User",
      "activated": true,
      "authorities": ["ROLE_ADMIN", "ROLE_USER"]
    },
    {
      "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12",
      "login": "operator",
      "email": "operator@localhost",
      "firstName": "Operator",
      "lastName": "User",
      "activated": true,
      "authorities": ["ROLE_OPERATOR", "ROLE_USER"]
    },
    {
      "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13",
      "login": "user",
      "email": "user@localhost",
      "firstName": "Normal",
      "lastName": "User",
      "activated": true,
      "authorities": ["ROLE_USER"]
    }
  ],
  "test": [
    {
      "id": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b11",
      "login": "deactivated",
      "email": "deactivated@localhost",
      "firstName": "Deactivated",
      "lastName": "User",
      "activated": false,
      "authorities": ["ROLE_USER"]
    },
    {
      "id": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b12",
      "login": "noauth",
      "email": "noauth@localhost",
      "firstName": "No",
      "lastName": "Auth",
      "activated": true,
      "authorities": []
    },
    {
      "id": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b13",
      "login": "malformed",
      "email": "malformed@localhost",
      "firstName": "",
      "lastName": null,
      "activated": true,
      "authorities": ["ROLE_USER"]
    }
  ]
}
```
