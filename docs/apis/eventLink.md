# Event Link API

This document describes the Event Link endpoints for sharing an event through a token (authenticated owners) and reading the shared event (public users).

---

## Overview

The owner of a calendar home can create event tokens for an event of one of its calendars.
Whoever holds a token can read the event it points to, without being authenticated.
A token expires after a given number of days and can be revoked by the owner at any time.

An event is designated by the path of its calendar object resource, as found in the `href` of the event:
`/calendars/{calendarHomeId}/{calendarId}/{eventId}.ics`.

---

## Data model

### Event token object

| Field        | Type               | Description                                                  |
|--------------|--------------------|--------------------------------------------------------------|
| `eventToken` | string             | Value of the token                                           |
| `right`      | string             | One of `VIEW`, `ADD_MYSELF`                                  |
| `expiration` | string (ISO-8601)  | Instant after which the token is no longer valid             |

Expired tokens are never returned nor accepted.

---

## Endpoints

### **POST /api/events/{calendarHomeId}/{calendarId}/{eventId}/eventTokens**

Create an event token for an event. Only the owner of the calendar home can call it.

`eventId` is the name of the calendar object resource without its `.ics` extension.

Tokens can only be created for a meeting whose `ORGANIZER` is the owner.

**Request body**

| Field              | Required | Description                                                           |
|--------------------|----------|-----------------------------------------------------------------------|
| `right`            | yes      | One of `VIEW`, `ADD_MYSELF`                                           |
| `expirationInDays` | no       | Validity of the token in days, must be positive. Defaults to `30`.    |

**Sample request**
```
POST /api/events/5f50a663bdaffe002629099c/5f50a663bdaffe002629099c/sabredav-bbc9219b-e21b-4797-b1fb-7480bb1c80fc/eventTokens
Authorization: Bearer <token>
Content-Type: application/json

{
    "right": "VIEW",
    "expirationInDays": 7
}
```

**Sample response**
```
HTTP/1.1 201 Created
Content-Type: application/json

{
    "eventToken": "Vb3N9nq0yZtJkq3Qe7cS1xG5cGw2Rw",
    "expiration": "2026-12-02T14:30:00Z"
}
```

**Error responses**

| Status | Cause                                                                                 |
|--------|---------------------------------------------------------------------------------------|
| 400    | Missing or invalid body, missing or unknown `right`, non positive `expirationInDays`, invalid path segment |
| 401    | Unauthenticated                                                                       |
| 403    | The authenticated user is not the owner of the calendar home, or the event is not a meeting organized by the owner |
| 404    | Event not found                                                                       |

---

### **GET /api/events/{calendarHomeId}/{calendarId}/{eventId}/eventTokens**

List the valid event tokens of an event. Only the owner of the calendar home can call it.

**Sample request**
```
GET /api/events/5f50a663bdaffe002629099c/5f50a663bdaffe002629099c/sabredav-bbc9219b-e21b-4797-b1fb-7480bb1c80fc/eventTokens
Authorization: Bearer <token>
```

**Sample response**
```
HTTP/1.1 200 OK
Content-Type: application/json

[
    {
        "eventToken": "Vb3N9nq0yZtJkq3Qe7cS1xG5cGw2Rw",
        "right": "VIEW",
        "expiration": "2026-12-02T14:30:00Z"
    },
    {
        "eventToken": "p1Xc6Qw0T2mYbVd8sK4nJe9rLhA3Zu",
        "right": "ADD_MYSELF",
        "expiration": "2026-12-25T09:00:00Z"
    }
]
```

**Error responses**

| Status | Cause                                                          |
|--------|----------------------------------------------------------------|
| 400    | Invalid path segment                                           |
| 401    | Unauthenticated                                                |
| 403    | The authenticated user is not the owner of the calendar home   |

---

### **GET /api/eventTokens/{eventToken}**

- No authentication required

Return the right granted by an event token.

**Sample request**
```
GET /api/eventTokens/Vb3N9nq0yZtJkq3Qe7cS1xG5cGw2Rw
```

**Sample response**
```
HTTP/1.1 200 OK
Content-Type: application/json

{
    "right": "VIEW"
}
```

**Error responses**

| Status | Cause                             |
|--------|-----------------------------------|
| 404    | Token not found or expired        |

---

### **DELETE /api/eventTokens/{eventToken}**

Revoke an event token. Only the owner of the calendar home holding the event can call it.

**Sample request**
```
DELETE /api/eventTokens/Vb3N9nq0yZtJkq3Qe7cS1xG5cGw2Rw
Authorization: Bearer <token>
```

**Sample response**
```
HTTP/1.1 204 No Content
```

**Error responses**

| Status | Cause                                                          |
|--------|----------------------------------------------------------------|
| 401    | Unauthenticated                                                |
| 403    | The authenticated user is not the owner of the calendar home   |
| 404    | Token not found or expired                                     |

---

### **GET /api/events?eventToken={eventToken}**

- No authentication required

Return the event an event token points to.

**Query parameters**

| Parameter    | Required | Description         |
|--------------|----------|---------------------|
| `eventToken` | yes      | Value of the token  |

**Sample request**
```
GET /api/events?eventToken=Vb3N9nq0yZtJkq3Qe7cS1xG5cGw2Rw
```

**Sample response**
```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "eventJSON": ["vcalendar", [...], [...]]
}
```

The `eventJSON` field contains the event in jCal format (RFC 7265).

**Error responses**

| Status | Cause                                                  |
|--------|--------------------------------------------------------|
| 400    | `eventToken` is missing                                |
| 404    | Token not found or expired, event deleted or cancelled |
