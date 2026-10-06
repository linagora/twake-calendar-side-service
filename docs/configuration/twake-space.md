# TwakeSpace extension

The TwakeSpace extension gives each space of the Twake Workplace directory a team calendar, following the space events. It runs only when `extensions.properties` lists it:

```
guice.extension.startable=com.linagora.calendar.twakespace.TwakeSpaceStartable
```

Its health check, `TwakeSpace`, restarts a consumer when its queue has none. It runs with the other health checks once `healthcheck.properties` lists it:

```
additional.healthchecks=com.linagora.calendar.twakespace.TwakeSpaceHealthCheck
```

It needs the MongoDB backend, where it keeps the domain and deletion time of each space in the `twake_spaces` collection, and the last known state of each team calendar event in `twake_space_events`.

## Team calendars

- A space gets its team calendar on `twake.space.created`, named after the space id, with the space name as display name. It belongs to the event's `organizationDomain`. The event has none when the organization has no domain, and the calendar then belongs to the email domain of the space's first admin. That domain has to be a domain of the side service.
- Renaming the space renames the calendar.
- Members get the calendar shared following their role: viewers read, editors and admins read and write. Any other role reads, and a warning is logged. No one gets the administration right, so the calendar is never shared outside the space. Members unknown to the side service are registered first.
- A member outside the calendar's domain is not shared, and a warning is logged.
- On `twake.space.deleted`, every member loses access and the deletion time is recorded. The calendar and its events are kept. Later events of the space are ignored.
- Events of a space without team calendar, such as a space created before the extension was enabled, go to the dead letter queue.
- An event that fails is retried 3 times, then goes to the dead letter queue. Alert on its size through the RabbitMQ metrics.
- The dead letter queue is for inspection. Replaying an event may apply it over the newer ones handled since.

## Activity

The extension publishes CloudEvents on the activity exchange, a durable topic exchange on the space events vhost, with the event type as routing key. The TwakeSpace feed shows them as cards.

- `com.twake.calendar.space.provisioned.v1` once a space has its team calendar, again when `twake.space.created` is redelivered.
- `com.twake.calendar.event.created.v1`, `updated.v1` and `rescheduled.v1` when an event of a team calendar changes. Imported events publish nothing.
- `com.twake.calendar.event.accepted.v1` and `declined.v1` when an attendee answers, `proposed.v1` when an attendee outside the space proposes another time.

Events of personal calendars publish nothing. The extension reads the changes from the side service's calendar queues, through its own queue bound to the `calendar:event:created`, `calendar:event:updated` and `calendar:event:notificationEmail:send` exchanges.

## Properties

All properties go in `extensions.properties` and are optional.

- `twakespace.exchange`: the exchange carrying space events. Defaults to `space`.
- `twakespace.routing.keys`: comma separated routing keys bound to the queue. Defaults to the space and member events the extension handles.
- `twakespace.queue`: the queue the extension consumes. Defaults to `tcalendar:twake-space`.
- `twakespace.dead.letter.queue`: the queue, and the exchange of the same name, receiving the events the extension fails to handle. Defaults to `tcalendar:twake-space-dead-letter`.
- `twakespace.activity.exchange`: the exchange the activity is published on. Defaults to `activity`.
- `twakespace.calendar.queue`: the queue of calendar changes, on the side service's RabbitMQ. Defaults to `tcalendar:twake-space-calendar`.
- `twakespace.calendar.dead.letter.queue`: its dead letter queue. Defaults to `tcalendar:twake-space-calendar-dead-letter`.
- `twakespace.rabbitmq.*`: the connection to the vhost carrying space events, with the [rabbitmq.properties](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/configure/rabbitmq.html) keys. Without `twakespace.rabbitmq.uri`, the extension uses the side service's `rabbitmq.properties`. With it, `management.uri`, `management.user` and `management.password` are compulsory. The vhost is the path of `uri`, `%2F` for the default vhost `/`. `quorum.queues.enable`, `quorum.queues.replication.factor` and `quorum.queues.delivery.limit` shape the queues.

A name set but blank fails the startup. The extension consumes with a single active consumer, so events are handled in order across replicas.

## Example

When space events live on another vhost than the side service queues:

```
guice.extension.startable=com.linagora.calendar.twakespace.TwakeSpaceStartable
twakespace.rabbitmq.uri=amqp://${env:TWAKESPACE_RABBITMQ_USER}:${env:TWAKESPACE_RABBITMQ_PASSWORD}@rabbitmq:5672/%2F
twakespace.rabbitmq.management.uri=http://rabbitmq:15672
twakespace.rabbitmq.management.user=${env:TWAKESPACE_RABBITMQ_USER}
twakespace.rabbitmq.management.password=${env:TWAKESPACE_RABBITMQ_PASSWORD}
twakespace.rabbitmq.quorum.queues.enable=true
twakespace.rabbitmq.quorum.queues.replication.factor=3
```

The RabbitMQ user needs these permissions on the vhost:

- configure: the exchange, the activity exchange, the queue and the dead letter queue
- write: the activity exchange, the queue and the dead letter queue
- read: the exchange, the queue and the dead letter queue
