# Twake Calendar Side service

![LOGO](assets/calendar.svg)

## Goals

This service aims at interacting with a [esn-sabre](https://github.com/linagora/esn-sabre/) backend and OpenPaaS single 
page applications being [calendar](https://github.com/linagora/esn-frontend-calendar) and 
[contacts](https://github.com/linagora/esn-frontend-contacts).

It vows to strictly follow OpenPaaS APIs as integration tested by [dav-integrationTesting](https://ci.linagora.com/btellier/dav-integrationtesting).

It vows to follow OpenPaaS DB structure (at least for now as we might see DB refactoring dropping MongoDB).

However, despite those above statements it shall be seen as a very aggressive refactoring as it technically is a full rewrite.

We aim for a pragmatic approach by reusing most of the tooling that made Twake mail successful.

## Roadmap

We aim for replacing [OpenPaaS](https://open-paas.org/) by the end of 2025.

Then we might add websocket push to this project.

Next development items are not specified yet. We could envision replacing [MongoDB backend](https://www.mongodb.com/) by
[PostgreSQL](https://www.postgresql.org/) document DB but this is not decided yet.

## Architecture

[This page](docs/features.md) details the side service features.

[This page](docs/architecture.md) details the side service architecture.

## Running it

We provide a full [demo](app/docker-sample/README.md) of the entire calendar stack, including this calendar side-service,
the esn-sabre dav server, and all single page applications with a sample OIDC setup.

Below sections refers how to run specifically the side service:

 - [Compile and run with CLI](docs/run/run-cli.md)
 - [Run with docker](docs/run/run-docker.md)

## Configuring it

Please refer to the [sample configuration](app/src/main/conf).

Please refer to [this page](docs/configuration/index.md) for a documentation of the configuration.

## Migrating from OpenPaaS

Simply replace your OpenPaaS server by the side service. Especially:

 - Turn OpenPaaS off.
 - Use the OpenPaaS mongoDB database for the side service
 - Connect your side service to the RabbitMQ server your sabre server uses
 - Connect your side service to the OpenSearch server your Twake Mail server uses
 - Connect it to your LDAP server
 - Point your existing OpenPaaaS SPA (calendar, cotact, account, excal) and other services (esn-sabre dav server, twake mail)
to the side service instead of OpenPaaS.

Once this is done, extra steps are needed in order to:
 - [Reindex events](/docs/apis/webadmin.md#calendar-event-reindexing) as the side service re-implemented event indexing from scratch.
 - [Re-schedule alarms](/docs/apis/webadmin.md#alarm-rescheduling) as the side service do not reuse legacy OpenPaaS data structure and re-implement
scheduling using a polling approach atop a new dedicated collection.

## Contributing

At LINAGORA we warmly welcome feedbacks and contributions.

We expect quality contributions matching the scope of the project.

We recommend discussing the contribution (GitHub issues or discussion) prior writing some code.

LINAGORA owns the code base so alone is entitled to decide what shall be accepted or not as a contribution
and became owner of the contribution. However, we will retain paternity of the contribution (git history
and if applicable / requested in comments)

## Credits

Developed with <3 at [LINAGORA](https://linagora.com) !