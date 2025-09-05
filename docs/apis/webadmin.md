# Webadmin API

The Side server exposes a webadmin based administration Rest API that follows overall 
[the one](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/operate/webadmin.html)
implemented in Apache James.

This includes:

 - [Domain routes](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/operate/webadmin.html#_administrating_domains)
   (Without domain alias support)
 - [User routes](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/operate/webadmin.html#_administrating_users)
   (only user crud, no support for delegation, from header, and JMAP identites). 
 - [Webamin tasks route](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/operate/webadmin.html#_task_management) 
    backed by an InMemory, node local, task manager.

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