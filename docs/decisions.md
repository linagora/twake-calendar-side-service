# Decisions

Decisions taken on twake-calendar-side-service, oldest first. Append only: a decision that changes gets a new entry that replaces the old one and cites it. Entry format: the `twake-decisions` skill of [linagora/twake-guidelines](https://github.com/linagora/twake-guidelines/blob/main/skills/twake-decisions/SKILL.md).

Categories: UX, UI, Product, Architecture, Infra, Tech.

## 2026-11-22, [Architecture] People search: required fields and malformed bodies

- Decision: `POST /api/people/search` requires `objectTypes` (an array) and `limit`, defaults a missing or `null` `q` to an empty string, and answers a missing, `null` or malformed body, a wrongly typed field or a missing required field with a 400 whose `error.details` names the problem, as `/calendar/api/events/search` does.
- Decided by: agent ([issue #1616](https://github.com/linagora/twake-calendar-side-service/issues/1616)), to be confirmed by chibenwa
- Why: no default for `objectTypes` or `limit` would be obvious to a caller (searching every type, an arbitrary page size), while an empty query is already the default of the other search routes.
- Scope: the OpenPaaS-compatible REST API (`calendar-rest-api`), people search route.
