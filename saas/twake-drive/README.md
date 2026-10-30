# SaaS Drive

`calendar-twake-drive` provides `AttachmentStorageClient.upload(Attachment, Username)`
returning `Mono<AttachmentUri>` and the REST endpoint below.

## REST endpoint

Authenticated clients can call `POST /api/attachments`:

```http
POST /api/attachments
Authorization: Bearer <user-token>
Content-Type: text/calendar
X-Filename: calendar.ics

<raw file bytes>
```

`X-Filename` is the UTF-8 filename percent-encoded (for example,
`résumé +%.pdf` becomes `r%C3%A9sum%C3%A9%20%2B%25.pdf`). `Content-Type`
is the file MIME type. The body is binary, with no JSON envelope or Base64.
The upload limit applies to raw bytes, including chunked requests.

The response is `201 Created`:

```json
{"uri":"https://alice-drive.example.com/public?sharecode=...#/"}
```

The authenticated user is used for the instance mapping. The endpoint returns `400` for
an invalid request and `503` when `twp.drive.enabled=false`.

## Configuration

Set these in `configuration.properties`:

```properties
twp.drive.enabled=true
twp.drive.instance.domain=example.com
twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
twp.drive.partner.token=<partner-bearer-token>
twp.drive.public.url.template=https://{driveFqdn}/public?sharecode={sharecode}#/

# Optional defaults
twp.drive.drive.url.template=https://{fqdn}
twp.drive.folder.name=Calendar attachments
twp.drive.timeout=10s
twp.drive.trust.all.ssl.certs=false
```

Disabled by default; the client is created only when enabled. Only one configured
secret is needed: `partner.token`, authorized to obtain temporary service tokens.
The broker and Drive Files/Permissions APIs must be reachable.
