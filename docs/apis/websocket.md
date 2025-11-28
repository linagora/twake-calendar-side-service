# WebSocket API

The Side server exposes a WebSocket endpoint allowing clients to subscribe to calendar changes in real-time.  

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
The client can register one or multiple calendars to listen for updates.
Format: 
```json
{
  "register": [
    "/calendars/userA/12345",
    "/calendars/userB/67890"
  ]
}
```

#### Unregister
The client can remove subscriptions.
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
    "/calendars/userA/12345"
  ],
  "unregister": [
    "/calendars/userC/99999"
  ]
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
    "/calendars/userC/99999" : "internal error"
  }
}
```

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