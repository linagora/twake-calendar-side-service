# Error types

The `error.type` field is always present in error responses. It exposes a
machine-readable category for the error, allowing clients to react on a stable
token rather than parsing the human-readable `message` or `details` fields.

| Type                  | Status | Cause                                          |
|-----------------------|--------|------------------------------------------------|
| `BadRequest`          | 400    | Malformed or invalid request                   |
| `Unauthorized`        | 401    | Missing or invalid authentication              |
| `Forbidden`           | 403    | Authenticated but not allowed                  |
| `NotFound`            | 404    | Requested resource does not exist              |
| `UnprocessableEntity` | 422    | Request understood but cannot be processed     |
| `ServiceUnavailable`  | 503    | Service temporarily unavailable                |
| `ServerError`         | 500    | Unexpected server-side error                   |
| `InactiveBookingLink`    | 400    | Booking link is inactive                    |
| `UnavailableBookingSlot` | 422    | Requested booking slot is unavailable       |
