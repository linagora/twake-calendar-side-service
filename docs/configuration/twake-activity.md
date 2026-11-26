# Twake activity

Twake activity tells Twake Workplace what users do in the calendar and in the contacts: each time a user creates an event or a contact, the side service publishes one CloudEvent on the activity exchange of the Twake Workplace broker. It runs when `configuration.properties` enables it:

```
twp.settings.enabled=true
twake.activity.enabled=true
```

It needs `twp.settings.enabled`, as it publishes on the [Twake Workplace broker](configuration.md#twake-workplace-rabbitmq-properties). The startup fails without it.

It is independent of [TwakeSpace](twake-space.md): it covers every calendar and every address book of every user, not only team calendars. Both are part of the `twake-extension` module (`twake/extension`) and publish on the same exchange, `activity` by default.

## Published activities

| Change | Routing key | CloudEvent `type` | `source` |
| --- | --- | --- | --- |
| A user creates an event | `calendar.eventCreated` | `com.twake.calendar.eventCreated.v1` | `twake://calendar` |
| A user creates a contact | `contacts.contactCreated` | `com.twake.contacts.contactCreated.v1` | `twake://contacts` |

The routing key is `{application}.{type}`. A consumer binds its queue to the `activity` topic exchange with `#` to receive every activity, `calendar.#` or `contacts.#` for one application, or a full routing key for a single kind of activity.

Updates and deletions publish nothing yet.

### Message

The message is persistent, its content type is `application/cloudevents+json` and its AMQP `messageId` is the CloudEvent `id`. Its body is a [CloudEvent 1.0](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md) in structured mode.

An event created in a calendar:

```json
{
  "specversion": "1.0",
  "id": "5b1f0c2e-8d3a-3c4e-9a51-2f6e7d8c9b10",
  "source": "twake://calendar",
  "type": "com.twake.calendar.eventCreated.v1",
  "time": "2026-10-06T12:00:00Z",
  "subject": "calendars/64b0f1e2a1b2c3d4e5f60718/64b0f1e2a1b2c3d4e5f60718/3e5a.ics",
  "twakeactor": "btellier@linagora.com",
  "twakedomain": "linagora.com",
  "twakeresourceowner": "btellier@linagora.com",
  "data": {
    "path": "calendars/64b0f1e2a1b2c3d4e5f60718/64b0f1e2a1b2c3d4e5f60718/3e5a.ics",
    "calendar": "calendars/64b0f1e2a1b2c3d4e5f60718/64b0f1e2a1b2c3d4e5f60718"
  }
}
```

A contact created in an address book:

```json
{
  "specversion": "1.0",
  "id": "9e2d4a71-3b6c-3f80-8e12-7c5a1b0d4f33",
  "source": "twake://contacts",
  "type": "com.twake.contacts.contactCreated.v1",
  "time": "2026-10-06T12:00:00Z",
  "subject": "addressbooks/64b0f1e2a1b2c3d4e5f60718/contacts/7c1d.vcf",
  "twakeactor": "btellier@linagora.com",
  "twakedomain": "linagora.com",
  "twakeresourceowner": "btellier@linagora.com",
  "data": {
    "path": "addressbooks/64b0f1e2a1b2c3d4e5f60718/contacts/7c1d.vcf",
    "addressbook": "addressbooks/64b0f1e2a1b2c3d4e5f60718/contacts"
  }
}
```

| Attribute | Content |
| --- | --- |
| `id` | A UUID named after the type, the path and the etag sabre gives the created resource. A redelivered message keeps its `id`, so that a consumer can drop duplicates; recreating a resource at the same path gives another `id`. |
| `time` | When the side service handled the change. Sabre does not tell when the change was made. |
| `subject` | The DAV path of the created resource, without leading slash. |
| `twakeactor` | The mail address of the user who made the change. A CloudEvent extension is a string, never an object. |
| `twakedomain` | The mail domain of `twakeactor`. |
| `twakeresourceowner` | The mail address of the user owning the calendar or the address book that holds the created resource. It is `twakeactor` when users write their own calendars and address books, and another user for a delegated calendar or address book. Absent when the calendar or the address book belongs to no user, such as the domain address book or the calendar of a resource. |
| `data.path` | The DAV path of the created resource: `calendars/{calendarHomeId}/{calendarId}/{resource}.ics` or `addressbooks/{addressBookHomeId}/{addressBookName}/{resource}.vcf`. |
| `data.calendar` | Calendar activities only: the DAV path of the calendar holding the event. |
| `data.addressbook` | Contacts activities only: the DAV path of the address book holding the contact. |

The `type` names the application and carries a version: a change that breaks the contract publishes a `.v2` type. They differ from the `com.twake.calendar.event.*.v1` types that TwakeSpace publishes on the same exchange for its team calendars.

### Who the actor and the resource owner are

The actor is the user sabre names in `connectedUser`, who made the change, possibly on a calendar or an address book delegated to them. The resource owner is the user of the calendar home (`calendars/{calendarHomeId}/...`) or the user principal sabre gives as `owner` of the address book; a consumer compares it with `twakeactor` to tell a change made by a delegate. A change made with a technical token names no user: the owner of the calendar or of the address book then stands for the actor. When neither is a known user, for instance a contact written by the platform in a domain address book, nothing is published and the side service logs it at `INFO`.

### What publishes nothing

- An event written by a calendar import (sabre flags it `import`): importing a calendar would publish one activity per event.
- The copies sabre sends for the address books subscribed to or delegated from the one holding the contact (they carry a `sourcePath`): only the original publishes.

Things to know:

- A vCard import publishes one activity per contact, as sabre does not tell an imported contact from another.
- Sabre sends a `calendar:event:created` for each calendar the event lands in: an event created in a calendar that is shared, or sent to attendees of the same platform, may publish one activity per calendar, each with its own `path`, `calendar` and `id`.
- Two activities handled at the same time may reach the exchange in either order.

## Queues

The side service reads the `calendar:event:created` and `sabre:contact:created` exchanges of its own broker in the `tcalendar:twake-activity` queue. It follows `dav.queues.quorum.bypass`, like the other calendar queues. Every replica consumes it.

A message is acknowledged once RabbitMQ has confirmed its activity. A message that fails, for example an unreadable one or one whose activity the broker refuses, is not retried: it goes to `tcalendar:twake-activity-dead-letter`. The RabbitMQ health check reports the queue and its dead letter queue.

## Properties

This goes in `rabbitmq.properties`, next to the other Twake Workplace broker properties, and is optional.

- `twake.activity.exchange`: the exchange the activity is published on, declared as a durable topic exchange. Defaults to `activity`.
