# TwakeSpace

TwakeSpace gives each space of the Twake Workplace directory a team calendar, following the space events. It runs when `configuration.properties` enables it:

```
twp.settings.enabled=true
twakespace.enabled=true
```

It needs `twp.settings.enabled`, as it reads the space events on the [Twake Workplace broker](configuration.md#twake-workplace-rabbitmq-properties). The startup fails otherwise.

TwakeSpace keeps what the space events say about each space: its organization, domain, name, members with their role, and deletion. With the MongoDB backend it goes to the `twake_spaces` collection.

## Team calendars

Space events may be handled in any order, and concurrently. Each event is merged into the stored space: the name and the role of each member keep the value of the newest event, by its `timestamp`. A removed member stays removed until a newer event adds them back. The team calendar is then brought in line with the stored space, not with the event, so the result does not depend on the order.

- A space gets its team calendar, named after the space id, from the first event that names its domain. The domain is the event's `organizationDomain`. The event has none when the organization has no domain, and the domain is then the email domain of an admin in the event. It has to be a domain of the side service.
- The display name is the space name. Until an event gives it, it is the space id.
- Renaming the space renames the calendar.
- Members get the calendar shared following their role: viewers read, editors and admins read and write. Any other role reads, and a warning is logged. No one gets the administration right, so the calendar is never shared outside the space. Members unknown to the side service are registered first.
- A member outside the calendar's domain is not shared, and a warning is logged.
- Shares of users that no space event named are left alone.
- On `twake.space.deleted`, every member loses access and the deletion is recorded. The calendar and its events are kept. A deleted space stays deleted: its later events, and older ones handled late, share nothing and create no calendar.

## Activity

Each time something happens in a team calendar, TwakeSpace publishes one activity: a CloudEvent that the TwakeSpace feed shows as a card. Activities go to the activity exchange, a durable topic exchange of the Twake Workplace broker, with the CloudEvent type as routing key.

The CloudEvent types are:

- `com.twake.calendar.space.provisioned.v1`: the space got its team calendar. A redelivered `twake.space.created` publishes it again.
- `com.twake.calendar.event.created.v1`: someone created an event in the team calendar.
- `com.twake.calendar.event.rescheduled.v1`: an event moved. The card keeps the previous start and end.
- `com.twake.calendar.event.updated.v1`: any other change to an event, or a tentative answer. The card's attendance counts show the tentative attendee.
- `com.twake.calendar.event.accepted.v1` and `com.twake.calendar.event.declined.v1`: an attendee accepted or declined.
- `com.twake.calendar.event.proposed.v1`: an attendee proposed another time.

Some changes publish nothing:

- events of personal calendars;
- imported events;
- changes and answers that concern a single occurrence of a recurring event, as a card shows the whole event.

TwakeSpace reads the changes of team calendars from the `calendar:event:created` and `calendar:event:updated` exchanges of the side service's broker. It reads the answers of attendees from the replies and counter proposals that sabre sends on `calendar:event:notificationEmail:send`:

- a reply names the attendee who answered, while the update sabre then makes to the team calendar does not;
- a counter proposal changes nothing in the event, so no created or updated message carries it.

It does not store any of them. Each card shows the event as one change left it, so two changes of an event handled at the same time may reach the feed in either order.

## Queues

Every replica consumes both queues.

- `tcalendar:twake-space` on the Twake Workplace broker receives the space events. It is a quorum queue unless `twp.queues.quorum.bypass` is set.
- `tcalendar:twake-space-calendar` on the side service's broker receives the changes of calendars. It follows `dav.queues.quorum.bypass`, like the other calendar queues.

An event that fails is not retried: it goes straight to the queue's dead letter queue, `tcalendar:twake-space-dead-letter` or `tcalendar:twake-space-calendar-dead-letter`. The RabbitMQ health check reports both queues and their dead letter queues. Space events can be replayed from the dead letter queue: a replayed event never overrides a newer one.

## Properties

These go in `rabbitmq.properties`, next to the other Twake Workplace broker properties, and are optional.

- `twakespace.exchange`: the exchange carrying space events. Defaults to `space`.
- `twakespace.activity.exchange`: the exchange the activity is published on. Defaults to `activity`.
