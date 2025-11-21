# Ticket Authentication API

This document describes the Ticket Authentication endpoints introduced in the side service.  
---

## 1. Purpose

Ticket Authentication provides a lightweight API for generating short‑lived tickets.  
These tickets are used mainly for authenticating WebSocket connections.

---

## 2. Endpoints

### **POST /ws/ticket**
Generate a new ticket for the authenticated user.

**Sample request**
```
POST /ws/ticket
Authorization: Bearer <oidc-token>
```

**Sample response**
```
HTTP/1.1 200 OK
{
    "clientAddress": "127.0.0.1",
    "value": "e7321534-b843-45aa-bca7-157f84dca424",
    "generatedOn": "2025-11-21T03:17:08Z",
    "validUntil": "2025-11-21T03:18:08Z",
    "username": "bob@open-paas.ltd"
}
```

### **DELETE /ws/ticket**
Invalidate an existing ticket

**Sample request**
```
DELETE /ws/ticket?ticket=TICKET_v1_xxx
```

**Sample response**
```
204 No Content
```