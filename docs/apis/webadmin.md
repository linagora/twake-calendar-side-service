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

### Testing existence of a user

```
HEAD /registeredUsers?email=james@linagora.com
```

Returns a status code of 200 if the user is registered, 400 instead.

### Deleting a registered user

```
DELETE /registeredUsers?email=james@linagora.com
```

Deletes the registered user matching the given email.

Status codes:
- 204 if deleted
- 400 if the email query parameter is missing
- 404 if the user does not exist

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

### 1. Synchronize all domains
```
POST /addressbook/domain-members?task=sync
```

Synchronizes LDAP members for **all existing domains**.

Optional query parameters:
- `ignoredDomains` : [String] Comma-separated list of domains to exclude from synchronization
- `ldapFilter` : [String] Optional, URL-encoded LDAP search filter (RFC 4515). See [Restricting members with an LDAP filter](#restricting-members-with-an-ldap-filter).

Example:

```
POST /addressbook/domain-members?task=sync&ignoredDomains=twake.app,example.org
```

### 2. Synchronize a single domain
```
POST /addressbook/domain-members/{domain}?task=sync
```

Synchronizes LDAP members only for the specified domain.

Optional query parameters:
- `ldapFilter` : [String] Optional, URL-encoded LDAP search filter (RFC 4515). See [Restricting members with an LDAP filter](#restricting-members-with-an-ldap-filter).

### Restricting members with an LDAP filter

By default the synchronization selects every LDAP entry whose `objectClass` matches the configured user
object class and whose `mail` belongs to the domain. The optional `ldapFilter` parameter lets you narrow
that selection: the provided filter is **AND-combined** with the default one.

A typical use case is hiding a *secret department* from the global `Domain Members` address book while still
synchronizing everyone else. Pass an exclusion filter so the secret department is never projected:

```
POST /addressbook/domain-members?task=sync&ldapFilter=(!(departmentNumber=SECRET))
```

> ⚠️ The synchronization computes a diff and **removes** from the `Domain Members` address book every
> contact that is no longer returned by LDAP. As a consequence, a filtered synchronization deletes the
> excluded members from the address book. You must therefore **always** run the synchronization with the
> same filter — a subsequent unfiltered synchronization would re-add the previously excluded members.

An invalid `ldapFilter` value results in a `400 Bad Request`.

### Task additional information

Both endpoints will return a webadmin task with the following additional information:

```
"additionalInformation": {
    "type": "sync-domain-members-contacts-ldap-to-dav",
    "domain": null,
    "ignoredDomains": [ "twake.app" ],
    "ldapFilter": "(!(departmentNumber=SECRET))",
    "timestamp": "${json-unit.any-string}",
    "addedCount": 1,
    "addFailureContacts": [],
    "updatedCount": 0,
    "updateFailureContacts": [],
    "deletedCount": 0,
    "deleteFailureContacts": []
}
```

`ldapFilter` is only present when the parameter was supplied.

## Delete domain members contacts

Removes the contacts stored in the `Domain Members` address book. This does **not** touch LDAP; it only
clears the contacts that were projected into the DAV (CardDav) `domain-members` address book.

Only enabled if LDAP is configured.

### 1. Delete contacts of all domains
```
DELETE /addressbook/domain-members
```

Removes the `Domain Members` contacts of **all existing domains**.

Optional query parameters:
- `ignoredDomains` : [String] Comma-separated list of domains to exclude from the deletion

Example:

```
DELETE /addressbook/domain-members?ignoredDomains=twake.app,example.org
```

### 2. Delete contacts of a single domain
```
DELETE /addressbook/domain-members/{domain}
```

Removes the `Domain Members` contacts only for the specified domain.

Status codes:
- `201`: Task successfully submitted
- `400`: Invalid domain or `ignoredDomains` parameter
- `404`: The specified domain does not exist (single-domain endpoint only)

### Task additional information

Both endpoints will return a webadmin task with the following additional information:

```
"additionalInformation": {
    "type": "clear-domain-members-contacts-dav",
    "domain": "twake.app",
    "ignoredDomains": null,
    "timestamp": "${json-unit.any-string}",
    "deletedCount": 12,
    "deleteFailureContacts": []
}
```

Where `domain` is set for the single-domain endpoint and `ignoredDomains` is set for the all-domains endpoint.

## Calendar events

### Calendar event reindexing

```
POST /calendars?task=reindex&eventsPerSecond=100&calendarsConcurrency=1
```

Will iterate all registered user calendars and reindex their events.

The query parameter `eventsPerSecond` controls the indexing rate. Defaults to 100.

The query parameter `calendarsConcurrency` controls how many calendars can be exported and parsed concurrently for a user. Defaults to 1.

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

All resource routes are scoped under a domain: `/domains/{domain}/resources`.

### Listing resources

```
GET /domains/linagora.com/resources
```

Will list existing resources for that domain:

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
  }
]
```

Status codes:
 - 200 when returning results
 - 400 when domain name is malformed
 - 404 when domain does not exist

### Getting a specific resource

```
GET /domains/linagora.com/resources/RESOURCE_ID
```

Will return the corresponding resource:

```
  {
    "name": "Resource name",
    "deleted": false,
    "description": "Descripting",
    "id": "RESOURCE_ID",
    "creator":"user1@linagora.com",
    "icon": "laptop",
    "domain": "linagora.com",
    "administrators": [
      {"email": "user1@linagora.com"},
      {"email": "user2@linagora.com"}
    ]
  }
