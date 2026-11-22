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
