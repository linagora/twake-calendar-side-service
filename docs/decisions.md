# Decisions

Decisions taken on twake-calendar-side-service, oldest first. Append only: a decision that changes gets a new entry that replaces the old one and cites it. Entry format: the `twake-decisions` skill of [linagora/twake-guidelines](https://github.com/linagora/twake-guidelines/blob/main/skills/twake-decisions/SKILL.md).

Categories: UX, UI, Product, Architecture, Infra, Tech.

## 2026-11-22, [Architecture] Address book list notifications ignore resource-owned books

- Decision: the address book list notification consumer ignores, with a debug log, notifications whose address book path belongs to a resource, as it does for domain books; an owner that is neither a user, a domain nor a resource still fails and is dead-lettered, except for deletions.
- Decided by: agent (session for [issue #1617](https://github.com/linagora/twake-calendar-side-service/issues/1617)), to be confirmed by chibenwa
- Why: Sabre provisions address books under resource principals when a resource is first invited, and a resource has no personal address book list to push over WebSocket; failing filled the dead letter queue and kept the health check degraded.
- Scope: `calendar-amqp`, `AddressBookListNotificationHandler`.
