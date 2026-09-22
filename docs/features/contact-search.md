# Contact search

Search contacts across multiple address books accessible to the authenticated user.

## Request

```http
POST /contacts/api/contacts/search?limit=30&offset=0
Content-Type: application/json

{
  "query": "alice",
  "addressBooks": [
    { "userId": "owner-id", "addressBookId": "contacts" },
    { "userId": "owner-id", "addressBookId": "collected" }
  ]
}
```

Query parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `limit` | `30` | Maximum number of contacts returned. Must be greater than `0`. |
| `offset` | `0` | Number of contacts skipped from the combined result. Must be `0` or greater. |

Request body:

| Field | Required | Description |
|-------|----------|-------------|
| `query` | No | Case-insensitive regular expression matched against vCard content. Missing, null or empty values match all contacts. |
| `addressBooks` | No | Address books to search. Missing, null or empty values return an empty result. |
| `addressBooks[].userId` | Yes | ID of the user or domain owning the address book. |
| `addressBooks[].addressBookId` | Yes | Address book URI segment, such as `contacts`, `collected`, `domain-members` or a delegated mirror ID. |

Existing DAV permissions apply to personal, shared, delegated and domain address
books. Address books that the authenticated user cannot read, or that no longer exist,
are ignored. If none of the requested books are available, the response contains an
empty `dav:item` array.

## Response

Successful requests return `200 OK`:

```json
{
  "_embedded": {
    "dav:item": [
      {
        "_links": {
          "self": {
            "href": "/addressbooks/owner-id/contacts/contact-id.vcf"
          }
        },
        "etag": "etag-value",
        "data": [
          "vcard",
          [
            ["version", {}, "text", "4.0"],
            ["fn", {}, "text", "Alice Example"]
          ]
        ]
      }
    ]
  }
}
```

Each item preserves the DAV `_links`, `etag` and `data`. The `data` field uses jCard,
the JSON representation of vCard defined by [RFC 7095](https://www.rfc-editor.org/rfc/rfc7095).
Results follow the address book order from the request and are sorted by contact URI
within each book. `offset` and `limit` are applied to the combined result.

Invalid requests return `400`. DAV dependency failures return `502`.
