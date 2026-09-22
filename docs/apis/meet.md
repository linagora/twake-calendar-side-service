# Meet API

This document describes the video conference endpoint exposed by the side service.

---

## Overview

The side service asks [LaSuite Meet](https://github.com/suitenumerique/meet) to create a room and
returns its URL, which the caller writes into the `X-OPENPAAS-VIDEOCONFERENCE` ICS property. The room
therefore exists before the invitation goes out and the organizer owns it, so host controls work.

The room code is Meet's to mint: it is read-only on
[its external API](https://github.com/suitenumerique/meet/blob/main/docs/openapi.yaml), so a caller
learns a room's address by being told what was created. Hence this endpoint rather than a link composed
client-side.

Enabled by `meet.enabled=true`; see
[the configuration page](../configuration/configuration.md#meet-integration-meetenabledtrue).

---

## Create a video conference room

```
POST /api/videoconference
```

Authenticated as the calendar user. No request body. The room is created in that user's name, which is
what makes them its owner.

**Responses**

| Status | Body | Description |
|--------|------|-------------|
| `201 Created` | `{"url": "..."}` | The room was created |
| `404 Not Found` | empty | Meet is not configured on this deployment |
| `502 Bad Gateway` | empty | Meet refused the creation or could not be reached |

```json
{
    "url": "https://meet.example.com/vep-txbc-trh"
}
```

Without Meet the route is not bound at all, so the `404` comes from the router: a client that gets one
falls back to generating a room code of its own.

---

## Booking links

Booking links have no client to call this endpoint for them: the booker is an anonymous visitor and the
organizer is not in the request. The room is created when the ICS is built, in the name of the booking
link owner, so the same rooms come out of both paths.
