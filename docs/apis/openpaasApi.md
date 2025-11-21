# OpenPaaS API

The project, in an attempt to be compatible with OpenPaaS legacy Single Page Applications, implement a subset of the
webadmin API.

## Authentication mechanisms

### OIDC

The current project implement [OpenID connect](https://openid.net/specs/openid-connect-core-1_0.html) as an authentication mechanism.

[User auto-provisioning](../features.md#automatic-user-provisioning) is supported but requires specific claims.

Opaque tokens are needed in order to be distinguished from the OpenPaa legacy JWT authentication.

The side service need to be able to introspect those tokens. It also needs to discover user information through the use of 
userinfo endpoint.

The side service caches introspect/userinfo data for a configurable amount of time either locally or in redis. Back 
channel logout will act as a cache invalidation mechanism.

### Basic auth

[Basic auth](https://datatracker.ietf.org/doc/html/rfc7617) is supported.

Additionally, the side service allows BASIC AUTH through a configurable admin account for usage by thrid party apps like
Twake Mail.

### Legacy OpenPaaS JWT tokens

The side service allows to generate OpenPaaS compatible JWTs and accepts them in BEARER HTTP authentication scheme.

Those tokens are gnerated by calling `POST /api/jwt/generate`

This is the mechanism used by legacy Single Page Application to authenticate with esn-sabre DAV server.

### Lemon cookies

Some broken legacy SPA feature (import) do rely directly on the lemon cookie and not i OpenID connect.

In order to fix the implementation of the SPA atop the side service, we provide this authentication mechanism.

### Ticket Authentication

The side service supports ticket-based authentication for cross-API access and WebSocket connections. This mechanism allows users to generate short-lived authentication tickets using their existing credentials (OIDC, Basic Auth, etc.) and then use these tickets to authenticate subsequent requests without re-providing credentials.

**Key features:**
- Short-lived tickets (default: 1 minute validity)
- IP address validation to prevent ticket theft
- Persistent storage support (in-memory or MongoDB)
- Used primarily for WebSocket authentication and cross-API access

Tickets can be used as authentication credentials by passing them as a query parameter (`?ticket=TICKET_v1_xxx`) or in the Authorization header.

For detailed endpoint documentation, see [Ticket Authentication API](ticketAuthentication.md).

## Endpoints

### GET /api/user

```
GET /api/user
```

Will return user details and configuration:

```
{
"id": "abcdef",
"_id": "abcdef",
"accounts": [
    {
        "hosted": true,
        "preferredEmailIndex": 0,
        "type": "email",
        "timestamps": {
            "creation": "1970-01-01T00:00:00.000Z"
        },
        "emails": [
            "btellier@linagora.com"
        ]
    }
],
"isPlatformAdmin": false,
"login": {
    "success": "1970-01-01T00:00:00.000Z",
    "failures": []
},
"configurations": { "modules" : [
    {
        "name": "core",
        "configurations": [
            {
                "name": "davserver",
                "value": {
"frontend": {
    "url": "https://dav.linagora.com"
},
"backend": {
    "url": "https://dav.linagora.com"
}
                }
            },
            {
                "name": "allowDomainAdminToManageUserEmails",
                "value": null
            },
            {
                "name": "homePage",
                "value": null
            },
            {
                "name": "language",
                "value": "en"
            },
            {
                "name": "datetime",
                "value": {
"timeZone": "Europe/Paris",
"use24hourFormat": true
                }
            },
            {
                "name": "businessHours",
                "value": [
                   {
                     "start": "8:0",
                     "end": "19:0",
                     "daysOfWeek": [1, 2, 3, 4, 5]
                   }
                ]
            }
        ]
    },
    {
        "name": "linagora.esn.calendar",
        "configurations": [
            {
                "name": "features",
                "value": {
                  "isSharingCalendarEnabled": true
                }
            },
            {
                "name": "workingDays",
                "value": null
            },
            {
                "name": "hideDeclinedEvents",
                "value": null
            }
        ]
    },
    {
        "name": "linagora.esn.videoconference",
        "configurations": [
            {
                "name": "jitsiInstanceUrl",
                "value": "https://jitsi.linagora.com"
            },
            {
                "name": "openPaasVideoconferenceAppUrl",
                "value": "https://jitsi.linagora.com"
            }
        ]
    },
    {
        "name": "linagora.esn.contact",
        "configurations": [
            {
                "name": "features",
                "value": {
                  "isVirtualFollowingAddressbookEnabled": false,
                  "isVirtualUserAddressbookEnabled": false,
                  "isSharingAddressbookEnabled": true,
                  "isDomainMembersAddressbookEnabled": true
                }
            }
        ]
    },
    {
        "configurations": [
            {
                "name": "alarmEmails",
                "value": null
            }
        ],
        "name": "calendar"
    }
]},
"preferredEmail": "btellier@linagora.com",
"state": [],
"domains": [
    {
        "domain_id": "%s",
        "joined_at": "1970-01-01T00:00:00.000Z"
    }
],
"main_phone": "",
"followings": 0,
"following": false,
"followers": 0,
"emails": [
    "btellier@linagora.com"
],
"firstname": "btellier@linagora.com",
"lastname": "btellier@linagora.com",
"objectType": "user"
}
```

Called by the SPA in order to retrieve user context from an authenticated user.

Extra fields are faked in order to satisfy OpenPaaS legcy SPAs.

### GET /api/users?email=james@bond.fr

```
GET /api/users?email=james@bond.fr
```

Will lookup user with this mail address.

```
[{
   "preferredEmail": "btellier@linagora.com",
    "_id": "abcdef",
    "state": [],
    "domains": [
      {
        "domain_id": "%s",
        "joined_at": "1970-01-01T00:00:00.000Z"
      }
    ],
    "main_phone": "",
    "followings": 0,
    "following": false,
    "followers": 0,
    "emails": [ "btellier@linagora.com" ],
    "firstname": "btellier@linagora.com",
    "lastname": "btellier@linagora.com",
    "objectType": "user"
}]
```

Used by twake mail to retrieve the id of a user from its email prior interacting with its DAV data.

### GET /api/users/{id}

```
GET /api/users/{id}
```

Allows to retrieve user details based on its id.

```
{
   "preferredEmail": "btellier@linagora.com",
    "_id": "abcdef",
    "state": [],
    "domains": [
      {
        "domain_id": "%s",
        "joined_at": "1970-01-01T00:00:00.000Z"
      }
    ],
    "main_phone": "",
    "followings": 0,
    "following": false,
    "followers": 0,
    "emails": [ "btellier@linagora.com" ],
    "firstname": "btellier@linagora.com",
    "lastname": "btellier@linagora.com",
    "objectType": "user"
}
```

Used by the SPAs in order to name ones calendar from its id.

### GET /api/themes/{domainId}

Return always

```
{"logos":{},"colors":{}}
```

Naive implementation to please OpenPaaS SPAs.

### POST /api/configurations

```
POST /api/configurations
[{"name":"core","keys":["davserver"]}]
```

Allows looking up for configuration keys.

```
   [
    {"name":"core","configurations":[
     {"name":"davserver",
       "value":{
         "backend":{"url":"https://dav.linagora.com"},
         "frontend":{"url":"https://dav.linagora.com"}
        }
       }
      ]
     }
    ]
```

Supported configuration keys:

 - `core`
   - `davserver`: advertise to the OpenPaaS SPA dav server location. Server from configuration.
   - `language`: stored in user settings, eg `en`
   - `datetime`: stored in user settings, eg `{"timeZone":"Europe/Paris","use24hourFormat":true}`
   - `businessHours`: stored in user settings, eg `[{"start":"8:0","end":"19:0","daysOfWeek":[1,2,3,4,5]}]`
 - `linagora.esn.contact`
   - `features`: advertised from configuration `{"isVirtualFollowingAddressbookEnabled":false,"isSharingAddressbookEnabled":true,"isVirtualUserAddressbookEnabled":false,"isDomainMembersAddressbookEnabled":true}`
 - `linagora.esn.calendar"`
   - `features`: advertised from configuration `{"isSharingCalendarEnabled": true}`
   - `workingDays`
   - `hideDeclinedEvents`
 - `calendar`
   - `alarmEmails`: allows the user to disable alarm emails.
  - `displayWeekNumbers`: Should the front display week numbers? Defaults to true.
 - `linagora.esn.videoconference`
   - `jitsiInstanceUrl`: URL of the jitsi server. Advertised from configuration
   - `openPaasVideoconferenceAppUrl`: legacy. Points to the jisi url.

### PUT /api/configurations

```
PUT /api/configurations?scope=user
[
 {
  "name": "core",
  "configurations": [
   {
    "name": "language",
    "value": "vi"
   }
  ]
 }
]
```

Allows to update configuration of a user

Status code: 201

### GET /calendar/api/calendars/{calendarHomeId}/{calendarId}/secret-link

```
GET /calendar/api/calendars/{calendarHomeId}/{calendarId}/secret-link
```

Allows to retrieve the secret link associated with a calendar.

Secret links are authenticated with a random string stored and thus the URL is self supportive.

The `shouldResetLink` query parameter (boolean default to false) control if the existing secret link shall be overridden.

Example of generated secret link:

```
GET /api/calendars/{userId}/{calendarId}/calendar.ics?token=xyz
```

### GET /api/calendars/{userId}/{calendarId}/calendar.ics?token=xyz

(no further authenticating needed)

Will return calendar data in an ICS format

### GET /linagora.esn.resource/images/icon/{icon}.svg

Serves binary content corresponding to the SVG of the icon

This [link](https://github.com/linagora/twake-calendar-side-service/tree/main/calendar-rest-api/src/main/resources/icons/resources) 
references all usable icons.

### GET /api/users/{userId}/profile/avatar

Redirects to the user avatar

### GET /api/avatars?emil=btellier@linagora.com

Computes the user avatar by rendering first letter in a fixed color square.

### POST /api/people/search

This badly named HTTP method allows looking up the following resources in an auto complete manner: `user`, `contact`, `resources`. 

Sample payloads:

```
POST /api/people/search
{
 "q" : "naruto",
 "objectTypes" : [ "user", "resource", "contact" ],
  "limit" : 10
}
```

Will return:

```
              [
                  {
                      "id": "2f18b89b-f112-3c4c-9e9f-2cdbff80bd0e",
                      "objectType": "user",
                      "names": [
                          {
                              "displayName": "naruto hokage",
                              "type": "default"
                          }
                      ],
                      "emailAddresses": [
                          {
                              "value": "naruto@open-paas.ltd",
                              "type": "Work"
                          }
                      ],
                      "phoneNumbers": [],
                      "photos": [
                          {
                              "url": "https://twcalendar.linagora.com/api/avatars?email=naruto@open-paas.ltd",
                              "type": "default"
                          }
                      ]
                  },
                  {
                    id: "${json-unit.ignore}",
                    objectType: "contact",
                    names: [ { displayName: "sasuke uchiha", type: "default" } ],
                    emailAddresses: [ { value: "sasuke@domain.tld", type: "Work" } ],
                    phoneNumbers: [],
                    photos: [
                      {
                        url: "https://twcalendar.linagora.com/api/avatars?email=sasuke@domain.tld",
                        type: "default"
                      }
                    ]
                  },
                  {
                    "id": "%s",
                    "objectType": "resource",
                    "names": [ { "displayName": "meeting-room", "type": "default" } ],
                    "emailAddresses": [ { "value": "%s", "type": "default" } ],
                    "phoneNumbers": [],
                    "photos": [ { "url": "https://e-calendrier.avocat.fr/linagora.esn.resource/images/icon/laptop.svg", "type": "default" } ]
                  }
                ]
```

Used by SPAs for auto-complete.

### /api/themes/{domainId}/logo

Redirect to the SPA `/calendar/assets/images/white-logo.svg` own image.

Here to satisfy OpenPaaS SPAs.

### POST /api/jwt/generate

Used to generate a JWT token for the OpenPaaS SPAs. The JWT token is then used to interact with esn-sabre DAV server.

Example:

```
POST /api/jwt/generate
```

Returns:

`"JWT_TOKEN_GOES_HERE_IN_QUOTES"`

### GET /api/domains/{domainId}

Allows for a SPA to retrieve details of a given domain

```
GET /api/domains/{domainId}
```

Will return 

```
{
         "timestamps": {
           "creation": "1970-01-01T00:00:00.000Z"
         },
         "hostnames": ["linagora.com"],
         "schemaVersion": 1,
         "_id": "%s",
         "name": "linagora.com",
         "company_name": "linagora.com",
         "administrators": [ ],
         "injections": [],
         "__v": 0
}
```

Most fields are hard coded to please the SPAs.

### GET /api/technicalToken/introspect

Technical tokes are used by the side service to interact with Sabre (this is mandatory for resource management and
domain address book member management).

```
GET /api/technicalToken/introspect
```

With the following header:

```
X-TECHNICAL-TOKEN: xyz
```

Will return 200 if the token is valid and 404 otherwise (non-existent or expired)

### GET /calendar/api/calendars/event/participation

This allows excal SPA to pilot the action clicked by the user.

Don't trust the GET verb, this WILL trigger a side effect: update the remote user participation.

It uses a `jwt` query parameter build in the emailed link.

```
GET /calendar/api/calendars/event/participation?jwt=xxx
```

A participation token need to have the following claims:
 - `attendeeEmail`
 - `organizerEmail`
 - `uid`
 - `calendarURI`
 - `action`: one of `ACCEPTED`, `REJECTED`, `TENTATIVE`

### POST /api/files

```
POST /api/import?name=calendar.ics&size=567&mimetype=text/calendar
BEGIN:VCALENDAR
VERSION:2.0
END:VCALENDAR
```

Will return the id of the uploaded file.

```
{
  "_id": "xyz"
}
```

Supported mime types: `text/calendar` and `text/vcard`.

Size is purely declarative and ignored.

Body is the raw ICS or VCard data.

Needed as import is a two step process where the SPAs first uploads data to the side service before importing it in the DAV server.

We implemented the following limitations fo file uploads: 
 - A maximum size for user uploads, defaulting to 50MB. If exceeded then files are deleted in an oder first fashion to clear space until the operation can take place.
 - An expiration, defaulting to 1 hour

### POST /api/import

Allow to import an uploaded file either in the address book or the calendar.

For calendars:

```
POST /api/import
{
  "fileId": "xyz",
  "target": "/calendars/abc/def.json"
}
```

For address books:

```

POST /api/import
{
  "fileId": "zyz",
  "target": "/addressbooks/abc/def.json"
}
```

### POST /linagora.esn.dav.import/api/import

Exactly the same than `POST /api/import`