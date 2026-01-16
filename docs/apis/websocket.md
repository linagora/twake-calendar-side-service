# WebSocket API

The Side server exposes a WebSocket endpoint allowing clients to subscribe to
**Calendar and Address Book changes** in real time, as well as receive **display alarm notifications**.

This includes:

- Calendar sync token updates
- Calendar import events (ICS)
- Address Book import events (vCard)
- Display alarm notifications

## 1. Endpoint 

```http
GET /ws?ticket={ticketValue}
```

If the ticket is invalid or expired, the server rejects the WebSocket upgrade:

```http
HTTP/1.1 401 Unauthorized
```

- Protocol: WebSocket
- Authentication: required via ticket (see [Ticket Authentication](./ticketAuthentication.md))
- Ping support: server sends periodic PING frames for connection health checks

## 2. Message types

All messages exchanged over WebSocket use JSON text frames.

### Client → Server

#### Register
The client can register one or multiple **resources** to listen for updates.    
Supported resource types:
- Calendars
- Address Books

Format: 
```json
{
  "register": [
    "/calendars/userA/12345",
    "/calendars/userB/67890",
    "/addressbooks/userA/collected"
  ]
}
```

#### Unregister
The client can remove existing subscriptions.
Format:
```json
{
  "unregister": [
    "/calendars/userA/12345"
  ]
}
```

#### Mixed request
Client may send both in a single message:
Format:
```json
{
  "register": [
    "/calendars/userA/12345",
    "/addressbooks/userA/collected"
  ],
  "unregister": [
    "/calendars/userC/99999"
  ]
}
```

#### Enable/Disable Display Notification
The client can enable or disable display alarm notifications. When enabled, the client will receive alarm notifications for events with `ACTION=DISPLAY` alarms.

Enable:
```json
{
  "enableDisplayNotification": true
}
```

Disable:
```json
{
  "enableDisplayNotification": false
}
```

---

### Server → Client

#### Register / Unregister response
```json
{
  "registered": [
    "/calendars/userA/12345"
  ],
  "notRegistered": {
    "/calendars/userB/67890" : "Forbidden"
  },
  "unregistered": [
    "/calendars/userA/12345"
  ],
  "notUnregistered": {
    "/calendars/userC/99999" : "NotFound"
  }
}
```

Possible error values:

| Value          | Meaning |
|---------------|--------|
| Forbidden     | The user has no access rights to the resource |
| NotFound      | The resource does not exist |
| InternalError | An unexpected server-side error |

#### Calendar Event push

Whenever a change happens on a registered calendar (new event, update, deletion, sync-token update), the side-service
pushes an event message to the client.
```json
{
  "/calendars/userA/12345": {
    "syncToken": "http://sabre.io/ns/sync/5678"
  }
}
```

#### Calendar import event (ICS)

When a calendar import is triggered, the server notifies subscribed clients of the import result.
```json
{
  "/calendars/userA/12345": {
    "imports": {
      "import-123": {
        "status": "completed",
        "succeedCount": 10,
        "failedCount": 2
      }
    }
  }
}
```

#### Address Book import event (vCard)

When an address book import is triggered, the server notifies subscribed clients of the import result.

```json
{
  "/addressbooks/userA/collected": {
    "imports": {
      "import-456": {
        "status": "completed",
        "succeedCount": 5,
        "failedCount": 0
      }
    }
  }
}
```

#### Display Notification response
Response when enabling or disabling display notifications:
```json
{
  "displayNotificationEnabled": true
}
```
or
```json
{
  "displayNotificationEnabled": false
}
```

#### Display Alarm push
When an alarm with `ACTION=DISPLAY` triggers for an event, the server pushes an alarm notification to all WebSocket clients that have enabled display notifications for that user.

```json
{
  "alarms": [
    {
      "eventSummary": "Team Meeting",
      "eventURL": "/calendars/baseId/calendarId/event-uid.ics",
      "eventStartTime": "2025-01-15T10:00:00Z"
    }
  ]
}
```

| Field | Description |
|-------|-------------|
| eventSummary | The SUMMARY property of the calendar event |
| eventURL | The path to the event ICS file |
| eventStartTime | The start time of the event in ISO 8601 format |

## Notes

- A WebSocket client may subscribe to multiple calendars and address books simultaneously.
