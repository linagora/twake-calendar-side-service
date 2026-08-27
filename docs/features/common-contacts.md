# Common Contacts

Synchronizes contact changes between Calendar and Twake Workplace.
- Enable the feature with `common.contacts.enabled=true` in `configuration.properties`.

## AMQP topology

RabbitMQ connection properties are configured in `rabbitmq.properties`.
- Sabre contact notifications use the standard Calendar/DAV RabbitMQ connection: `uri`.
- Normalized Common Contacts events and collected contacts use the Twake Workplace RabbitMQ connection: `twp.rabbitmq.uri`.
- When `twp.rabbitmq.uri` is not configured, Twake Workplace flows reuse `uri`.

## Outbound: Calendar -> Twake Workplace

### Source: Sabre contact notifications

- Broker: standard Calendar/DAV RabbitMQ connection (`uri`).
- Exchange type: `fanout`, durable.
- Routing key for every binding: empty string (`""`).
- Calendar provisions these source exchanges:
  - `sabre:contact:created`
  - `sabre:contact:updated`
  - `sabre:contact:deleted`
- Shared queue for the three exchanges: `tcalendar:common-contact`.
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
  - `action`: `ADD`, `UPDATE`, or `DELETE`, derived from the source exchange.
  - `path`: the contact resource path supplied by Sabre.
  - `uid`: the vCard UID.
  - `payload`: the JSContact `Card` (RFC 9553).

## Inbound: Twake Workplace -> Calendar

### Source: collected contacts

- Broker: Twake Workplace RabbitMQ connection (`twp.rabbitmq.uri`, or `uri` when not configured).
- Source exchange: `twake:contacts:collected`.
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
