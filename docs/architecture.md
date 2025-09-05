# Architecture

![Architecture diagram](assets/twake-calendar-side-service-architecture.drawio.png)

In addition to this target architecture, in order to allow easy deployment of our solution, the
`Twake Calendar Side Service` will also proxy HTTP calls it do not handle (yet) to the soon-to-be-replaced
`OpenPaaS` server.

Regarding observability, we intend Twake Calendar Side Service to be on par with Twake Mail: structured JSON logs
collected via [Loki](https://grafana.com/oss/loki/), metrics gathered by [Prometheus](https://prometheus.io/).

Implemented [Webadmin APIs](apis/webadmin.md): metrics, heathcheck, users and domain routes.

## Flux matrix

The side service relies on the following other services:

**esn-sabre DAV server** holds the calendar / contacts data and is in charge of manipulating them. The side-service calls the
DAV server in order to:
- Manage the secret link access: it validates the secret link then proxy the DAV calendar export
- Support excal: where a non authenticated third party user receives an invite from one of our users excal SPA offers
  a link for this user to update his participation. The token enbedded in the SPA is validated by the side service which
  acts accordingly on the DAV server.
- Synchronizing domain members: the side service ingest data from a LDAP and use it to poputate the domain member address book
  in sabre through the use of a "Technical Token".
- Calendar and address book import: after uploading data to the side service the side service pushes each relevant items
  into the corresponding DAV collection.
- [DAV Proxy](apis/davProxy.md): the side service expose an endpoint authenticated with OIDC that wraps the DAV api.
- Alarm service and event indexing service might read DAV data in order to maintain their own projections.

**Twake Mail** (or any SMTP submission service) is used to send calendar related emails.

## Persistance

[**MongoDB**](https://www.mongodb.com/) is used as a primary data store. Entities are stored in order to be retro-compatible
with the OpenPaaS dataformat making a transition from OpenPaaS to the side service trivial. Indexes matching the application
access pattern are created upon application start.

[**OpenSearch**](https://opensearch.org/) is used for:
- user and contact search as part of the `people search api`. This is implemented by leveraging the
  [Twake Mail auto-complete](https://github.com/linagora/tmail-backend/blob/master/docs/modules/ROOT/pages/tmail-backend/features/contactAutocomplete.adoc).
  Twake calendar thus blindly reads the entries added by Twake mail, which is currently responsible onf the indexing process.
- event search.The side service listen calendar events pushed by Sabre on RabbitMQ in order to maintain an index on OpenSearch
  whch is queries upon event search.

[**RabbitMQ**](https://www.rabbitmq.com/) is used in order to react to change performed atop the esn-sabre DAV server.

[**Redis**](https://redis.io/) is used as a cache to store OpenID connect access token hash thus lowring the load on the identity server.
Invalidation of this cache is possible by relying on back-channel logout.

**LDAP** data storage is possible and allows for:
- Synchronizing domain members into ESN-Sabre DAV server
- Back basic authentication if exposed