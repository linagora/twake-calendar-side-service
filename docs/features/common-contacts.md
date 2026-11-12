# Common Contacts

Synchronizes contact changes between Calendar and Twake Workplace.
- Enable the feature with `common.contacts.enabled=true` in `configuration.properties`.

## AMQP topology

RabbitMQ connection properties are configured in `rabbitmq.properties`.
- Sabre contact notifications use the standard Calendar/DAV RabbitMQ connection: `uri`.
- Normalized Common Contacts events and collected contacts use the Twake Workplace RabbitMQ connection: `twp.rabbitmq.uri`.
- When `twp.rabbitmq.uri` is not configured, Twake Workplace flows reuse `uri`.

## Outbound: Calendar -> Twake Workplace

### Source: Sabre contact and address book notifications

- Broker: standard Calendar/DAV RabbitMQ connection (`uri`).
- Exchange type: `fanout`, durable.
- Routing key for every binding: empty string (`""`).
- Calendar provisions these source exchanges:
  - `sabre:contact:created`
  - `sabre:contact:updated`
  - `sabre:contact:deleted`
  - `sabre:addressbook:deleted`
- Shared queue for the four exchanges: `tcalendar:common-contact`.
- Dead-letter queue: `tcalendar:common-contact:dead-letter`.

### Destination: Common Contacts exchange

- Broker: Twake Workplace RabbitMQ connection (`twp.rabbitmq.uri`, or `uri` when not configured).
- Output exchange: the `common.contacts.exchange` property; defaults to `twake:contacts:common`.
- Exchange type: `fanout`, durable.
- Routing key when publishing: empty string (`""`).

### Common Contacts event

Sample payload:

```json
{
  "audience": { "user": "owner@example.com" },
  "action": "ADD",
  "path": "addressbooks/64d2a99f1d4ddc4e88e4e001/contacts/contact-uid.vcf",
  "uid": "contact-uid",
  "revision": 1234,
  "etag": "\"5d41402abc4b2a76b9719d911017c592\"",
  "payload": {
    "@type": "Card",
    "version": "2.0",
    "uid": "contact-uid",
    "name": { "@type": "Name", "full": "Alice Example" },
    "emails": {
      "EMAIL-1": { "@type": "EmailAddress", "address": "alice@example.com" }
    }
  }
}
```

