# Attachment upload API

`POST /api/attachments` uploads a file to the authenticated user's TDrive and
returns a public, read-only link. Anyone with the link can open the file.
The endpoint is registered only when `twp.drive.enabled=true`. See the
[TDrive configuration guide](../../saas/twake-drive/README.md).

## Request

| Header | Value |
| --- | --- |
| `Authorization` | User authentication accepted by the calendar side service, such as `Bearer <calendar-jwt>`. |
| `Content-Type` | The file's media type, such as `application/pdf`. |
| `X-Filename` | A single filename encoded as UTF-8 with percent encoding. For example, `résumé.pdf` becomes `r%C3%A9sum%C3%A9.pdf`. |

Send the raw file bytes as the request body. The route uses
`upload.user.total.limit` as the maximum size per request, including chunked
requests. The default is 50 MiB.

Set `CALENDAR_API_URL` to the side-service base URL and `CALENDAR_JWT` to a JWT
for the calendar user. Run this example with `résumé.pdf` in the current directory.

```sh
curl --request POST "${CALENDAR_API_URL%/}/api/attachments" \
  --header "Authorization: Bearer ${CALENDAR_JWT}" \
  --header 'Content-Type: application/pdf' \
  --header 'X-Filename: r%C3%A9sum%C3%A9.pdf' \
  --data-binary '@./résumé.pdf'
```

## Response

A successful upload returns `201 Created` with a JSON body.

```json
{"uri":"https://alice-drive.example.com/public?sharecode=example#/"}
```

| Status | Meaning |
| --- | --- |
| `400 Bad Request` | Missing or invalid metadata, or the resolver could not select a valid Drive instance for the user. |
| `401 Unauthorized` | Missing or invalid authentication. |
| `404 Not Found` | TDrive attachment storage is disabled. |
| `413 Content Too Large` | The request body exceeds the upload limit. |
| `502 Bad Gateway` | An upstream request failed or returned an invalid response. |
| `504 Gateway Timeout` | An upstream request timed out. |
