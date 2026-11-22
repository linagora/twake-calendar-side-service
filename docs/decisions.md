# Decisions

Decisions taken on twake-calendar-side-service, oldest first. Append only: a decision that changes gets a new entry that replaces the old one and cites it. Entry format: the `twake-decisions` skill of [linagora/twake-guidelines](https://github.com/linagora/twake-guidelines/blob/main/skills/twake-decisions/SKILL.md).

Categories: UX, UI, Product, Architecture, Infra, Tech.

## 2026-11-22, [Architecture] Malformed user and domain ids answer 400 with a message naming the id

- Decision: with the MongoDB storage, a user or domain id that is not a 24-character hexadecimal string is rejected with 400 and the message `Invalid id '<id>': expected a 24-character hexadecimal id` (routes `/api/users/{id}`, `/api/domains/{id}`, `/api/entity/{id}`); the check lives in the MongoDB DAOs, not in the routes, because the memory storage uses UUID ids.
- Decided by: agent (session [#1618](https://github.com/linagora/twake-calendar-side-service/issues/1618)), to be confirmed by chibenwa
- Why: the raw `ObjectId` error ("state should be: hexString has 24 characters") leaked to API clients and named no parameter of the route.
- Scope: REST API, MongoDB storage (user and domain DAOs).
