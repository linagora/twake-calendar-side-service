# Decisions

Decisions taken on twake-calendar-side-service, oldest first. Append only: a decision that changes gets a new entry that replaces the old one and cites it. Entry format: the `twake-decisions` skill of [linagora/twake-guidelines](https://github.com/linagora/twake-guidelines/blob/main/skills/twake-decisions/SKILL.md).

Categories: UX, UI, Product, Architecture, Infra, Tech.

## 2026-11-21, [Tech] Calendar search uses the James OpenSearch client

- Decision: calendar search uses the configured James `ReactorOpenSearchClient` for all requests, including single-operation bulk updates with sequence guards.
- Decided by: agent ([PR #1611](https://github.com/linagora/twake-calendar-side-service/pull/1611)), to be confirmed by vttranlina.
- Why: James now owns the HttpClient 5 transport and no longer exposes its low-level REST client; reusing it preserves connection settings and lifecycle management.
- Scope: `storage-opensearch`, including indexing and deletion.