- Event fields:
  - `audience`: an object containing `user`, `domain`, or neither.
  - `action`: `ADD`, `UPDATE`, or `DELETE`, derived from the source exchange. `SYNC_START` or `SYNC_END` for a
    [republication](#republication-of-an-address-book).
  - `path`: the contact resource path supplied by Sabre, in the address book holding the contact.
  - `uid`: the vCard UID.
  - `revision`: the sync token of the address book holding the contact, after the change. It grows with every change
    of the address book.
  - `etag`: the etag of the contact. For `DELETE`, the one of the deleted contact.
  - `payload`: the JSContact `Card` (RFC 9553).

A contact belongs to the owner of its address book: whoever writes it, and whichever way, one event is published, on the
path of that address book and with its owner as audience. The copies of the contact in the address books subscribed to,
or delegated from, that address book are not published (Sabre notifications carrying a `sourcePath`).

Events can be delivered out of order. Applications should drop an event whose `revision` is lower than the one they
hold for its `path`, and keep the revision of a deleted contact so that a late `ADD` or `UPDATE` does not bring it back.
Republished contacts carry the revision of their address book when the republication read it, before reading the
contacts: an unchanged contact comes with a higher `revision` but the same `etag`.

Deleting an address book emits one `DELETE` event for the whole address book. Its `audience` is resolved
from the Sabre `owner`, and its `path` identifies the address book. The event omits `uid` and `payload`:

```json
{
  "audience": { "user": "owner@example.com" },
  "action": "DELETE",
  "path": "addressbooks/64d2a99f1d4ddc4e88e4e001/custom"
}
```

Applications must remove every contact under that address book path. Sabre does not emit individual
contact deletion notifications when deleting the address book.

### Republication of an address book

A [republication](../apis/webadmin.md#republish-all-contacts) sends each address book as a sync: a `SYNC_START`
event, one `ADD` event per contact, then a `SYNC_END` event. The events of a sync share a `syncId`,
`<task id>:<address book path>`. Events forwarded from Sabre carry no `syncId`.

```json
{
  "audience": { "domain": "example.com" },
  "action": "SYNC_START",
  "path": "addressbooks/64d2a99f1d4ddc4e88e4e001/domain-members",
  "syncId": "05641376-02ed-47bd-bcc7-76ff6262d92a:addressbooks/64d2a99f1d4ddc4e88e4e001/domain-members",
  "revision": 1234
}
```

```json
{
  "audience": { "domain": "example.com" },
  "action": "ADD",
  "path": "addressbooks/64d2a99f1d4ddc4e88e4e001/domain-members/contact-uid.vcf",
  "uid": "contact-uid",
  "revision": 1234,
  "etag": "\"5d41402abc4b2a76b9719d911017c592\"",
  "syncId": "05641376-02ed-47bd-bcc7-76ff6262d92a:addressbooks/64d2a99f1d4ddc4e88e4e001/domain-members",
  "payload": { "@type": "Card", "version": "2.0", "uid": "contact-uid" }
}
```

```json
{
  "audience": { "domain": "example.com" },
  "action": "SYNC_END",
  "path": "addressbooks/64d2a99f1d4ddc4e88e4e001/domain-members",
  "syncId": "05641376-02ed-47bd-bcc7-76ff6262d92a:addressbooks/64d2a99f1d4ddc4e88e4e001/domain-members",
  "revision": 1234,
  "complete": true,
  "count": 1
}
```

- `path`: the address book, for `SYNC_START` and `SYNC_END`.
- `revision`: the sync token of the address book, read right before fetching its contacts. The same in every event of the sync.
- `complete`: whether every contact of the address book was sent.
- `count`: the number of `ADD` events of the sync.

A contact failing to be sent does not stop the sync: the other contacts are sent and `complete` is `false`. An address
book that cannot be read is not sent at all. A `SYNC_END` failing to be published is lost.

Address books are sent one at a time, but events forwarded from Sabre can come in between. On a `SYNC_END` with
`complete` set, once it received `count` distinct contacts carrying its `syncId`, an application can remove the
contacts of that address book that the sync did not send and whose revision it holds is not higher than the `revision`
of the sync: a higher one comes from a change made after the address book was read. After such a sync, an event of
that address book whose `revision` is not higher than the one of the sync is stale.

## Inbound: Twake Workplace -> Calendar

### Source: collected contacts

- Broker: Twake Workplace RabbitMQ connection (`twp.rabbitmq.uri`, or `uri` when not configured).
- Source exchange: the `common.contacts.collected.exchange` property; defaults to `twake:contacts:collected`.
- Queue: `tcalendar:contacts:collected`.
- Dead-letter queue: `tcalendar:contacts:collected-dead-letter`.
- Consumer: single active consumer.
- Queue type:
  - Default: quorum queue.

### Message inbound

- Body: JSON object.
- Required fields:
  - `userEmail`: the target user's email address. Calendar resolves this user in the local user store and writes contacts to that user's CardDAV address book.
  - `collectedContacts`: the contacts to write for the target user. Each item is an RFC 9553 JSContact `Card`.
- Unknown top-level fields are ignored.

Sample payload:

```json
{
  "userEmail": "alice@example.com",
  "collectedContacts": [
    {
      "@type": "Card",
      "version": "2.0",
      "uid": "contact-uid",
      "name": { "@type": "Name", "full": "Bob Example" },
      "emails": {
        "main": { "@type": "EmailAddress", "address": "bob@example.com" }
      },
      "onlineServices": {
        "matrix": {
          "@type": "OnlineService",
          "service": "matrix",
          "user": "@bob:matrix.example.com"
        }
      }
    }
  ]
}
```
