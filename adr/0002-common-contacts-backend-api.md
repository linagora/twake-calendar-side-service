# ADR-0002: Common Contacts backend autocomplete API

## Status

Accepted.

## Context

Common Contacts ([#989](https://github.com/linagora/twake-calendar-side-service/issues/989)) makes the side service
the contact hub of Twake Workplace: other applications (Chat, Drive, ...) push the contacts they collect, and receive
contact changes over RabbitMQ.

These applications also need to autocomplete contacts on behalf of their users. The side service already implements
this for the calendar SPA with the OpenPaaS `POST /api/people/search` route, but that route:

- is authenticated with end-user credentials (OIDC, JWT, basic auth). Backends calling on behalf of a user do not
  hold such credentials.
- lives on the public REST API port, alongside the whole OpenPaaS API surface.

## Decision

Expose a subset of the people search API on a **dedicated port**, served by a **dedicated reactor-netty server** living
in a **dedicated maven module**, `common-contacts-api`.

- **Configuration**: `common.contact.api.port` in `configuration.properties`. The API is disabled when unset, so
  existing deployments are not affected. `common.contact.api.secrets` is a coma separated list of accepted tokens;
  it is mandatory when the port is set and startup fails otherwise. Values are split on comas regardless of the list
  delimiter handling of the properties loader, and trimmed.
- **Authentication**: a dedicated layer, independent from the `Authenticator` of the REST API, checks the
  `Authorization: Bearer <secret>` header against the configured secrets using constant time comparison. Several
  secrets allow one secret per consumer as well as rotations without downtime. Authentication is checked before
  routing so that unauthenticated callers cannot probe the exposed routes.
- **Impersonation**: as the caller is a backend, the request body carries the `user` on whose behalf the search is
  done. Results are the ones this user would get from the SPA.
- **Scope**: only the `contact` object type is served, which is what an autocomplete needs. Other object types
  (`user`, resources, team calendars) are not exposed and requesting them is rejected with `400 Bad Request`. The response only carries `id`, `objectType`, `names`
  and `emailAddresses`: avatar URLs point to the end-user authenticated REST API and are meaningless for backends.
- **Reuse**: the search itself (providers, sorting, user search restrictions of the domain) is extracted from
  `PeopleSearchRoute` into `PeopleSearchService` in `calendar-rest-api` and shared by both APIs. Error responses
  reuse `ErrorResponse`.

## Consequences

- Network isolation of the backend API is possible as it runs on its own port: it can be kept private to the
  platform network while the REST API port is exposed publicly.
- Secret holders can search contacts of any user. Secrets must be handled as highly sensitive credentials, and the
  port shall not be exposed publicly.
- Any change to the people search logic applies to both APIs.

## Alternatives considered

- **Adding a Bearer strategy to the existing REST API authenticator**: exposes the whole OpenPaaS API to shared
  secrets and mixes end-user and backend concerns on the same port.
- **Webadmin route**: webadmin is an administration API with its own lifecycle and authentication; granting
  application backends webadmin credentials would give them far more power than an autocomplete requires.
- **Issuing technical user JWTs**: requires consumers to manage token signing and renewal, heavier than a static
  shared secret for a server to server integration.
