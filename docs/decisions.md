# Decisions

Decisions taken on twake-calendar-side-service, oldest first. Append only: a decision that changes gets a new entry that replaces the old one and cites it. Entry format: the `twake-decisions` skill of [linagora/twake-guidelines](https://github.com/linagora/twake-guidelines/blob/main/skills/twake-decisions/SKILL.md).

Categories: UX, UI, Product, Architecture, Infra, Tech.

## 2026-11-22, [Tech] iTIP local delivery parses the message once per recipient

- Decision: `ItipLocalDeliveryConsumer` parses `localDelivery.message()` once and hands the `Calendar` to `ItipEmailNotificationPublisher`, which also checks expiry on the in-memory calendar of each payload instead of parsing it again; neither side may modify that shared `Calendar` (derived payloads are built through copies such as `CalendarUtil.withVEvents` and `CalendarEventUtils.withoutUnreadableDates`).
- Decided by: chibenwa, [issue #1626](https://github.com/linagora/twake-calendar-side-service/issues/1626); read-only sharing chosen by agent (PR for #1626), to be confirmed by chibenwa
- Why: `CalendarUtil.parseIcs` was 29.7% of the side service allocations, two thirds of it re-parsing a text just parsed.
- Scope: calendar-amqp, iTIP local delivery and its email notifications.

## 2026-11-22, [Tech] Search index writes never force an OpenSearch refresh

- Decision: indexing and deletions (`_delete_by_query`) of the calendar search index do not ask OpenSearch for a refresh; the periodic refresh makes them visible, and tests that read their own write wait for it.
- Decided by: Benoit Tellier, [issue #1627](https://github.com/linagora/twake-calendar-side-service/issues/1627)
- Why: a forced refresh made each deletion cost 25 ms (six times an indexing) and published a new segment per deletion, while the search index is written asynchronously by the AMQP consumers and gives no read-your-write guarantee anyway.
- Scope: `storage-opensearch`, `OpensearchCalendarSearchService`.

## 2026-11-22, [Architecture] Pending: verify the signature of back-channel logout tokens

- Options: (a) add `oidc.jwks.url` (and optionally `oidc.issuer`) and validate the logout token as OpenID Connect Back-Channel Logout 1.0 section 2.6 requires (signature with the provider's JWKS, `alg: none` refused, `iss`, `aud`, `iat`, `events`, no `nonce`), refusing every token while the JWKS is not configured; (b) the same, but keep today's unverified behaviour with a startup warning when `oidc.jwks.url` is not configured; (c) keep `POST /add-revoked-token` as it is.
- Today: the route only base64-decodes the token payload to read its `sid`. It is a WebAdmin route (not public in a usual deployment) and an attacker must know the victim's `sid`, which limits the exploitation. The same code is in james-project (`OidcBackchannelLogoutRoutes`, used by tmail-backend) and in webadmin-proxy (`BackchannelLogoutRoutes`).
- To decide: Benoit Tellier (maintainer), who keeps the route as it is for now (session 7717c82f-49cb-473b-9970-18a578db177b, 2026-11-22).
- Scope: WebAdmin `POST /add-revoked-token`, and the matching routes of james-project, tmail-backend and webadmin-proxy.

## 2026-11-22, [Architecture] Concurrent imports of the same vCards are not serialised by the side service

- Decision: the side service does not queue or serialise concurrent `POST /api/import` of the same cards; the duplicate rows they create are an esn-sabre storage issue (no uniqueness on address book and card URI).
- Decided by: Benoit Tellier (maintainer), session 7717c82f-49cb-473b-9970-18a578db177b, 2026-11-22, on QA report "vcard-reimport-duplicates-contacts".
- Why: a lock in the side service only covers one instance; the fix belongs to the DAV storage.
- Scope: vCard and ICS imports (`ImportProcessor`), and other flows that PUT the same card URI concurrently.

## 2026-11-22, [Product] Bounds of booking link fields

- Decision: a booking link's `durationMinutes` is at most 86400 (60 days, the longest range the slots route accepts), `name` and `location` at most 255 characters, `description` at most 2000 characters, and a JSON number is refused for a boolean or a string field; on creation, PATCH and the WebAdmin routes alike.
- Decided by: agent (session 7717c82f-49cb-473b-9970-18a578db177b, PR qa-rest-batch), to be confirmed by Benoit Tellier (maintainer).
- Why: a longer duration gives a public link that can never offer a slot; the text bounds are those of the reservation route (title 255, notes 2000).
- Scope: `POST` and `PATCH /api/booking-links`, WebAdmin booking link routes.

## 2026-11-22, [Tech] A conflicting event deletion in OpenSearch is retried after one refresh interval

- Decision: completes "Search index writes never force an OpenSearch refresh" (issue #1627): a delete-by-query that met a version conflict is retried after one index refresh interval (1 s, OpenSearch default), so the retry snapshots the documents rewritten concurrently; an explicit refresh is not possible since the James-managed reactive client (main, #1612) exposes none.
- Decided by: agent (session 7717c82f-49cb-473b-9970-18a578db177b, PR qa-rest-batch), to be confirmed by Benoit Tellier (maintainer).
- Why: without a forced refresh, an immediate retry would snapshot the same stale documents and hit the same conflicts.
- Scope: `OpensearchCalendarSearchService` delete of an event, stale occurrences of a series, and whole-calendar deletion.