```

Status codes: 404 if domain or resource not found (or resource belongs to another domain), 200 otherwise.

### Marking a resource as deleted

```
DELETE /domains/linagora.com/resources/RESOURCE_ID
```

Will mark the resource as deleted.

Status codes: 404 if domain or resource not found, 204 otherwise.

### Creating a resource

```
POST /domains/linagora.com/resources
{
  "name": "Resource name",
  "description": "Descripting",
  "creator":"user1@linagora.com",
  "icon": "laptop",
  "administrators": [
    {"email": "user1@linagora.com"},
    {"email": "user2@linagora.com"}
  ]
}
```

The `administrators` field is optional. Omitting it (or providing an empty list) creates a resource with no administrator:

```
POST /domains/linagora.com/resources
{
  "name": "Resource name",
  "description": "Descripting",
  "creator":"user1@linagora.com",
  "icon": "laptop"
}
```

Will create the following resource.

Status codes:
 - 201 if created. Location header contains the URL to read resource details
 - 400 if invalid: creator/administrator do not exist, or extra fields / invalid JSON
 - 404 if domain does not exist

A resource without administrator is not subject to the validation flow: any event request with this resource is
automatically accepted.

Please note that resource administrators:
 - are emailed upon events created that book the resource
 - have delegation write access to the calendar of the resource

### Updating a resource

```
PATCH /domains/linagora.com/resources/RESOURCE_ID
{
  "name": "Resource name 2",
  "description": "Descripting 2",
  "icon": "battery",
  "administrators": [
    {"email": "user2@linagora.com"}
  ]
}
```

Would update the resource accordingly. Each field is optional; omitting a field leaves it unchanged.

Status codes: 204 if updated, 400 if invalid (e.g. administrator not found), 404 if domain or resource not found.

Please note that resource administrators:
 - are emailed upon events created that book the resource
 - have delegation write access to the calendar of the resource. Removing an administrator revokes this delegation right.

### Repositioning write rights for admins on resource calendar

```
POST /domains/linagora.com/resources?task=repositionWriteRights
```

Will iterate on each resource and ensure current administrators have delegation write access to the resource calendar,
allowing them to accept, reject and counter events in the name of the resource.

Note that existing delegation write access granted to users no longer listed as administrators will not be revoked.

## Domain registered users routes

Domain-scoped mirror of the `/registeredUsers` routes, allowing multi-tenant safe access.
The global `/registeredUsers` routes remain available for global admin operations.

### Listing users of a domain

```
GET /domains/linagora.com/registeredUsers
```

Returns only users whose email belongs to `linagora.com`:

```json
[
  {
    "email": "james@linagora.com",
    "firstname": "James",
    "lastname": "Bond",
    "id": "248y230r2c"
  }
]
```

Optional `?email=` filter to retrieve a single user:

```
GET /domains/linagora.com/registeredUsers?email=james@linagora.com
```

Returns 404 if the user does not exist or belongs to another domain.

Status codes:
- 200 on success
- 400 if domain name is malformed
- 404 if domain does not exist

### Adding a user to a domain

```
POST /domains/linagora.com/registeredUsers
{
  "email": "james@linagora.com",
  "firstname": "James",
  "lastname": "Bond"
}
```

The email domain must match the URL domain.

Status codes:
- 201 if created
- 400 if a required field is missing or the email domain does not match the URL domain
- 404 if domain does not exist
- 409 if a user with that email already exists

### Testing existence of a user within a domain

```
HEAD /domains/linagora.com/registeredUsers?email=james@linagora.com
HEAD /domains/linagora.com/registeredUsers?id=248y230r2c
```

Returns 200 if the user exists and belongs to that domain, 404 otherwise.

### Updating a user within a domain

```
PATCH /domains/linagora.com/registeredUsers?id=248y230r2c
{
  "email": "james2@linagora.com",
  "firstname": "James2",
  "lastname": "Bond2"
}
```

Status codes:
- 204 if updated
- 400 if required fields are missing
- 404 if the user does not exist or belongs to another domain
- 409 if the new email is already taken

### Deleting a user within a domain

```
DELETE /domains/linagora.com/registeredUsers?email=james@linagora.com
```

Deletes the registered user matching the given email when the user belongs to the domain in the URL.

Status codes:
- 204 if deleted
- 400 if the domain name is malformed or the email query parameter is missing
- 404 if the domain does not exist, the user does not exist, or the user belongs to another domain

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

## Domain settings routes

Manage per-domain configuration settings.

### Retrieving domain settings

```
GET /domains/{domain}/settings
```

Example:

```
GET /domains/linagora.com/settings
```

Returns the explicitly configured settings for the domain alongside the effective resolved values. Fields at the top level set to `null` are not configured for this domain. The `resolved` object always contains the effective value after applying configuration file and system defaults as fallback.

```json
{
  "userSearchMode": "limited",
  "resourceSearchEnabled": null,
  "defaultCalendarPublicVisibility": null,
  "calendarPublicVisibilitySettingEnabled": null,
  "resolved": {
    "userSearchMode": "limited",
    "resourceSearchEnabled": true,
    "defaultCalendarPublicVisibility": "read",
    "calendarPublicVisibilitySettingEnabled": true
  }
}
```

**Field values** (top-level and `resolved`):
- `userSearchMode`: `"enabled"` | `"limited"` | `"disabled"` (top-level also allows `null`)
- `resourceSearchEnabled`: `true` | `false` (top-level also allows `null`)
- `defaultCalendarPublicVisibility`: `"read"` | `"private"` (top-level also allows `null`)
- `calendarPublicVisibilitySettingEnabled`: `true` | `false` (top-level also allows `null`)

**Status codes**:
- `200`: settings returned (all fields `null` when nothing has been configured)
- `400`: domain name has an invalid format
- `404`: domain does not exist

---

### Updating domain settings

```
PUT /domains/{domain}/settings
{
  "userSearchMode": "limited",
  "resourceSearchEnabled": false,
  "defaultCalendarPublicVisibility": null,
  "calendarPublicVisibilitySettingEnabled": true
}
```

All fields are required. A `null` value clears the setting for that field, causing it to fall back to the system default.

**Status codes**:
- `204`: settings saved
- `400`: a required field is missing, an unknown field is present, or a field value is invalid
- `404`: domain does not exist

---

### Partially updating domain settings

```
PATCH /domains/{domain}/settings
{
  "userSearchMode": "limited",
  "resourceSearchEnabled": null,
  "calendarPublicVisibilitySettingEnabled": false
}
```

All fields are optional. Only the fields present in the body are updated:
- A non-null value updates that field to the given value
- A `null` value clears that field (reverts to system default at resolution time)
- An absent field is left unchanged

**Status codes**:
- `204`: patch applied
- `400`: a field value is invalid
- `404`: domain does not exist

---

## User calendar management routes

Administrative management of user calendars. These routes proxy the esn-sabre JSON API:
each call is translated into the corresponding DAV server call, authenticated by impersonating
the targeted user. The user is identified by his email address; the associated technical user
id is resolved automatically.

All routes return:
- `400`: the username is invalid or the request body is malformed
- `404`: the user is not registered (`User does not exist`)

### Listing the calendars of a user

```
GET /users/{usernameToBeUsed}/calendars
```

Example:

```
GET /users/btellier@linagora.com/calendars
```

Proxies `GET /calendars/{userId}.json?personal=true&sharedDelegationStatus=accepted&sharedPublicSubscription=true&withRights=true`
on the DAV server and returns the DAV server response verbatim. This includes personal calendars,
accepted delegations and public subscriptions, with their rights (`acl` and `invite` fields).

```json
{
  "_links": {"self": {"href": "/calendars/5f50a663bdaffe002629099c.json"}},
  "_embedded": {
    "dav:calendar": [
      {
        "_links": {"self": {"href": "/calendars/5f50a663bdaffe002629099c/5f50a663bdaffe002629099c.json"}},
        "dav:name": "#default",
        "apple:color": "#006400",
        "caldav:description": "",
        "acl": ["..."],
        "invite": ["..."]
      }
    ]
  }
}
```

**Status codes**:
- `200`: the calendar list is returned

### Creating a calendar

```
POST /users/{usernameToBeUsed}/calendars
{
  "id": "0e26ee47-cc4b-4aaa-8447-12588fdb11f1",
  "dav:name": "My calendar",
  "apple:color": "#F5CFD0",
  "caldav:description": "Some description"
}
```

Supported fields:
- `id` (optional): the collection identifier of the calendar to create. Generated (random UUID) when absent.
- `dav:name` (required): display name of the calendar
- `apple:color` (optional): color of the calendar
- `caldav:description` (optional): description of the calendar

Returns the identifier of the created calendar:

```json
{"id": "0e26ee47-cc4b-4aaa-8447-12588fdb11f1"}
```

**Status codes**:
- `201`: the calendar was created
- `400`: `dav:name` is missing or an unknown field is present

### Deleting a calendar

```
DELETE /users/{usernameToBeUsed}/calendars/{calendarId}
```

Example:

```
DELETE /users/btellier@linagora.com/calendars/0c5413b9-2ca3-4669-ae44-0d8083344ca8
```

Where `{calendarId}` is the collection identifier of the calendar, as returned by the listing route.

Also works for calendars obtained through delegation and subscriptions to public calendars:
deleting them removes the delegated copy / the subscription of this user, not the source calendar.

**Status codes**:
- `204`: the calendar was deleted
- `404`: the user or the calendar does not exist

### Updating details of a calendar

```
PATCH /users/{usernameToBeUsed}/calendars/{calendarId}
{
  "dav:name": "B test 2",
  "caldav:description": "sample desc",
  "apple:color": "#F5CFD0"
}
```

Proxies a `PROPPATCH` on the calendar. All fields are optional but at least one must be present.
Unknown fields are rejected.

**Status codes**:
- `204`: the calendar was updated
- `400`: empty body or unknown field
- `404`: the user or the calendar does not exist

### Changing the public visibility of a calendar

```
POST /users/{usernameToBeUsed}/calendars/{calendarId}/publicRight
{
  "public_right": "{DAV:}read"
}
```

Supported `public_right` values:
- `"{DAV:}read"`: anyone authenticated can read the calendar (public calendar)
- `""`: removes public rights (private calendar)

**Status codes**:
- `204`: the public visibility was updated
- `400`: missing or unsupported `public_right` value
- `404`: the user or the calendar does not exist

### Adding / removing invitees (delegation)

```
POST /users/{usernameToBeUsed}/calendars/{calendarId}/invitee
{
  "share": {
    "set": [
      {"dav:href": "mailto:twake-calendar-dev@linagora.com", "dav:administration": true},
      {"dav:href": "mailto:cmoussu@linagora.com", "dav:read": true},
      {"dav:href": "mailto:xguimard@linagora.com", "dav:read-write": true}
    ],
    "remove": [
      {"dav:href": "mailto:xxx@linagora.com"}
    ]
  }
}
```

The body is proxied verbatim to the DAV server. Each `set` entry grants a right to the given
user (`dav:read`, `dav:read-write` or `dav:administration`), each `remove` entry revokes
the sharing for the given user.

**Status codes**:
- `204`: the sharees were updated
- `400`: missing `share` field, `dav:href` is not a `mailto:` URI, or a `set` entry carries no right
- `404`: the user or the calendar does not exist

## User address book management routes

Administrative management of user address books. These routes proxy the Sabre DAV server,
impersonating the targeted user. The user is identified by their email address; the associated
technical user id is resolved automatically.

All routes return:
- `400`: the username is invalid or the request body is malformed
- `404`: the user is not registered (`User does not exist`)

### Listing the address books of a user

```
GET /users/{username}/addressbooks
```

Example:

```
GET /users/btellier@linagora.com/addressbooks
```

Returns the DAV server response verbatim, including the default `contacts` address book and any custom ones.

```json
{
  "_links": {"self": {"href": "/addressbooks/5f50a663bdaffe002629099c.json"}},
  "_embedded": {
    "dav:addressbook": [
      {
        "_links": {"self": {"href": "/addressbooks/5f50a663bdaffe002629099c/contacts.json"}},
        "dav:name": "My contacts",
        "carddav:description": ""
      }
    ]
  }
}
```

**Status codes**:
- `200`: the address book list is returned

### Creating an address book

```
POST /users/{username}/addressbooks
{
  "id": "0e26ee47-cc4b-4aaa-8447-12588fdb11f1",
  "dav:name": "My Contacts",
  "carddav:description": "Personal contacts"
}
```

Supported fields:
- `id` (optional): the collection identifier of the address book to create. Generated (random UUID) when absent.
- `dav:name` (required): display name of the address book
- `carddav:description` (optional): description of the address book

Returns the identifier of the created address book:

```json
{"id": "0e26ee47-cc4b-4aaa-8447-12588fdb11f1"}
```

**Status codes**:
- `201`: the address book was created
- `400`: `dav:name` is missing or the request body is malformed
- `404`: the user does not exist

### Deleting an address book

```
DELETE /users/{username}/addressbooks/{addressBookId}
```

Example:

```
DELETE /users/btellier@linagora.com/addressbooks/0e26ee47-cc4b-4aaa-8447-12588fdb11f1
```

**Status codes**:
- `204`: the address book was deleted
- `400`: attempting to delete a system address book (e.g. `contacts`)
- `404`: the user or the address book does not exist

### Changing the public visibility of an address book

```
POST /users/{username}/addressbooks/{addressBookId}/publicRight
{
  "public_right": "{DAV:}read"
}
```

Supported `public_right` values:
- `"{DAV:}read"`: anyone authenticated can read the address book (public)
- `""`: removes public rights (private)

**Status codes**:
- `204`: the public visibility was updated
- `400`: missing or unsupported `public_right` value
- `404`: the user or the address book does not exist

### Adding / removing invitees (sharing)

```
POST /users/{username}/addressbooks/{addressBookId}/invitee
{
  "dav:sharee": [
    {"dav:href": "mailto:alice@linagora.com", "dav:share-access": 3},
    {"dav:href": "mailto:bob@linagora.com", "dav:share-access": 5}
  ]
}
```

Each entry in `dav:sharee` sets the access level for the given user. The `dav:share-access` field
accepts integer values between 2 and 5 matching the WebDAV sharing spec:
- `2`: read access
- `3`: read-write access
- `4`: administration access
- `5`: no access (revokes sharing)

**Status codes**:
- `204`: the sharees were updated
- `400`: missing or malformed `dav:sharee` array, or an invalid `dav:share-access` value
- `404`: the user or the address book does not exist

## User booking link routes

These routes let an administrator manage the [booking links](bookingLink.md) of a given user.
They mirror the end-user `/api/booking-links` API but are scoped to an explicit `{username}`
path parameter.

Differences with the end-user API:

- No per-user settings resolution happens here. Availability rules without an explicit
  `timeZone` are interpreted in **UTC**, and an omitted `availabilityRules` on creation stores
  no rule (instead of defaulting to the user's business hours).
- Authentication is the standard WebAdmin one (not the end-user token).

The booking link object and the availability rule object share the same shape as the
[Booking Link API](bookingLink.md#data-model).

### Listing the booking links of a user

```
GET /users/{username}/booking-links
```

Returns the booking links of the user, sorted by update time (most recent first).

```
HTTP/1.1 200 OK
Content-Type: application/json

