# Attachment upload API

`POST /api/attachments` uploads a file to the authenticated user's TDrive and
returns a public, read-only link. Anyone with the link can open the file.
The endpoint is registered only when `twp.drive.enabled=true`. See the
[TDrive configuration guide](../../saas/twake-drive/README.md).

## Request

Send `multipart/form-data` as defined by RFC 7578, with exactly one file part
named `file`.

| Header | Value |
| --- | --- |
| `Authorization` | User authentication accepted by the calendar side service, such as `Bearer <calendar-jwt>`. |
| `Content-Type` | `multipart/form-data; boundary=...` |

The file part supplies:

| Part header | Value |
| --- | --- |
| `Content-Disposition` | `form-data; name="file"; filename="report.pdf"` |
| `Content-Type` | The file's media type, such as `application/pdf`. Defaults to `application/octet-stream` when omitted. |

Send the filename as UTF-8 and the file contents as binary bytes. Additional
parts are rejected. The file size limit is `upload.user.total.limit` (default:
50 MiB), including chunked requests.

Set `CALENDAR_API_URL` to the side-service base URL and `CALENDAR_JWT` to a JWT
for the calendar user:

```sh
curl --request POST "${CALENDAR_API_URL%/}/api/attachments" \
  --header "Authorization: Bearer ${CALENDAR_JWT}" \
  --form 'file=@./résumé.pdf;type=application/pdf'
```

## Response

A successful upload returns `201 Created` with a JSON body.

```json
{"uri":"https://alice-drive.example.com/public?sharecode=example#/"}
```

| Status | Meaning |
| --- | --- |
| `400 Bad Request` | Invalid multipart data or filename, or the resolver could not select a valid Drive instance for the user. |
| `401 Unauthorized` | Missing or invalid authentication. |
| `404 Not Found` | TDrive attachment storage is disabled. |
| `413 Content Too Large` | The file exceeds the upload size limit. |
| `502 Bad Gateway` | An upstream request failed or returned an invalid response. |
| `504 Gateway Timeout` | An upstream request timed out. |
