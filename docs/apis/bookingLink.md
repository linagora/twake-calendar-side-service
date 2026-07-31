# Booking Link API

This document describes the Booking Link endpoints for managing user booking links (authenticated users) and using booking links to book events (public users).

---

## Overview

Booking link API allows an authenticated user to expose availability slots for scheduling.
Each booking link is identified by a UUID public ID, scoped to a calendar, and can define
availability rules (weekly recurring or fixed date ranges). 
Public users can use booking links to book events.

---

## Data model

### Booking link object

| Field               | Type             | Description                                                                 |
|---------------------|------------------|-----------------------------------------------------------------------------|
| `publicId`          | string (UUID)    | Unique public identifier of the booking link                                |
| `calendarUrl`       | string           | Calendar URI in the form `/calendars/{baseId}/{calendarId}`                 |
| `durationMinutes`   | integer          | Duration of each bookable slot in minutes (must be positive)                |
| `active`            | boolean          | Whether the booking link is active                                          |
| `autoAccept`        | boolean          | When `true`, the organizer accepts as soon as a booking is created: no validation mail is sent to the organizer and no acknowledgement mail to the booker, the regular IMIP flow applies. When `false` (default), the booking starts the validation flow. |
| `availabilityRules` | array (optional) | List of availability rule objects (weekly or fixed)                         |
| `extraAttendees`    | object (optional)| Tree of the registered users invited to every event booked through this link. Omitted from responses when empty. See [Extra attendees](#extra-attendees). |
| `name`              | string (optional)| Display name of the booking link                                            |
| `description`       | string (optional)| Description of the booking link                                             |
| `color`             | string           | Display color of the booking link, as a `#RRGGBB` hex string. Always present in responses, defaulting to `#6B4ECC` when not set. |
| `location`          | string (optional)| Location carried onto every event booked through this link                  |
| `visibility`        | string (optional)| One of `PUBLIC`, `PRIVATE`. Sets the `CLASS` of every booked event          |
| `transparency`      | string (optional)| One of `OPAQUE`, `TRANSPARENT`. Sets the `TRANSP` of every booked event      |
| `resources`         | array (optional) | Resource ids added as attendees of every booked event. Omitted from responses when empty. See [Resources](#resources). |
| `alarm`             | array (optional) | Alarms of every booked event, each `{period, action}`. Omitted from responses when empty. See [Alarm](#alarm). |

### Availability rule object

**Weekly rule** — repeats on a given day of the week:

| Field       | Type   | Description                                                                |
|-------------|--------|----------------------------------------------------------------------------|
| `type`      | string | `"weekly"`                                                                 |
| `dayOfWeek` | string | Three-letter abbreviation: `MON`, `TUE`, `WED`, `THU`, `FRI`, `SAT`, `SUN` |
| `start`     | string | Start time in `HH:mm` format                                               |
| `end`       | string | End time in `HH:mm` format                                                 |
| `timeZone`  | string | IANA timezone for this rule (e.g. `Asia/Ho_Chi_Minh`, `UTC`). Default to the timezone setting of the user, then to the default configured timezone then UTC if omitted. |

**Fixed rule** — a one-time date range:

| Field      | Type   | Description                                         |
|------------|--------|-----------------------------------------------------|
| `type`     | string | `"fixed"`                                           |
| `start`    | string | Start date-time in `yyyy-MM-ddTHH:mm:ss` format     |
| `end`      | string | End date-time in `yyyy-MM-ddTHH:mm:ss` format       |
| `timeZone` | string | IANA timezone used to interpret `start` and `end`. Default to the timezone setting of the user, then to the default configured timezone then UTC if omitted. |

### Extra attendees

A booking link may carry `extraAttendees`: registered users to invite alongside the owner. This allows handing
over a single link for a meeting that needs several people, for instance a sales representative, a presales
engineer and a project manager.

They are expressed as a tree, whose only supported shape today is a single `and` node of `participant` leaves -
invite all of them:

```json
{
    "extraAttendees": {
        "and": [
            { "participant": "67c3a792e4b0884b05ef8af0" },
            { "participant": "67c3a792e4b0884b05ef8af1" }
        ]
    }
}
```

The tree leaves room for the richer combinations calendaring calls for (optional participants, substitutes:
`bob` OR `michael` but only one of them) without a breaking change. Any other shape - other node types, nested
`and`, extra fields on a leaf - is rejected with a `400 Bad Request` for now.

Extra attendees change the booking link in two ways:

- The offered slots are the intersection of the availability rules, the owner availability, and each extra
  attendee availability. Attendee availability is seen from the owner point of view: the free-busy lookup is
  performed as the booking link owner, so only what the calendar server lets the owner see (public calendar,
  read delegation) narrows down the slots. An attendee whose calendar cannot be read at all is considered free
  rather than making the booking link unusable.
- Every event booked through the link carries the extra attendees as attendees, with `PARTSTAT=NEEDS-ACTION`:
  they are invited through the regular iTIP flow and still have to answer.

Working hours of the extra attendees own timezone are not taken into account.

Constraints:

- Each `participant` must be the OpenPaaS id of an existing user, and must not be the booking link owner.
- At most 20 participants. Duplicates are ignored.

In `GET` and list responses each `participant` leaf also carries the resolved `name` and `email` (omitted when
the user no longer exists):

```json
{ "extraAttendees": { "and": [ { "participant": "67c3a792e4b0884b05ef8af0", "name": "Bob Smith", "email": "bob@open-paas.org" } ] } }
```

### Resources

A booking link may carry `resources`: a flat array of resource ids, added as `CUTYPE=RESOURCE` attendees
(`PARTSTAT=NEEDS-ACTION`) of every booked event so the resource acceptance workflow applies.

```json
{
    "resources": ["68a1b2c3d4e5f60718293a4b", "68a1b2c3d4e5f60718293a4c"]
}
```

In `GET` and list responses `resources` is returned as objects `{id, name}`, the `name` being the resolved
resource name (omitted when the resource no longer exists):

```json
{ "resources": [ { "id": "68a1b2c3d4e5f60718293a4b", "name": "Projector" } ] }
```

Constraints:

- Each id must be an existing, non-deleted resource belonging to the booking link owner's domain.
- At most 20 resources. Duplicates are ignored.

### Alarm

A booking link may carry an `alarm`: an array of alarms, each added as a VALARM addressed to the event attendees
on every booked event. Each entry has:

- `period` — ISO-8601 duration relative to the event start (e.g. `-PT10M` for ten minutes before), must be negative.
- `action` — now only `EMAIL`.

```json
{
    "alarm": [ { "period": "-PT10M", "action": "EMAIL" } ]
}
```

---

## Endpoints

### **POST /api/booking-links**

Create a new booking link for the authenticated user.

**Request body**

| Field               | Required | Description                                                                                                                                              |
|---------------------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `calendarUrl`       | yes      | Calendar URI (must be accessible by the user)                                                                                                            |
| `durationMinutes`   | yes      | Slot duration in minutes, must be positive                                                                                                               |
| `active`            | yes      | Whether the booking link is active                                                                                                                       |
| `autoAccept`        | no       | Whether bookings are auto-accepted by the organizer. Defaults to `false` when omitted.                                                                   |
| `availabilityRules` | no       | List of availability rules. Defaults to business hours from user settings when omitted. Each rule may specify its own `timeZone` (see availability rule object above). |
| `extraAttendees`    | no       | Tree of the registered users to invite on every booked event. Empty when omitted. See [Extra attendees](#extra-attendees).                                |
| `name`              | no       | Display name of the booking link. Blank values are ignored.                                                                                              |
| `description`       | no       | Description of the booking link. Blank values are ignored.                                                                                               |
| `color`             | no       | Display color as a `#RRGGBB` hex string. Blank values are ignored. Defaults to `#6B4ECC` when omitted.                                                    |
| `location`          | no       | Location of every booked event. Blank values are ignored.                                                                                                |
| `visibility`        | no       | One of `PUBLIC`, `PRIVATE`.                                                                                                                               |
| `transparency`      | no       | One of `OPAQUE`, `TRANSPARENT`.                                                                                                                           |
| `resources`         | no       | Array of resource ids. See [Resources](#resources).                                                                                                      |
| `alarm`             | no       | Array of alarms, each `{period, action}`. See [Alarm](#alarm).                                                                                           |

**Sample request**
```
POST /api/booking-links
Authorization: Bearer <token>
Content-Type: application/json

{
    "calendarUrl": "/calendars/67c3a792e4b0884b05ef8aef/67c3a792e4b0884b05ef8aef",
    "durationMinutes": 30,
    "active": true,
    "autoAccept": false,
    "name": "Intro call",
    "description": "Book a 30-minute introduction call",
    "color": "#6B4ECC",
    "location": "Room 3, Building A",
    "visibility": "PRIVATE",
    "transparency": "TRANSPARENT",
    "resources": ["68a1b2c3d4e5f60718293a4b"],
    "alarm": [ { "period": "-PT10M", "action": "EMAIL" } ],
    "extraAttendees": { "and": [{ "participant": "67c3a792e4b0884b05ef8af0" }, { "participant": "67c3a792e4b0884b05ef8af1" }] },
    "availabilityRules": [
        { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "timeZone": "Asia/Ho_Chi_Minh" },
        { "type": "weekly", "dayOfWeek": "MON", "start": "13:00", "end": "17:00", "timeZone": "Europe/London" },
        { "type": "fixed", "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00", "timeZone": "UTC" }
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
| 400    | Missing or invalid field, unknown rule type, invalid `timeZone`, calendar not found or inaccessible, unknown extra attendee, extra attendee is the owner, invalid `visibility`/`transparency`/`alarm`, unknown or cross-domain resource |
| 401    | Unauthenticated                                |

---

### **GET /api/booking-links**

List all booking links of the authenticated user (sorted by update time).

**Sample request**
```
GET /api/booking-links
Authorization: Bearer <token>
```

**Sample response**
```
HTTP/1.1 200 OK
Content-Type: application/json

[
    {
        "publicId": "550e8400-e29b-41d4-a716-446655440000",
        "calendarUrl": "/calendars/67c3a792e4b0884b05ef8aef/67c3a792e4b0884b05ef8aef",
        "durationMinutes": 30,
        "active": true,
        "autoAccept": false,
        "name": "Intro call",
        "description": "Book a 30-minute introduction call",
        "color": "#6B4ECC",
        "location": "Room 3, Building A",
        "visibility": "PRIVATE",
        "transparency": "TRANSPARENT",
        "resources": [ { "id": "68a1b2c3d4e5f60718293a4b", "name": "Projector" } ],
        "alarm": [ { "period": "-PT10M", "action": "EMAIL" } ],
        "extraAttendees": { "and": [ { "participant": "67c3a792e4b0884b05ef8af0", "name": "Bob Smith", "email": "bob@open-paas.org" } ] },
        "availabilityRules": [
            { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "17:00", "timeZone": "Asia/Ho_Chi_Minh" }
        ]
    }
]
```

Fields `availabilityRules`, `extraAttendees`, `name`, `description`, `location`, `visibility`, `transparency`,
`resources` and `alarm` are omitted from each entry when not set. The `color` field is always present, defaulting
to `#6B4ECC` when not set.

**Error responses**

| Status | Cause           |
|--------|-----------------|
| 401    | Unauthenticated |

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
    "autoAccept": false,
    "name": "Intro call",
    "description": "Book a 30-minute introduction call",
    "color": "#6B4ECC",
    "location": "Room 3, Building A",
    "visibility": "PRIVATE",
    "transparency": "TRANSPARENT",
    "resources": [ { "id": "68a1b2c3d4e5f60718293a4b", "name": "Projector" } ],
    "alarm": [ { "period": "-PT10M", "action": "EMAIL" } ],
    "extraAttendees": { "and": [ { "participant": "67c3a792e4b0884b05ef8af0", "name": "Bob Smith", "email": "bob@open-paas.org" } ] },
    "availabilityRules": [
        { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "timeZone": "Asia/Ho_Chi_Minh" },
        { "type": "weekly", "dayOfWeek": "MON", "start": "13:00", "end": "17:00", "timeZone": "Europe/London" },
        { "type": "fixed", "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00", "timeZone": "UTC" }
    ]
}
```

Fields `availabilityRules`, `extraAttendees`, `name`, `description`, `location`, `visibility`, `transparency`,
`resources` and `alarm` are omitted from the response when not set. The `color` field is always present, defaulting
to `#6B4ECC` when not set.

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

| Field               | Description                                                                                                      |
|---------------------|------------------------------------------------------------------------------------------------------------------|
| `calendarUrl`       | New calendar URI                                                                                                 |
| `durationMinutes`   | New slot duration in minutes, must be positive                                                                   |
| `active`            | New active state                                                                                                 |
| `autoAccept`        | New auto-accept state                                                                                            |
| `availabilityRules` | Replaces all existing rules. Set to `null` to remove all rules. Each rule may specify its own `timeZone`. |
| `extraAttendees`    | Replaces all existing extra attendees. Set to `null` or `{"and": []}` to remove them all. See [Extra attendees](#extra-attendees). |
| `name`              | New display name. Set to `null` or a blank value to remove it.                                                   |
| `description`       | New description. Set to `null` or a blank value to remove it.                                                    |
| `color`             | New display color as a `#RRGGBB` hex string. Set to `null` or a blank value to remove it (the default `#6B4ECC` then applies). |
| `location`          | New location. Set to `null` or a blank value to remove it.                                                       |
| `visibility`        | One of `PUBLIC`, `PRIVATE`. Set to `null` or a blank value to remove it.                                         |
| `transparency`      | One of `OPAQUE`, `TRANSPARENT`. Set to `null` or a blank value to remove it.                                     |
| `resources`         | Replaces all existing resources. Set to `null` or `[]` to remove them all. See [Resources](#resources).         |
| `alarm`             | Replaces all alarms with an array of `{period, action}`. Set to `null` or `[]` to remove them all.               |

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
    "availabilityRules": [
        { "type": "weekly", "dayOfWeek": "FRI", "start": "14:00", "end": "18:00", "timeZone": "UTC" }
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
| 400    | No field provided, invalid value, invalid `timeZone`, calendar not found or inaccessible, unknown extra attendee, extra attendee is the owner, invalid `visibility`/`transparency`/`alarm`, unknown or cross-domain resource |
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
| `timeZone` | no      | Timezone identifier (e.g. `Europe/Paris`, `UTC`, or an ical4j-supported legacy identifier) used to express `range` and `slots` in the response. Defaults to `UTC` when omitted. |

Constraints:

- Max query range is 60 days.

**Sample request**

```
GET /api/booking-links/550e8400-e29b-41d4-a716-446655440000/slots?from=2036-01-26T00:00:00Z&to=2036-01-27T00:00:00Z&timeZone=Europe/Paris
```

**Sample response**

```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "durationMinutes": 30,
  "autoAccept": false,
  "name": "Interview",
  "description": "A 30 minutes interview",
  "color": "#6B4ECC",
  "location": "Room 3, Building A",
  "resources": [
    { "name": "Projector", "photoUrl": "https://excal.linagora.com/images/icon/projector.svg" }
  ],
  "visibility": "PRIVATE",
  "transparency": "TRANSPARENT",
  "alarm": [ { "period": "-PT10M", "action": "EMAIL" } ],
  "owner": {
    "displayName": "John Doe",
    "email": "john.doe@open-paas.org"
  },
  "range": {
    "from": "2036-01-26T01:00:00+01:00",
    "to": "2036-01-27T01:00:00+01:00"
  },
  "slots": [
    { "start": "2036-01-26T10:00:00+01:00" },
    { "start": "2036-01-26T10:30:00+01:00" },
    { "start": "2036-01-26T11:00:00+01:00" }
  ]
}
```

The `owner` object exposes the public booking link owner `displayName` and `email`.

The response also exposes the booking link `autoAccept` flag, along with the optional `name` and `description` (omitted when not set) and the `color` (always present, defaulting to `#6B4ECC`), so the bookee can access that information too.

The public slots response also exposes the booking link `location`, `resources` (each with a `name` and, when the resource has an icon, a `photoUrl`), `visibility`, `transparency` and `alarm`, each omitted when not set.

**Error responses**

| Status | Cause |
|--------|-------|
| 400    | Missing/invalid `from` or `to`, invalid UUID, `to <= from`, range > 60 days, invalid `timeZone`, booking link inactive |
| 404    | Booking link not found |

The error body may carry a `type` field identifying the error, see [Error types](errorTypes.md).

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
Content-Type: application/json

{
  "bookingConfirmationToken": "<signed-jwt>"
}
```

The returned `bookingConfirmationToken` is a signed JWT that can be used to retrieve or cancel the booked event. It encodes the
`publicBookingLinkId`, `calendarId`, `ownerId`, and `eventId` needed to identify and cancel the booking.

**Error responses**

| Status | Cause |
|--------|-------|
| 400    | Invalid body, invalid email/constraints, requested slot not available, booking link inactive |
| 422    | Request is syntactically valid but cannot be processed due to business validation rules |
| 404    | Booking link not found |
| 500    | Unexpected server-side booking error |

The error body may carry a `type` field identifying the error, see [Error types](errorTypes.md).

---

### **DELETE /api/booked-event**

- No authentication required

Cancel a previously booked event using the confirmation token returned by the `/book` endpoint.

**Query parameters**

| Parameter | Required | Description |
|-----------|----------|-------------|
| `bookingConfirmationToken` | yes | The signed JWT returned by the `/book` endpoint |

**Sample request**

```
DELETE /api/booked-event?bookingConfirmationToken=<signed-jwt>
```

**Sample response**

```
HTTP/1.1 204 No Content
```

**Error responses**

| Status | Cause |
|--------|-------|
| 400    | `bookingConfirmationToken` is missing |
| 401    | `bookingConfirmationToken` is invalid |
| 500    | Unexpected server-side error |

---

### **GET /api/booked-event**

- No authentication required

Retrieve the details of a previously booked event using the confirmation token returned by the `/book` endpoint.

**Query parameters**

| Parameter | Required | Description |
|-----------|----------|-------------|
| `bookingConfirmationToken` | yes | The signed JWT returned by the `/book` endpoint |

**Sample request**

```
GET /api/booked-event?bookingConfirmationToken=<signed-jwt>
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

| Status | Cause |
|--------|-------|
| 400    | `bookingConfirmationToken` is missing |
| 401    | `bookingConfirmationToken` is invalid |
| 404    | Booked event not found |
| 500    | Unexpected server-side error |

---