[
  {
    "publicId": "550e8400-e29b-41d4-a716-446655440000",
    "calendarUrl": "/calendars/67c3a792e4b0884b05ef8aef/67c3a792e4b0884b05ef8aef",
    "durationMinutes": 30,
    "active": true,
    "availabilityRules": [
      { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "17:00", "timeZone": "UTC" }
    ]
  }
]
```

`availabilityRules` is omitted from an entry when not set.

**Status codes**:
- `200`: the list is returned (possibly empty)
- `400`: invalid `username`
- `404`: the user does not exist

### Getting a booking link

```
GET /users/{username}/booking-links/{publicId}
```

**Status codes**:
- `200`: the booking link is returned
- `400`: invalid `username` or `publicId` (not a UUID)
- `404`: the user does not exist, or the booking link does not exist for that user

### Creating a booking link

```
POST /users/{username}/booking-links
{
  "calendarUrl": "/calendars/67c3a792e4b0884b05ef8aef/67c3a792e4b0884b05ef8aef",
  "durationMinutes": 30,
  "active": true,
  "availabilityRules": [
    { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "timeZone": "Europe/Paris" }
  ]
}
```

`calendarUrl`, `durationMinutes` and `active` are required. `availabilityRules` is optional.

```
HTTP/1.1 201 Created
Location: /users/{username}/booking-links/550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{
  "bookingLinkPublicId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Status codes**:
- `201`: the booking link was created
- `400`: invalid `username`, missing/invalid field, unknown rule type, invalid `timeZone`, or the calendar does not exist for that user
- `404`: the user does not exist

### Updating a booking link

```
PATCH /users/{username}/booking-links/{publicId}
{
  "durationMinutes": 60,
  "active": false
}
```

Only the fields present in the body are updated. At least one field must be provided.
Set `availabilityRules` to `null` to remove all rules.

**Status codes**:
- `204`: the booking link was updated
- `400`: invalid `username` or `publicId`, no field provided, invalid value, invalid `timeZone`, or the calendar does not exist for that user
- `404`: the user does not exist, or the booking link does not exist for that user

### Deleting a booking link

```
DELETE /users/{username}/booking-links/{publicId}
```

**Status codes**:
- `204`: the booking link was deleted
- `400`: invalid `username` or `publicId`
- `404`: the user does not exist, or the booking link does not exist for that user

### Resetting the public id of a booking link

```
POST /users/{username}/booking-links/{publicId}/reset
```

Generates a new public id for the booking link, invalidating the old one.

```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "bookingLinkPublicId": "9f4f2166-95c4-4d3e-b421-23dc5e8a1fbb"
}
```

**Status codes**:
- `200`: a new public id was generated and returned
- `400`: invalid `username` or `publicId`
- `404`: the user does not exist, or the booking link does not exist for that user

## Domain-scoped task routes

These routes provide domain-filtered access to the standard webadmin task management endpoints. They are intended for WebAdmin proxies that enforce multi-tenancy based on the domain in the URL. A task is only accessible if it belongs to the specified domain; otherwise a `404` is returned (to avoid leaking task IDs across domains).

The following task types are domain-scoped:

| Task type | Domain resolution |
|-----------|-------------------|
| `calendar-archival` (single-user) | domain extracted from `targetUser` |
| `DeleteUserDataTask` | domain extracted from `username` |
| `sync-domain-members-contacts-ldap-to-dav` (single-domain) | `domain` field in additional information |
| `clear-domain-members-contacts-dav` (single-domain) | `domain` field in additional information |

Multi-domain tasks (e.g. archive all users, sync all domains) are not attributed to any specific domain and are therefore not accessible through these routes.

### Get task status

```
GET /domains/{domain}/tasks/{taskId}
```

Returns the task execution details if the task belongs to the domain. Same response body as `GET /tasks/{taskId}`.

**Status codes**:
- `200`: task found and belongs to the domain
- `400`: invalid task id format
- `404`: domain does not exist, task not found, or task does not belong to the domain

### Await task completion

```
GET /domains/{domain}/tasks/{taskId}/await?timeout=3600s
```

Waits for the task to complete and returns the final execution details. Same semantics as `GET /tasks/{taskId}/await`.

Optional query parameter:
- `timeout`: maximum wait duration (e.g. `3600s`, `1d`). Defaults to 365 days.

**Status codes**:
- `200`: task completed and belongs to the domain
- `400`: invalid task id or timeout format
- `404`: domain does not exist, task not found, or task does not belong to the domain
- `408`: timeout reached before task completion

### Cancel a task

```
DELETE /domains/{domain}/tasks/{taskId}
```

Cancels the task if it belongs to the domain.

**Status codes**:
- `204`: task cancelled (or already completed/cancelled)
- `400`: invalid task id format
- `404`: domain does not exist, task not found, or task does not belong to the domain

## User data deletion

Allows deleting all data associated with a user. This is an asynchronous task that executes multiple deletion steps.

### Deleting user data

```
POST /users/{username}?action=deleteData
```

Example:

```
POST /users/james@linagora.com?action=deleteData
```

Will delete all data associated with the user `james@linagora.com`.

**Query parameters**:
- `action=deleteData` (required): Triggers the deletion task
- `fromStep={stepName}` (optional): Start execution from a specific step, skipping previous ones

**Response**:

Returns a task ID for async tracking:

```json
{
  "taskId": "464269f0-9314-11ef-a339-d76792bfb514"
}
```

**Status codes**:
- `201`: Task successfully submitted
- `400`: Invalid action or missing parameter

### Deletion steps

The deletion task executes the following steps in priority order:

| Step Name | Priority | Description |
|-----------|----------|-------------|
| `DavCalendarDeletionTaskStep` | 1 | Deletes user's calendars and calendar events |
| `DavContactDeletionTaskStep` | 2 | Deletes user's contacts and address books |
| `CalendarSearchDeletionTaskStep` | 10 | Removes indexed calendar events from OpenSearch |
| `OpenPaaSUserDeletionTaskStep` | 1000 | Deletes user from the OpenPaaS user database |

### Running from a specific step

To skip certain deletion steps, use the `fromStep` parameter:

```
POST /users/james@linagora.com?action=deleteData&fromStep=CalendarSearchDeletionTaskStep
```

This will skip `DavCalendarDeletionTaskStep` and `DavContactDeletionTaskStep`, starting directly from `CalendarSearchDeletionTaskStep`.