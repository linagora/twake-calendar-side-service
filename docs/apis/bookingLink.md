# Booking Link API

This document describes the Booking Link endpoints for managing user booking links.

---

## Overview

A booking link allows an authenticated user to expose availability slots for scheduling.
Each booking link is identified by a UUID public ID, scoped to a calendar, and can define
availability rules (weekly recurring or fixed date ranges).

---

## Data model

### Booking link object

| Field               | Type             | Description                                                                 |
|---------------------|------------------|-----------------------------------------------------------------------------|
| `publicId`          | string (UUID)    | Unique public identifier of the booking link                                |
| `calendarUrl`       | string           | Calendar URI in the form `/calendars/{baseId}/{calendarId}`                 |
| `durationMinutes`   | integer          | Duration of each bookable slot in minutes (must be positive)                |
| `active`            | boolean          | Whether the booking link is active                                          |
| `timeZone`          | string           | IANA timezone used for availability rules (e.g. `Asia/Ho_Chi_Minh`, `UTC`) |
| `availabilityRules` | array (optional) | List of availability rule objects (weekly or fixed)                         |

### Availability rule object

**Weekly rule** — repeats on a given day of the week:

| Field       | Type   | Description                                         |
|-------------|--------|-----------------------------------------------------|
| `type`      | string | `"weekly"`                                          |
| `dayOfWeek` | string | Three-letter abbreviation: `MON`, `TUE`, `WED`, `THU`, `FRI`, `SAT`, `SUN` |
| `start`     | string | Start time in `HH:mm` format                        |
| `end`       | string | End time in `HH:mm` format                          |

**Fixed rule** — a one-time date range:

| Field   | Type   | Description                                          |
|---------|--------|------------------------------------------------------|
| `type`  | string | `"fixed"`                                            |
| `start` | string | Start date-time in `yyyy-MM-ddTHH:mm:ss` format      |
| `end`   | string | End date-time in `yyyy-MM-ddTHH:mm:ss` format        |

The `timeZone` field at the booking link level applies to all availability rules.

---

## Endpoints

### **POST /api/booking-links**

Create a new booking link for the authenticated user.

**Request body**

| Field               | Required | Description                                        |
|---------------------|----------|----------------------------------------------------|
| `calendarUrl`       | yes      | Calendar URI (must be accessible by the user)      |
| `durationMinutes`   | yes      | Slot duration in minutes, must be positive         |
| `active`            | yes      | Whether the booking link is active                 |
| `timeZone`          | no       | Defaults to `UTC` if omitted                       |
| `availabilityRules` | no       | List of availability rules                         |

**Sample request**
```
POST /api/booking-links
Authorization: Bearer <token>
Content-Type: application/json

{
    "calendarUrl": "/calendars/67c3a792e4b0884b05ef8aef/67c3a792e4b0884b05ef8aef",
    "durationMinutes": 30,
    "active": true,
    "timeZone": "Asia/Ho_Chi_Minh",
    "availabilityRules": [
        { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "12:00" },
        { "type": "weekly", "dayOfWeek": "MON", "start": "13:00", "end": "17:00" },
        { "type": "fixed", "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00" }
    ]
}
```

**Sample response**
```
HTTP/1.1 201 Created
Content-Type: application/json

{
    "bookingLinkPublicId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Error responses**

| Status | Cause                                          |
|--------|------------------------------------------------|
| 400    | Missing or invalid field, unknown rule type, calendar not found or inaccessible |
| 401    | Unauthenticated                                |

---

### **GET /api/booking-links/{bookingLinkPublicId}**

Retrieve a booking link by its public ID. Only returns the link if it belongs to the authenticated user.

**Sample request**
```
GET /api/booking-links/550e8400-e29b-41d4-a716-446655440000
Authorization: Bearer <token>
```

**Sample response**
```
HTTP/1.1 200 OK
Content-Type: application/json

{
    "publicId": "550e8400-e29b-41d4-a716-446655440000",
    "calendarUrl": "/calendars/67c3a792e4b0884b05ef8aef/67c3a792e4b0884b05ef8aef",
    "durationMinutes": 30,
    "active": true,
    "timeZone": "Asia/Ho_Chi_Minh",
    "availabilityRules": [
        { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "12:00" },
        { "type": "weekly", "dayOfWeek": "MON", "start": "13:00", "end": "17:00" },
        { "type": "fixed", "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00" }
    ]
}
```

Fields `availabilityRules` are omitted from the response when not set.

**Error responses**

| Status | Cause                                                             |
|--------|-------------------------------------------------------------------|
| 400    | `bookingLinkPublicId` is not a valid UUID                         |
| 401    | Unauthenticated                                                   |
| 404    | Booking link not found or belongs to another user                 |

---

### **PATCH /api/booking-links/{bookingLinkPublicId}**

Partially update a booking link. Only fields present in the request body are updated,
absent fields are left unchanged. At least one field must be provided.

**Request body**

All fields are optional. Include only the fields to update.

| Field               | Description                                                           |
|---------------------|-----------------------------------------------------------------------|
| `calendarUrl`       | New calendar URI                                                      |
| `durationMinutes`   | New slot duration in minutes, must be positive                        |
| `active`            | New active state                                                      |
| `timeZone`          | Timezone applied when parsing updated availability rules              |
| `availabilityRules` | Replaces all existing rules. Set to `null` to remove all rules        |

**Sample request — update duration and deactivate**
```
PATCH /api/booking-links/550e8400-e29b-41d4-a716-446655440000
Authorization: Bearer <token>
Content-Type: application/json

