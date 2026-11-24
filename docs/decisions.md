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

## 2026-11-23, [Tech] The DAV proxy authenticates first and streams the request body; the booking route body is capped at 64 KiB

- Decision: `DavProxy` authenticates the caller before reading any byte of the body, then streams the body to Sabre chunk by chunk (forwarding `Content-Length` when the client gave one, with HTTP client retries off since a streamed body cannot be sent twice) instead of aggregating it. The anonymous `POST /api/booking-links/{id}/book` reads at most 64 KiB and answers 413 `PayloadTooLarge` beyond, whether announced by `Content-Length` or counted on a chunked body. The authenticated routes that aggregate their body are left unchanged.
- Decided by: chibenwa, [issue #1630](https://github.com/linagora/twake-calendar-side-service/issues/1630); streaming instead of a configured size cap, and the 64 KiB value, chosen by agent (PR for #1630), to be confirmed by chibenwa
- Why: an anonymous client could make the JVM hold multi-gigabyte or endless uploads before getting its 401, bringing the whole service down. Streaming keeps legitimate DAV uploads (imports, large calendars) unbounded without memory cost; a valid reservation (20 attendees, 255 characters title, 2000 characters notes) fits far below 64 KiB.
- Scope: `calendar-rest-api`, `DavProxy`, `BookingLinkReservationRoute`, `BoundedRequestBody`.

## 2026-11-23, [Tech] Inline ATTACH values are decoded on the heap

- Decision: the side service builds `ATTACH` properties through `InMemoryAttach`, registered ahead of the ical4j factories in `CalendarUtil` and used by `CalendarUtil.copyOf`: a value with an `ENCODING` parameter is decoded into a heap buffer, never into a file.
- Decided by: agent (session e2aca2a3-2d1e-4ac1-8ff0-43617824effd, PR ical4j-hardening), to be confirmed by Benoit Tellier (maintainer), on the twake-qa deep dependency review of ical4j 4.4.0 (SEV-3).
- Why: ical4j 4.4.0 decodes it into an `ical4j-attach*.tmp` file it only deletes on JVM exit, so each parse or copy of an ICS sent by a user or a remote iTIP sender left a file in `java.io.tmpdir`, and a path in the JVM wide delete-on-exit set. To drop once ical4j stops doing so.
- Scope: `CalendarUtil.parseIcs` and `CalendarUtil.copyOf`, hence every ICS the side service reads.

## 2026-11-23, [Tech] Recurrence expansions are bounded by the candidates ical4j builds

- Decision: before ical4j expands an RRULE or EXRULE to find an occurrence (`EventParseUtils.createInstanceVEvent`, used by the calendar diff and iTIP notifications) or the next alarm (`AlarmInstantFactory`), `RecurSkipGuard.canExpand` bounds the walked periods times the product of the BY* list sizes (repeated values included) to 1,000,000 (`tcalendar.recur.max.expanded.candidates`). A rule with a COUNT is counted from DTSTART (at most COUNT periods), the lookup is widened by the span of the event (DURATION or DTEND minus DTSTART), and a rule over the bound gives no occurrence (an `IllegalArgumentException`) and no alarm.
- Decided by: agent (session e2aca2a3-2d1e-4ac1-8ff0-43617824effd, PR ical4j-hardening), to be confirmed by Benoit Tellier (maintainer), on the twake-qa deep dependency review of ical4j 4.4.0 (SEV-1, SEV-2).
- Why: ical4j builds the whole BY* product of a period in memory before filtering it, does not skip ahead for a COUNT rule, and walks every period of the span: a few hundred bytes of RRULE threw an `OutOfMemoryError` in the AMQP consumers serving every tenant. Refusing rather than truncating the rule, as `capCount` does for alarms, avoids a silently wrong diff.
- Scope: `storage-api` `RecurSkipGuard`, `EventParseUtils`, `AlarmInstantFactory`. The expiry check of `CalendarEventUtils` keeps its own bounds.

## 2026-11-23, [Tech] Text from outside an ICS is cleaned before ical4j writes it

- Decision: text written in a TEXT value that does not come from an ICS goes through `IcsText.sanitize` (line breaks to LF, other control characters but HTAB and unpaired surrogates dropped), and a single line value such as a UID through `IcsText.sanitizeSingleLine`; Twake Space meeting requests now do so for their title, description and UID, like booking links already did.
- Decided by: agent (session e2aca2a3-2d1e-4ac1-8ff0-43617824effd, PR ical4j-hardening), to be confirmed by Benoit Tellier (maintainer), on the twake-qa deep dependency review of ical4j 4.4.0 (SEV-4).
- Why: ical4j writes a lone CR verbatim and its parser reads it as a line break, so a meeting title could add attendees, an organizer or alarms to the event as read back.
- Scope: `calendar-api` `IcsText`, booking link events, Twake Space `MeetingRequest`.

## 2026-11-23, [Tech] Recurrence expansion bounds weigh BYDAY, BYMONTHDAY and sparse rules

- Decision: replaces the counting of "Recurrence expansions are bounded by the candidates ical4j builds" (same day): BYDAY counts 53 days per value in a YEARLY rule without BYMONTH nor BYWEEKNO, 5 in a MONTHLY or YEARLY one, BYMONTHDAY 12 per value in a YEARLY rule without BYMONTH, BYWEEKNO 7. COUNT bounds the walked periods only when every period holds an occurrence (no filtering BY* part, ordinal BYDAY, BYMONTHDAY, BYYEARDAY, BYWEEKNO nor BYSETPOS); and the next alarms of such a sparse rule are bounded over the whole year they are looked up in.
- Decided by: agent (session e2aca2a3-2d1e-4ac1-8ff0-43617824effd, PR ical4j-hardening review), to be confirmed by Benoit Tellier (maintainer).
- Why: counting BYDAY by its values let `FREQ=YEARLY;BYDAY=MO,...,SU;BYHOUR=0..23;BYMINUTE=0..59;BYSECOND=0..59` through at 604,800 candidates while ical4j builds 31.5 million dates in its first period.
- Scope: `storage-api` `RecurSkipGuard`, `AlarmInstantFactory`.

## 2026-11-23, [Tech] A Twake Space meeting UID with control characters is refused

- Decision: replaces the UID part of "Text from outside an ICS is cleaned before ical4j writes it" (same day): a meeting request whose UID holds a control character or an unpaired surrogate is refused (unprocessable event) instead of being cleaned; title and description are still cleaned.
- Decided by: agent (session e2aca2a3-2d1e-4ac1-8ff0-43617824effd, PR ical4j-hardening review), to be confirmed by Benoit Tellier (maintainer).
- Why: the UID identifies the meeting for Twake Space and for the lookups of the side service: cleaning it could merge two distinct meetings into one event.
- Scope: Twake Space `MeetingRequest`.

## 2026-11-23, [Tech] Contact search lists each address book by full name

- Decision: `CardDavClient.searchContacts` asks Sabre for `sort=fn` instead of `sort=uri`, so `POST /api/contacts/search` keeps the address book order of the request and lists the contacts of each book by the first letter of their full name (`fn` as Sabre stores it: ASCII folded, lower case, `#` for a non letter; ties in `_id` order), no longer by card URI.
- Decided by: agent (PR #1633, esn-sabre 2.5.0.7 bump), to be confirmed by Benoit Tellier (maintainer).
- Why: esn-sabre 2.5.0.7 answers 400 to a contact listing sorted on anything but `fn`, `email` or `_id`, which broke every contact search. Sabre adds `_id` as tie-breaker, so `offset` pages stay stable; `_id` alone is not even insertion order (the ObjectIds come from several PHP workers), and contacts often have no email. Sorting by the whole full name would need an esn-sabre change (`fn_sort` is only used by the cursor listing).
- Scope: `calendar-dav` `CardDavClient`, the contact search route.

## 2026-11-24, [Infra] The Docker Hub credential is bound only while pushing the image

- Decision: the `Jenkinsfile` binds no credential at pipeline level. `dockerHub` is bound with `withCredentials` inside the `Deliver Docker images` stage (main and tags only), the login reads the password on stdin, the Docker client configuration lives in the workspace (`DOCKER_CONFIG=$WORKSPACE/.docker`) and is logged out after the push. A credential is never put in a pipeline-level `environment` block.
- Decided by: Benoit Tellier, on the twake-qa security review of `e26eece1e897` (SEV-1).
- Why: a pipeline-level binding exported the push credential to every step, including the `mvn` builds, the tests and `ci/summarize-test-failures.py` of fork pull requests; the `when { branch 'main' }` of the push stage did not gate the binding, and log masking does not stop a process from reading its environment.
- Scope: `Jenkinsfile`. Rotating the credential, fork trust checks and isolating the shared `~/.m2` of PR builds are follow-ups.

## 2026-11-24, [Infra] A fork pull request runs no code before a linagora member vouches for its head commit

- Decision: replaces the fork part of "The Docker Hub credential is bound only while pushing the image" (2026-11-24): the Jenkinsfile starts with an `Authorize fork` stage: a pull request from a fork runs nothing of its own (build, tests, scripts, post actions) unless the fork owner is a linagora member, or a member approved its head commit, or submitted a review "Build this please" on it. No credential is bound at pipeline level; `github` and `dockerHub` are bound only around the steps that use them (`withGithubCredential`, `withDockerHubLogin` with a throwaway Docker config). The block holding these helpers is the same in the Jenkinsfiles of twake-contacts-frontend, twake-calendar-frontend, twake-calendar-side-service, webadmin-proxy and tmail-flutter, and is changed in all of them.
- Decided by: Benoit Tellier, after the twake-qa security review of twake-calendar-side-service (SEV-1, 2026-11-23); the pipeline of twake-contacts-frontend is the model.
- Why: the agents are persistent and shared, so code of a pull request that runs before the check can read a credential bound later in the same build, or leave a process behind for a later one. A review is tied to a commit by GitHub, unlike a comment, so a commit pushed after the approval is not built until a member reviews it again.
- Scope: `Jenkinsfile`, including the failed test summary run in `post`.
