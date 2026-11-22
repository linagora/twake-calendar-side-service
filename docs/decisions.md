# Decisions

Decisions taken on twake-calendar-side-service, oldest first. Append only: a decision that changes gets a new entry that replaces the old one and cites it. Entry format: the `twake-decisions` skill of [linagora/twake-guidelines](https://github.com/linagora/twake-guidelines/blob/main/skills/twake-decisions/SKILL.md).

Categories: UX, UI, Product, Architecture, Infra, Tech.

## 2026-11-22, [Product] Booking link write APIs bound the duration and texts and refuse wrongly typed JSON

- Decision: booking link creation and update (REST `POST`/`PATCH /api/booking-links` and WebAdmin) refuse with 400 a `durationMinutes` above 86400 (60 days, the widest range the slots route accepts), a `name` above 255 characters, a `location` above 1024 characters, a `description` above 4096 characters (lengths counted after trimming), and JSON values of the wrong type (numbers or strings for booleans, numbers or booleans for strings, strings, floats or booleans for integers).
- Decided by: agent (PR for [#1613](https://github.com/linagora/twake-calendar-side-service/issues/1613)), to be confirmed by chibenwa
- Why: a link whose duration fits in no queryable range is published but never bookable, and unbounded texts or silently coerced values let clients store data they did not mean to.
- Scope: `calendar-rest-api` booking link create and patch routes, `calendar-webadmin` booking link user routes.