{
    "durationMinutes": 60,
    "active": false
}
```

**Sample request — replace availability rules**
```
PATCH /api/booking-links/550e8400-e29b-41d4-a716-446655440000
Authorization: Bearer <token>
Content-Type: application/json

{
    "timeZone": "UTC",
    "availabilityRules": [
        { "type": "weekly", "dayOfWeek": "FRI", "start": "14:00", "end": "18:00" }
    ]
}
```

**Sample request — remove availability rules**
```
PATCH /api/booking-links/550e8400-e29b-41d4-a716-446655440000
Authorization: Bearer <token>
Content-Type: application/json

{
    "availabilityRules": null
}
```

**Sample response**
```
HTTP/1.1 204 No Content
```

**Error responses**

| Status | Cause                                                             |
|--------|-------------------------------------------------------------------|
| 400    | No field provided, invalid value, calendar not found or inaccessible |
| 401    | Unauthenticated                                                   |
| 404    | Booking link not found or belongs to another user                 |

---

### **DELETE /api/booking-links/{bookingLinkPublicId}**

Delete a booking link. Only deletes the link if it belongs to the authenticated user.
Returns 404 if the booking link does not exist or belongs to another user.

**Sample request**
```
DELETE /api/booking-links/550e8400-e29b-41d4-a716-446655440000
Authorization: Bearer <token>
```

**Sample response**
```
HTTP/1.1 204 No Content
```

**Error responses**

| Status | Cause                                                             |
|--------|-------------------------------------------------------------------|
| 400    | `bookingLinkPublicId` is not a valid UUID                         |
| 401    | Unauthenticated                                                   |
| 404    | Booking link not found or belongs to another user                 |

---

### **POST /api/booking-links/{bookingLinkPublicId}/reset**

Generate a new public ID for an existing booking link, invalidating the old one.
Useful when the current public link needs to be revoked and replaced.

**Sample request**
```
POST /api/booking-links/550e8400-e29b-41d4-a716-446655440000/reset
Authorization: Bearer <token>
```

**Sample response**
```
HTTP/1.1 200 OK
Content-Type: application/json

{
    "bookingLinkPublicId": "9f4f2166-95c4-4d3e-b421-23dc5e8a1fbb"
}
```

**Error responses**

| Status | Cause                                                             |
|--------|-------------------------------------------------------------------|
| 400    | `bookingLinkPublicId` is not a valid UUID                         |
| 401    | Unauthenticated                                                   |
| 404    | Booking link not found or belongs to another user                 |

---

### **GET /api/booking-links/{bookingLinkPublicId}/slots**
- No authentication required

Compute available slots for a public booking link in a query time range.

**Query parameters**

| Parameter | Required | Description |
|-----------|----------|-------------|
| `from`    | yes      | Range start, ISO-8601 instant (example: `2036-01-26T00:00:00Z`) |
| `to`      | yes      | Range end, ISO-8601 instant (must be strictly after `from`) |

Constraints:

- Max query range is 60 days.

**Sample request**

```
GET /api/booking-links/550e8400-e29b-41d4-a716-446655440000/slots?from=2036-01-26T00:00:00Z&to=2036-01-27T00:00:00Z
```

**Sample response**

```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "durationMinutes": 30,
  "range": {
    "from": "2036-01-26T00:00:00Z",
    "to": "2036-01-27T00:00:00Z"
  },
  "slots": [
    { "start": "2036-01-26T09:00:00Z" },
    { "start": "2036-01-26T09:30:00Z" },
    { "start": "2036-01-26T10:00:00Z" }
  ]
}
```

**Error responses**

| Status | Cause |
|--------|-------|
| 400    | Missing/invalid `from` or `to`, invalid UUID, `to <= from`, range > 60 days |
| 404    | Booking link not found or inactive |

---

### **POST /api/booking-links/{bookingLinkPublicId}/book**

- No authentication required

Create a booking on a selected slot for a public booking link.

**Request body**

| Field | Required | Description |
|-------|----------|-------------|
| `startUtc` | yes | Slot start in ISO-8601 instant format |
| `creator` | yes | Booking creator object |
| `creator.email` | yes | Creator email |
| `creator.name` | no | Creator display name |
| `additional_attendees` | no | Additional attendees (max 20), unique emails, must not include creator email |
| `eventTitle` | yes | Event title, non-blank, max 255 chars |
| `visioLink` | no | Whether to generate a visio link (default: `false`) |
| `notes` | no | Notes, max 2000 chars |

**Sample request**

```
POST /api/booking-links/550e8400-e29b-41d4-a716-446655440000/book
Content-Type: application/json

{
  "startUtc": "2036-01-26T09:00:00Z",
  "creator": {
    "name": "BOB",
    "email": "creator@example.com"
  },
  "additional_attendees": [
    {
      "name": "Nguyen Van A",
      "email": "vana@example.com"
    }
  ],
  "eventTitle": "30-min intro call",
  "visioLink": true,
  "notes": "Please call via Zoom."
}
```

**Sample response**

```
HTTP/1.1 201 Created
```

**Error responses**

| Status | Cause |
|--------|-------|
| 400    | Invalid body, invalid email/constraints, requested slot not available |
| 422    | Request is syntactically valid but cannot be processed due to business validation rules |
| 404    | Booking link not found or inactive |
| 500    | Unexpected server-side booking error |

---
