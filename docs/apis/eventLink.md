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
| `eventToken` | string (UUID)      | Value of the token                                           |
| `right`      | string             | One of `VIEW`, `ADD_ATTENDEE`                                |
| `expiration` | string (ISO-8601)  | Instant after which the token is no longer valid             |

Every field is always present: a token always expires. Expired tokens are never returned nor accepted.

---

## Endpoints

### **POST /api/events/{calendarHomeId}/{calendarId}/{eventId}/eventTokens**

Create an event token for an event. Only the owner of the calendar home can call it.

`eventId` is the name of the calendar object resource without its `.ics` extension.

Tokens can only be created for a meeting whose `ORGANIZER` is the owner.

**Request body**

| Field              | Required | Description                                                           |
|--------------------|----------|-----------------------------------------------------------------------|
| `right`            | yes      | One of `VIEW`, `ADD_ATTENDEE`                                         |
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
    "eventToken": "3f0c2a8e-6b1d-4c57-9e2a-8d4b7f1c0a93",
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
        "eventToken": "3f0c2a8e-6b1d-4c57-9e2a-8d4b7f1c0a93",
        "right": "VIEW",
        "expiration": "2026-12-02T14:30:00Z"
    },
    {
        "eventToken": "b7e45d21-0c9f-4a3e-8f6b-2d1a9c3e5f70",
        "right": "ADD_ATTENDEE",
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
GET /api/eventTokens/3f0c2a8e-6b1d-4c57-9e2a-8d4b7f1c0a93
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
| 400    | `eventToken` is not a UUID        |
| 404    | Token not found or expired        |

---

### **DELETE /api/eventTokens/{eventToken}**

Revoke an event token. Only the owner of the calendar home holding the event can call it.

**Sample request**
```
DELETE /api/eventTokens/3f0c2a8e-6b1d-4c57-9e2a-8d4b7f1c0a93
Authorization: Bearer <token>
```

**Sample response**
```
HTTP/1.1 204 No Content
```

**Error responses**

| Status | Cause                                                          |
|--------|----------------------------------------------------------------|
| 400    | `eventToken` is not a UUID                                     |
| 401    | Unauthenticated                                                |
| 403    | The authenticated user is not the owner of the calendar home   |
| 404    | Token not found or expired                                     |

---

### **GET /api/events?eventToken={eventToken}**

- No authentication required

Return the event an event token points to. A token of any right allows it.

**Query parameters**

| Parameter    | Required | Description         |
|--------------|----------|---------------------|
| `eventToken` | yes      | Value of the token  |

**Sample request**
```
GET /api/events?eventToken=3f0c2a8e-6b1d-4c57-9e2a-8d4b7f1c0a93
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
| 400    | `eventToken` is missing or not a UUID                  |
| 404    | Token not found or expired, event deleted or cancelled |

---

### **POST /api/events/addMyself?eventToken={eventToken}**

Add the authenticated user as an attendee of the event an event token points to, with `PARTSTAT=ACCEPTED`.
The token must have the `ADD_ATTENDEE` right. The event is updated on behalf of the owner of the calendar home, who must
still be its `ORGANIZER`, and the DAV server schedules the invitation of the new attendee.

Adding a user who is already an attendee leaves the event untouched.

**Query parameters**

| Parameter    | Required | Description         |
|--------------|----------|---------------------|
| `eventToken` | yes      | Value of the token  |

**Sample request**
```
POST /api/events/addMyself?eventToken=3f0c2a8e-6b1d-4c57-9e2a-8d4b7f1c0a93
Authorization: Bearer <token>
```

**Sample response**
```
HTTP/1.1 204 No Content
```

**Error responses**

| Status | Cause                                                                                 |
|--------|---------------------------------------------------------------------------------------|
| 400    | `eventToken` is missing or not a UUID                                                 |
| 401    | Unauthenticated                                                                       |
| 403    | The token does not have the `ADD_ATTENDEE` right, or the event is no longer a meeting organized by the owner |
| 404    | Token not found or expired, event deleted or cancelled                                |

---

### **POST /api/events/addAttendee?eventToken={eventToken}**

- No authentication required

Add an attendee to the event an event token points to, with `PARTSTAT=NEEDS-ACTION`.
The token must have the `ADD_ATTENDEE` right. The event is updated on behalf of the owner of the calendar home, who must
still be its `ORGANIZER`, and the DAV server schedules the invitation of the new attendee.

Adding an address which is already an attendee leaves the event untouched.

Anyone holding the token can have an invitation sent to any address.

**Query parameters**

| Parameter    | Required | Description         |
|--------------|----------|---------------------|
| `eventToken` | yes      | Value of the token  |

**Request body**

| Field         | Required | Description                     |
|---------------|----------|---------------------------------|
| `mailAddress` | yes      | Mail address of the attendee    |
| `displayName` | no       | Display name of the attendee    |

**Sample request**
```
POST /api/events/addAttendee?eventToken=3f0c2a8e-6b1d-4c57-9e2a-8d4b7f1c0a93
Content-Type: application/json

{
    "mailAddress": "external@domain.com",
    "displayName": "External Unauthenticated User"
}
```

**Sample response**
```
HTTP/1.1 204 No Content
```

**Error responses**

| Status | Cause                                                                                 |
|--------|---------------------------------------------------------------------------------------|
| 400    | `eventToken` is missing or not a UUID, missing or invalid body, missing or invalid `mailAddress` |
| 403    | The token does not have the `ADD_ATTENDEE` right, or the event is no longer a meeting organized by the owner |
| 404    | Token not found or expired, event deleted or cancelled                                |
