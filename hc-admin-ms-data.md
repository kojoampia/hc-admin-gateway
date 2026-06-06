# Service Data Blueprint: `hc-admin-ms-data.json`

This document provides a data blueprint for the `hc-admin-ms` core business service. The JSON structure below models key domain entities—Healthcare Facilities, System Audits, and Provider Metrics—and should be used to populate the service's database for `dev` and `test` profiles.

## Technical Summary: Angular Frontend Consumption

Domain-specific services in the Angular `hc-admin-db` frontend should fetch data from the microservice via the gateway. All API calls must be routed through `/services/hcadminservice/api/...` to leverage the gateway's routing and security features.

- **Example: Fetching Healthcare Facilities**
  A `FacilityService` should be created to manage facility data.

      ```typescript
      import { HttpClient } from '@angular/common/http';
      import { Injectable } from '@angular/core';
      import { Observable } from 'rxjs';
      import { Facility } from './facility.model'; // Define a Facility interface

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

  This approach ensures that the frontend correctly interacts with the microservice architecture and respects the security boundaries enforced by the gateway.

## JSON Data: `hc-admin-ms-data.json`

```json
{
  "dev": {
    "facilities": [
      {
        "entityId": "f1c4e567-d8b5-4a2a-9c0a-1b9e8d6c7b0a",
        "name": "Global General Hospital",
        "status": "ACTIVE",
        "createdAt": "2023-01-15T08:00:00Z",
        "managedBy": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
        "payload": {
          "type": "General Hospital",
          "location": "Worldwide",
          "beds": 1000
        }
      },
      {
        "entityId": "f1c4e567-d8b5-4a2a-9c0a-1b9e8d6c7b0b",
        "name": "Local Clinic West",
        "status": "ACTIVE",
        "createdAt": "2023-03-20T10:00:00Z",
        "managedBy": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12",
        "payload": {
          "type": "Clinic",
          "location": "West District",
          "beds": 50
        }
      }
    ],
    "audits": [
      {
        "entityId": "a2d4e678-e9c6-4b3b-8d1b-2c0a9e7d8c1c",
        "status": "COMPLETED",
        "createdAt": "2023-05-10T14:30:00Z",
        "managedBy": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
        "payload": {
          "event": "USER_ROLE_UPDATED",
          "details": "User 'operator' granted 'ROLE_DATA_IMPORTER'"
        }
      }
    ],
    "metrics": [
      {
        "entityId": "m3b5f789-f0d7-5c4c-9e2c-3d1b0f8e9d2d",
        "facilityId": "f1c4e567-d8b5-4a2a-9c0a-1b9e8d6c7b0b",
        "status": "CURRENT",
        "createdAt": "2023-06-01T00:00:00Z",
        "managedBy": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12",
        "payload": {
          "type": "PatientSatisfaction",
          "value": "98.5",
          "unit": "Percentage"
        }
      }
    ]
  },
  "test": {
    "facilities": [
      {
        "entityId": "t1c4e567-d8b5-4a2a-9c0a-1b9e8d6c7b0t",
        "name": "Decommissioned East Wing",
        "status": "INACTIVE",
        "createdAt": "2022-11-30T17:00:00Z",
        "managedBy": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b11",
        "payload": {
          "type": "Hospital Wing",
          "location": "East District",
          "beds": 0
        }
      },
      {
        "entityId": "t2c4e567-d8b5-4a2a-9c0a-1b9e8d6c7b1t",
        "name": "Under Construction Facility",
        "status": "PLANNING",
        "createdAt": "2024-01-01T09:00:00Z",
        "managedBy": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
        "payload": null
      }
    ],
    "audits": [
      {
        "entityId": "a3d4e678-e9c6-4b3b-8d1b-2c0a9e7d8c2c",
        "status": "FAILED",
        "createdAt": "2023-05-11T16:00:00Z",
        "managedBy": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b13",
        "payload": {
          "event": "DATABASE_CONNECTION_FAILURE",
          "details": "Failed to connect to audit log database."
        }
      }
    ],
    "metrics": []
  }
}
```
