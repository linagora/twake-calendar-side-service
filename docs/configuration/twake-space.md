# TwakeSpace

TwakeSpace gives each space of the Twake Workplace directory a team calendar, following the space events. It runs when `configuration.properties` enables it:

```
twp.settings.enabled=true
twakespace.enabled=true
```

It needs `twp.settings.enabled`, as it reads the space events on the [Twake Workplace broker](configuration.md#twake-workplace-rabbitmq-properties). The startup fails otherwise.

TwakeSpace keeps the organization, domain and deletion time of each space. With the MongoDB backend they go to the `twake_spaces` collection. With the memory backend they are lost on restart, which only suits tests.

## Team calendars

- A space gets its team calendar on `twake.space.created`, named after the space id, with the space name as display name. It belongs to the event's `organizationDomain`. The event has none when the organization has no domain, and the calendar then belongs to the email domain of the space's first admin. That domain has to be a domain of the side service.
- Renaming the space renames the calendar.
- Members get the calendar shared following their role: viewers read, editors and admins read and write. Any other role reads, and a warning is logged. No one gets the administration right, so the calendar is never shared outside the space. Members unknown to the side service are registered first.
- A member outside the calendar's domain is not shared, and a warning is logged.
- On `twake.space.deleted`, every member loses access and the deletion time is recorded. The calendar and its events are kept. Later events of the space are ignored.
- Events of a space without team calendar, such as a space created before TwakeSpace was enabled, go to the dead letter queue.

## Activity

Each time something happens in a team calendar, TwakeSpace publishes one activity: a CloudEvent that the TwakeSpace feed shows as a card. Activities go to the activity exchange, a durable topic exchange of the Twake Workplace broker, with the CloudEvent type as routing key.

The CloudEvent types are:

- `com.twake.calendar.space.provisioned.v1`: the space got its team calendar. A redelivered `twake.space.created` publishes it again.
- `com.twake.calendar.event.created.v1`: someone created an event in the team calendar.
- `com.twake.calendar.event.rescheduled.v1`: an event moved. The card keeps the previous start and end.
- `com.twake.calendar.event.updated.v1`: any other change to an event, or an answer other than accept or decline.
- `com.twake.calendar.event.accepted.v1` and `com.twake.calendar.event.declined.v1`: an attendee accepted or declined.
- `com.twake.calendar.event.proposed.v1`: an attendee proposed another time.

Some changes publish nothing:

- events of personal calendars;
- imported events;
- changes and answers that concern a single occurrence of a recurring event, as a card shows the whole event.

TwakeSpace reads the changes of team calendars from the `calendar:event:created`, `calendar:event:updated` and `calendar:event:notificationEmail:send` exchanges of the side service's broker. It does not store them.

## Queues

Each queue has a single active consumer, so its events are handled in order across replicas.

- `tcalendar:twake-space` on the Twake Workplace broker receives the space events. It is a quorum queue unless `twp.queues.quorum.bypass` is set.
- `tcalendar:twake-space-calendar` on the side service's broker receives the changes of calendars. It follows `dav.queues.quorum.bypass`, like the other calendar queues.

An event that fails is not retried: it goes straight to the queue's dead letter queue, `tcalendar:twake-space-dead-letter` or `tcalendar:twake-space-calendar-dead-letter`. The RabbitMQ health check reports both queues and their dead letter queues. A dead letter queue is for inspection: replaying an event may apply it over the newer ones handled since.

## Properties

These go in `rabbitmq.properties`, next to the other Twake Workplace broker properties, and are optional.

- `twakespace.exchange`: the exchange carrying space events. Defaults to `space`.
- `twakespace.activity.exchange`: the exchange the activity is published on. Defaults to `activity`.
