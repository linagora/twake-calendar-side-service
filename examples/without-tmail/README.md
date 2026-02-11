# Twake Calendar side service Standalone mode

This setup allows running Twake Calendar **without [Twake Mail](https://github.com/linagora/tmail-backend)** while still ingesting
calendar invitations and collected contacts from any external mail server.
This is achieved by running a minimal version of Twake mail embedding
the [OpenPaaS connector](https://github.com/linagora/tmail-backend/blob/master/docs/modules/ROOT/pages/tmail-backend/features/openpaas-integration.adoc)
alongside the calendar side service. We expect the user to run **any** third part email server. This third party email server is expected to mirror its email traffic (incoming, outgoing and internal).

James runs as a standalone PG server to process incoming emails with:
- `CalDavCollect` for iCalendar (ICS) ingestion
- `CardDavCollectedContact` (ContactCollect) for collected contacts

Emails are **not stored** and **not relayed**; they are parsed and then discarded.
Third-party infrastructure is responsible for mirroring incoming/outgoing mail traffic.

## Run order

1. Start calendar side service stack:

```bash
cd app/docker-sample
docker compose up -d
```

2. Start mailet collector

```bash
cd examples/without-tmail
docker compose up -d
```

## Network requirement

Both compose files must run on the same Docker network (`tcalendar`).
This allows the mailet collector to call HTTP endpoints of the side service and SabreDAV.

## Important configuration files

### `conf/mailetcontainer.xml`

This file defines the mail processing pipeline.
In this example, it is the core of the “collect + discard” behavior:

- Parse `text/calendar` (ICS) to JSON.
- `CalDavCollect` sends iTIP to SabreDAV.
- `CardDavCollectedContact` collects contacts to CardDAV.
- `Null` at the end discards the mail (no mailbox storage, no relay).

### `conf/smtpserver.xml`

This file defines how James accepts SMTP connections.
In this example, it allows ingress SMTP for collection only (no relay).

## Environment variables in `docker-compose.yml`

The `standalone-email` service passes these env vars into `openpaas.properties`.
They control where mailets call OpenPaaS API and DAV APIs:

- `OPENPAAS_API_URI`: OpenPaaS-compatible API endpoint (side service). Example: `http://tcalendar-side-service.linagora.local/api`
- `OPENPAAS_ADMIN_USER`: admin user for Basic Auth when calling OpenPaaS API.
- `OPENPAAS_ADMIN_PASSWORD`: password for the admin user.
- `OPENPAAS_TRUST_ALL_SSL_CERTS`: trust all SSL certs when calling OpenPaaS API.
- `DAV_API_URI`: DAV endpoint (SabreDAV). Example: `http://sabre-dav.linagora.local`
- `DAV_ADMIN_USER`: DAV admin user.
- `DAV_ADMIN_PASSWORD`: DAV admin password.
- `DAV_TRUST_ALL_SSL_CERTS`: trust all SSL certs when calling DAV.

Detailed reference:
[OpenPaas configuration doc](https://github.com/linagora/tmail-backend/blob/master/docs/modules/ROOT/pages/tmail-backend/configure/openpaas.adoc)
