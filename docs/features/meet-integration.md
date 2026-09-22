# Meet integration

Creates real [LaSuite Meet](https://github.com/suitenumerique/meet) rooms for calendar events, so the
link carried by `X-OPENPAAS-VIDEOCONFERENCE` names a room Meet actually knows about — owned by the
organizer, with the host controls that come with ownership.

Enable the feature with `meet.enabled=true` in `configuration.properties`.

See [the configuration page](../configuration/configuration.md#meet-integration-meetenabledtrue) for the
properties this feature reads, and [the Meet API page](../apis/meet.md) for the endpoint it exposes and
the Meet calls it makes.

## What Meet's external API actually offers

Read off the [Meet External API spec][spec] and the source behind it, not assumed. It constrains the
design more than one would expect, so it is written down here.

- [`POST /external-api/v1.0/application/token/`][spec] → `200` with `{access_token, token_type, expires_in}`.
  Exchanges the application credentials for a JWT scoped to one user, named by `scope=<email>`; Meet
  bakes that user into the token as `user_id` and trusts our assertion that we act for them. Errors:
  `400` (scope is not an email), `401` (bad credentials, inactive application), `403` (application not
  authorized for that email domain), `404` (user unknown and provisional creation disabled), `409`
  (provisional user integrity error). See [`ApplicationViewSet`][viewsets].
- [`POST /external-api/v1.0/rooms/`][spec] → `201` with `{id, name, slug, access_level, configuration, url}`.
  Creates a room and grants the scoped user `OWNER` ([`perform_create`][viewsets]).
- [`GET /external-api/v1.0/rooms/{uuid}/`][spec] → `200`, or `404` when no room carries that id, or
  `403` when the scoped user has no access to it.
- [`GET /external-api/v1.0/rooms/`][spec] → `200`, DRF-paginated, filtered to the scoped user's rooms.
- [`PATCH /external-api/v1.0/rooms/{uuid}/`][spec] → `200`. Access level and configuration only.

Two consequences shape everything else:

**The caller cannot choose the room code.** [`RoomSerializer`][serializers] marks `id`, `name` and
`slug` read-only and its `create()` does `validated_data["name"] = utils.generate_room_slug()`. The
room code is Meet's to mint. A code invented anywhere else names no room Meet knows about. `PUT` is
not exposed at all — [`http_method_names`][viewsets] omits it, and
[`test_api_rooms_update_put_not_allowed`][tests] asserts `405` — so there is no way to ask for a room
*at* a given slug either.

**A room can only be looked up by its UUID.** The external viewset has no slug-aware `get_object` —
the internal API has one, this one does not. Resolving a slug therefore means walking the paginated
rooms listing until it matches, which is linear in the number of rooms the user can see. We do not do
that.

## Creating the room

`POST /api/videoconference` asks Meet for a room owned by the authenticated user and answers `201`
with `{"url": "..."}`. The client writes that URL into `X-OPENPAAS-VIDEOCONFERENCE`. It answers `502`
when Meet refuses, and on a deployment without Meet the route is not bound at all, so the caller gets
a plain `404` and falls back to generating a code of its own.

That fallback only opens anything if Meet itself runs with `ALLOW_UNREGISTERED_ROOMS=True` (its
default): with it off, an unknown code answers `404 {"detail": "No Room matches the given query."}`,
so an external guest is told the room does not exist instead of landing in a waiting room — and with
it on the room has no owner, so there is no host to admit them from one either
([`retrieve`][internal-viewsets]).

### Booking links

A booking link creates the event with nobody around to call that route: the booker is an anonymous
visitor and the organizer is not in the request at all. So the room is created where the ICS is built,
before the property quotes it.

Both paths go through `MeetingConferenceLinkGenerator`, bound to the implementation that creates a
Meet room where Meet is configured and to the one that invents a code where it is not. The room is
created in the organizer's name — the booking link's owner, not the booker — so the organizer ends up
its `OWNER`.

### Slug collisions

Meet draws the code itself out of a 26^10 space and [`Room.slug`][models] is unique, so a collision is
its own dice coming up twice; the caller has no say in it and nothing to fix. It surfaces as a
**`400` naming the slug field**, not the `409` one would expect: [`BaseModel.save()`][models] calls
`full_clean()`, whose `validate_unique()` raises a Django `ValidationError`, and Meet's
[exception handler][exception-handler] maps that onto a DRF one. The client retries such a response a
bounded number of times — the next POST draws a different code — and lets any other `400` through
untouched.

[spec]: https://github.com/suitenumerique/meet/blob/main/docs/openapi.yaml
[viewsets]: https://github.com/suitenumerique/meet/blob/main/src/backend/core/external_api/viewsets.py
[serializers]: https://github.com/suitenumerique/meet/blob/main/src/backend/core/external_api/serializers.py
[models]: https://github.com/suitenumerique/meet/blob/main/src/backend/core/models.py
[exception-handler]: https://github.com/suitenumerique/meet/blob/main/src/backend/core/api/__init__.py
[internal-viewsets]: https://github.com/suitenumerique/meet/blob/main/src/backend/core/api/viewsets.py
[tests]: https://github.com/suitenumerique/meet/blob/main/src/backend/core/tests/test_external_api_rooms.py
