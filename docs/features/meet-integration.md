# Meet integration

Creates real [LaSuite Meet](https://github.com/suitenumerique/meet) rooms for calendar events, so the
link carried by `X-OPENPAAS-VIDEOCONFERENCE` names a room Meet actually knows about — owned by the
organizer, with the host controls that come with ownership.

Enable the feature with `meet.enabled=true` in `configuration.properties`.

See [the configuration page](../configuration/configuration.md#meet-integration-meetenabledtrue) for the
properties this feature reads.

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

[spec]: https://github.com/suitenumerique/meet/blob/main/docs/openapi.yaml
[viewsets]: https://github.com/suitenumerique/meet/blob/main/src/backend/core/external_api/viewsets.py
[serializers]: https://github.com/suitenumerique/meet/blob/main/src/backend/core/external_api/serializers.py
[tests]: https://github.com/suitenumerique/meet/blob/main/src/backend/core/tests/test_external_api_rooms.py
