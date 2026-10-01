# Common Contacts backend autocomplete API

Lets other Twake Workplace backends (Chat, Drive, ...) autocomplete contacts on behalf of a user.

It exposes a subset of the [OpenPaaS people search API](openpaasApi.md) on a dedicated port, authenticated
with shared secrets instead of end-user credentials. The design is described in
[ADR-0002](../../adr/0002-common-contacts-backend-api.md).

## Configuration

In `configuration.properties`:

```properties
# Disabled if unset
common.contact.api.port=81
# Coma separated list of accepted Bearer tokens
common.contact.api.secrets=abcdef,ghijz
```

See [the configuration page](../configuration/configuration.md).

## Authentication

Every request must carry one of the configured secrets as a Bearer token:

```http
Authorization: Bearer abcdef
```

Requests with a missing or unknown token are rejected with `401 Unauthorized`, whatever the requested path.

## Search contacts

```http
POST /api/people/search
Authorization: Bearer abcdef
Content-Type: application/json

{
  "user": "naruto@domain.tld",
  "q": "sasuke",
  "objectTypes": [ "contact" ],
  "limit": 10
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `user` | Yes | Mail address of the user on whose behalf the search is done. Only contacts visible to this user are returned. |
| `q` | No | Searched text. Defaults to an empty string. |
| `objectTypes` | No | Only `contact` is supported. Any other value is rejected with `400 Bad Request`. Omitted or empty: `contact` is searched. |
| `limit` | Yes | Maximum number of results. Between `1` and `256`. |

The semantics are the ones of the OpenPaaS people search API used by the calendar SPA: user search restrictions
configured for the domain of `user` apply.

Returns `200 OK`:

```json
[
  {
    "id": "5f1e8d3f-0f6a-4e8c-9a3c-0a8f2b1c9d7e",
    "objectType": "contact",
    "names": [ { "displayName": "sasuke uchiha", "type": "default" } ],
    "emailAddresses": [ { "value": "sasuke@domain.tld", "type": "Work" } ]
  }
]
```

Results are sorted by display name.

Errors:

- `400 Bad Request` for a missing or malformed body, a missing `user`, a `user` that is not a mail address,
  an unsupported object type, or out of range `limit`. The body follows the [error format](errorTypes.md).
- `401 Unauthorized` for a missing or invalid Bearer token.
- `404 Not Found` for any other route.
