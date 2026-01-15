# Webadmin API

The Side server exposes a webadmin based administration Rest API that follows overall 
[the one](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/operate/webadmin.html)
implemented in Apache James.

This includes:

 - [Domain routes](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/operate/webadmin.html#_administrating_domains)
   (Without domain alias support)
 - [User routes](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/operate/webadmin.html#_administrating_users)
   (only user crud, no support for delegation, from header, and JMAP identites). 
 - [Webamin tasks routes](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/operate/webadmin.html#_task_management) 
    backed by an InMemory, node local, task manager.
 - [Healthchecks routes](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/operate/webadmin.html#_healthcheck) 
    The following healthchecks are implemented: `Guice application lifecycle`, `LDAP User Server`, `RabbitMQ backend`, `OpenSearch Backend`, `Redis backend`

It embeds a [Prometheus compatible](https://prometheus.io/) metric endpoint available via `GET /metrics`.

Additionally, the following endpoints are implemented, and are detailed below.

## Back-channel logout endpoint

```
POST /add-revoked-token
```

Can be configured in OpenID identity provider in order to support back-channel logout.

## Calendar users routes

Allow to oversee users that registered and used the DAV server.

### Listing registered users

```
GET /registeredUsers
```

Will return the list of registered users:

```
[
  {
   "email": "james@linagora.com",
   "firstname": "James",
   "lastname": "Bond",
   "id": "248y230r2c"
  },
  ...
]
```

### Registration of a new user

```
POST /registeredUsers
{
 "email": "james@linagora.com",
 "firstname": "James",
 "lastname": "Bond",
 "id": "248y230r2c"
}
```

Returns 201 created status code.

### Testing existance of a user

```
HEAD /registeredUsers?email=james@linagora.com
```

Returns a status code of 200 if the user is registered, 400 instead.

### Updating a user registration

```
PATCH /registeredUsers?=248y230r2c
{
 "email": "james2@linagora.com",
 "firstname": "James2",
 "lastname": "Bond2"
}
```

Will set the fields of the user to the following value.

Status code: 204

### Import LDAP users

```
POST /registeredUsers/tasks?task=importFromLDAP&usersPerSecond=100
```

Will collect all users from LDAP and add them to twake calendar if not exist.

The query parameter `usersPerSecond` controls the concurrency. Defaults to 100.

This endpoint returns a webdmin task with the following additional information:

- processedUserCount: integer
- failedUserCount: integer

### Add missing fields to registered users

```
POST /registeredUsers?action=addMissingFields
```

Will add missing `email` and `firstnames` fields to existing registered users that don't have them.
This is a migration task to update legacy user documents.

The `email` field is extracted from `accounts.emails[0]`.
The `firstnames` field is computed by splitting the `firstname` field by spaces (e.g., "Jean Paul" becomes ["Jean", "Paul"]).

This endpoint returns a webadmin task with the following additional information:

```json
{
  "type": "add-missing-fields",
  "timestamp": "2025-11-24T10:15:30.00Z",
  "processedUsers": 100,
  "upgradedUsers": 25,
  "errorCount": 0
}
```

Where:
- `processedUsers`: Total number of users processed
- `upgradedUsers`: Number of users that were updated with missing fields
- `errorCount`: Number of errors encountered during migration

Status codes:
- `201`: Task successfully submitted
- `400`: Invalid action parameter

## Domain member synchronisation

Only enabled if LDAP is configured.

```
POST /addressbook/domain-members?task=sync
```

Will return a webadmin task with the following additional information:

```
"additionalInformation": {
    "type": "sync-domain-members-contacts-ldap-to-dav",
    "domain": null,
    "timestamp": "${json-unit.any-string}",
    "addedCount": 1,
    "addFailureContacts": [],
    "updatedCount": 0,
    "updateFailureContacts": [],
    "deletedCount": 0,
    "deleteFailureContacts": []
}
```

## Calendar events

### Calendar event reindexing

```
POST /calendars?task=reindex?eventsPerSecond=100
```

Will iterate all registered user calendar and reindex their events.

The query parameter `eventsPerSecond` controls the concurrency. Defaults to 100.

This endpoint returns a webdmin task with the following additional information:

 - processedEventCount: integer
 - failedEventCount: integer
 

### Alarm rescheduling

```
POST /calendars?task=scheduleAlarms?eventsPerSecond=100
```

Will iterate all registered user calendar and reschedule future alarms.

The query parameter `eventsPerSecond` controls the concurrency. Defaults to 100.

This endpoint returns a webdmin task with the following additional information:

- processedEventCount: integer
- failedEventCount: integer

### Calendar event archival

Calendar events can be archived into a dedicated archival calendar using the Webadmin task framework.
This operation is asynchronous and supports both **all users** and **single user** modes.

#### Archive events of all users

```
POST /calendars?task=archive
```

This endpoint iterates over **all registered users** and archives their calendar events matching the provided criteria.

##### Supported query parameters

| Parameter             | Type     | Optional | Description                                                                 |
|-----------------------|----------|----------|-----------------------------------------------------------------------------|
| `createdBefore`       | duration | yes      | Archive events whose `DTSTAMP` is before now minus the given duration (e.g. `5d`, `12h`, `1y`) |
| `lastModifiedBefore`  | duration | yes      | Archive events whose `LAST-MODIFIED` is before now minus the given duration (e.g. `5d`, `12h`, `1y`) |
| `masterDtStartBefore` | duration | yes      | Archive events whose master `DTSTART` is before now minus the given duration (e.g. `5d`, `12h`, `1y`) |
| `isRejected`          | boolean  | yes      | When `true`, archive only events rejected by the user                         |
| `isNotRecurring`      | boolean  | yes      | When `true`, archive only non-recurring events (events without RRULE, RDATE, or RECURRENCE-ID) |
| `eventsPerSecond`     | integer  | yes      | Throttling parameter controlling processing speed (default: `100`)          |

- When **no criteria parameter is provided**, all events are archived.
- All criteria are combined using **AND** logic.

Example:

```
POST /calendars?task=archive&createdBefore=5d&isRejected=true
```

#### Archive events of a single user

```
POST /calendars/{username}?task=archive
```

Archives calendar events **only for the specified user**.

The same query parameters are supported as for the all-users endpoint.

- When **no criteria parameter is provided**, all events of the user are archived.
- If the user does not exist, the request fails.

Example:

```
POST /calendars/john.doe@linagora.com?task=archive&lastModifiedBefore=30d
```

#### Task response

Both endpoints return a Webadmin task:

```json
{
  "taskId": "b7c8c3b0-5c5b-4e89-9e56-1a9f0d2e3a42"
}
```

Task details can be retrieved via:

```
GET /tasks/{taskId}
GET /tasks/{taskId}/await
```

Example task result:

```json
{
  "status": "completed",
  "additionalInformation": {
    "archivedEventCount": 12,
    "failedEventCount": 0,
    "criteria": {
      "createdBefore": "2025-12-22T00:00:00Z",
      "lastModifiedBefore": null,
      "masterDtStartBefore": null,
      "rejectedOnly": true,
      "isNotRecurring": true
    }
  }
}
```

Where:
- `archivedEventCount`: Number of events successfully archived
- `failedEventCount`: Number of events that failed to be archived
- `criteria`: Effective archival criteria applied for the task

**Note:** When using the single-user archival endpoint (`POST /calendars/{username}?task=archive`),
the task `additionalInformation` also contains a `targetUser` property indicating the archived user.

#### Validation and error cases

| Scenario | Status |
|--------|--------|
| Unknown user (single-user endpoint) | `404 Not Found` |
| Invalid query parameter | `400 Bad Request` |

## Resource routes

### Listing resources

```
GET /resources
```

Will list exising resources:

```
[
  {
    "name": "Resource name",
    "deleted": false,
    "description": "Descripting",
    "id": "RESOURCE_ID_1",
    "icon": "laptop",
    "domain": "linagora.com",
    "creator":"user1@linagora.com",
    "administrators": [
      {"email": "user1@linagora.com"},
      {"email": "user2@linagora.com"}
    ]
  },
  {
    "name": "Resource name",
    "deleted": false,
    "description": "Descripting",
    "id": "RESOURCE_ID_2",
    "creator":"user3@twake.app",
    "icon": "laptop",
    "domain": "twake.app",
    "administrators": [
      {"email": "user3@twake.app"},
      {"email": "user4@twake.app"}
    ]
  }
]
```

The `domain` query parameter allow filtering resources by domain.

Eg:

```
GET /resources?domain=linagora.com
```

Status codes:
 - 200 when returning results
 - 400 when domain is either invalid or do not exist

### Getting a specific resource

```
GET /resources/RESOURCE_ID_2
```

Will return the corresponding resource:

```
  {
    "name": "Resource name",
    "deleted": false,
    "description": "Descripting",
    "id": "RESOURCE_ID_2",
    "creator":"user3@twake.app",
    "icon": "laptop",
    "domain": "twake.app",
    "administrators": [
      {"email": "user3@twake.app"},
      {"email": "user4@twake.app"}
    ]
  }
```

Status code: 404 if not found, 200 otherwise.

### Marking a resource as deleted

```
DELETE /resources/RESOURCE_ID_2
```

Will mark the resource as deleted.

Status code: 404 if not found, 204 otherwise.

### Creating a resource

```
POST /resources
{
  "name": "Resource name",
  "description": "Descripting",
  "creator":"user1@linagora.com",
  "icon": "laptop",
  "domain": "linagora.com",
  "administrators": [
    {"email": "user1@linagora.com"},
    {"email": "user2@linagora.com"}
  ]
}
```

Will create the following resource.

Status codes:
 - 201 if created. Location includes the URL allowing to read resource details
 - 400 if invalid: the creator/domain/administrator do not exceed or extra fields / invalid JSON

A resource without administrator is not subject to the validation flow: any event request with this resource is 
automatically accepted.

Please note that resource administrators:
 - are emails upon events created that book the resource
 - have delegation write access to the calendar of the resource

### Updating a resource

```
PATCH resources/RESOURCE_ID
{
  "name": "Resource name 2",
  "description": "Descripting 2",
  "icon": "battery",
  "administrators": [
    {"email": "user2@linagora.com"}
  ]
}
```

Would update the resource accordingly. Each field is nullable and if unspecified the field is not updated.

Status code: 204 if updated, 400 if invalid eg administrator not found, 404 if the resource is not found.



Please note that resource administrators:
 - are emails upon events created that book the resource
 - have delegation write access to the calendar of the resource. Removing administrator will revoke this delegation 
right.

### Repositioning write right for admins on resource calendar

```
POST /resources?task=repositionWriteRights
```

Will iterate on each resource and ensures current administrators have delegation write access to the resource calendar,
allowing the to accept, reject and counter events in the name of the resource.

Note that existing delegation write to no longer existing users will not be revoked.

## Domain admins routes

Manage the list of administrators (admins) for each domain.

### Listing domain administrators

```
GET /domains/{domainName}/admins
```

Example:

```
GET /domains/linagora.com/admins
```

Returns the list of admins of the domain:

```json
[
  "user1@linagora.com",
  "user2@linagora.com"
]
```

**Status codes**:
- `200` when returning the list (can be empty if the domain exists but has no admins).
- `404` when the domain does not exist.
- `400` when `domainName` has an invalid format.

---

### Adding a domain administrator

```
PUT /domains/{domainName}/admins/{username}
```

Example:

```
PUT /domains/linagora.com/admins/user1@linagora.com
```

Adds the user `user1@linagora.com` as an admin of domain `linagora.com`.

**Status codes**:
- `204` if successfully added (idempotent: calling multiple times with the same user still returns `204`).
- `404` if the domain or user does not exist.
- `400` if `domainName` or `username` has an invalid format.

---

### Revoking domain administrator rights

```
DELETE /domains/{domainName}/admins/{username}
```

Example:

```
DELETE /domains/linagora.com/admins/user1@linagora.com
```

Revokes administrator rights of user `user1@linagora.com` for domain `linagora.com`.

**Status codes**:
- `204` if successful, even if the user exists but was not an admin (idempotent).
- `404` if the domain or user does not exist.
- `400` if `domainName` or `username` has an invalid format.  
