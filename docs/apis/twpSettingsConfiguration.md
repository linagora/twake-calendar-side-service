# Twake Workplace (TWP) Settings Synchronization

This document describes how Calendar side-service synchronizes user settings
from Twake Workplace (TWP).

## Overview

Calendar can synchronize a subset of user settings from Twake Workplace via
RabbitMQ. When enabled, TWP becomes the source of truth for these settings.

Currently, the following setting is synchronized from TWP:

- `core.language`

## RabbitMQ Configuration

In `rabbitmq.properties`, Calendar side service uses the following properties to consume
TWP settings messages.

- `twp.rabbitmq.uri`
- `twp.rabbitmq.management.uri`
- `twp.queues.quorum.bypass`
- `twp.settings.exchange`
- `twp.settings.routingKey`

For a detailed description of these RabbitMQ properties, please refer to the
official [TMail documentation](https://github.com/linagora/tmail-backend/blob/master/docs/modules/ROOT/pages/tmail-backend/configure/rabbitmq.adoc#twake-workplace-settings-configuration)

## Enabling TWP Settings Synchronization

To enable synchronization of user settings from Twake Workplace, the following
property must be set in `configuration.properties`:

```properties
twp.settings.enabled=true
```