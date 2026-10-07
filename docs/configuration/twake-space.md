# TwakeSpace

TwakeSpace gives each space of the Twake Workplace directory a team calendar, following the space events. It runs when `configuration.properties` enables it:

```
twp.settings.enabled=true
twakespace.enabled=true
```

It needs `twp.settings.enabled`, as it reads the space events on the [Twake Workplace broker](configuration.md#twake-workplace-rabbitmq-properties), and the MongoDB backend, where the `twake_spaces` collection keeps the organization, domain and deletion time of each space. The startup fails otherwise.

## Team calendars

- A space gets its team calendar on `twake.space.created`, named after the space id, with the space name as display name. It belongs to the event's `organizationDomain`. The event has none when the organization has no domain, and the calendar then belongs to the email domain of the space's first admin. That domain has to be a domain of the side service.
- Renaming the space renames the calendar.
- Members get the calendar shared following their role: viewers read, editors and admins read and write. Any other role reads, and a warning is logged. No one gets the administration right, so the calendar is never shared outside the space. Members unknown to the side service are registered first.
- A member outside the calendar's domain is not shared, and a warning is logged.
- On `twake.space.deleted`, every member loses access and the deletion time is recorded. The calendar and its events are kept. Later events of the space are ignored.
- Events of a space without team calendar, such as a space created before TwakeSpace was enabled, go to the dead letter queue.

## Activity

TwakeSpace publishes CloudEvents on the activity exchange, a durable topic exchange of the Twake Workplace broker, with the event type as routing key. The TwakeSpace feed shows them as cards.

- `com.twake.calendar.space.provisioned.v1` once a space has its team calendar, again when `twake.space.created` is redelivered.
- `com.twake.calendar.event.created.v1`, `updated.v1` and `rescheduled.v1` when an event of a team calendar changes. Imported events publish nothing.
- `com.twake.calendar.event.accepted.v1` and `declined.v1` when an attendee accepts or declines, `updated.v1` for any other answer, `proposed.v1` when an attendee outside the space proposes another time.

A card shows a whole event: a change or an answer that concerns a single occurrence of a recurring event publishes nothing.

Events of personal calendars publish nothing. TwakeSpace reads the changes of team calendars from the `calendar:event:created`, `calendar:event:updated` and `calendar:event:notificationEmail:send` exchanges of the side service's broker, and stores none of them.

## Queues

Each queue has a single active consumer, so its events are handled in order across replicas.

- `tcalendar:twake-space` on the Twake Workplace broker receives the space events. It is a quorum queue unless `twp.queues.quorum.bypass` is set.
- `tcalendar:twake-space-calendar` on the side service's broker receives the changes of calendars. It follows `dav.queues.quorum.bypass`, like the other calendar queues.

An event that fails is not retried: it goes straight to the queue's dead letter queue, `tcalendar:twake-space-dead-letter` or `tcalendar:twake-space-calendar-dead-letter`. The RabbitMQ health check reports both queues and their dead letter queues. A dead letter queue is for inspection: replaying an event may apply it over the newer ones handled since.

## Properties

These go in `rabbitmq.properties`, next to the other Twake Workplace broker properties, and are optional.

- `twakespace.exchange`: the exchange carrying space events. Defaults to `space`.
- `twakespace.activity.exchange`: the exchange the activity is published on. Defaults to `activity`.
