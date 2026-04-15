# Async Scheduling

The side service implements an event-driven architecture where **esn-sabre** publishes domain events to RabbitMQ exchanges and the side service reacts asynchronously through dedicated consumers.

The architectural decision is documented in [ADR-0001](https://github.com/linagora/esn-sabre/blob/master/adr/0001-async-scheduling.md).

All exchanges are **FANOUT** and durable. All queues have an associated dead-letter queue for failed messages.

---

## RabbitMQ Consumers (ADR-0001 Scope)

| Consumer class | What it does | Publisher | Listens to exchange(s) | Queue(s) | Dead-letter queue(s) | Produces to |
|---|---|---|---|---|---|---|
| `ItipLocalDeliveryConsumer` | Implements ADR-0001 fan-out then process for local delivery. Phase 1 splits N recipients into N single-recipient messages on the same exchange. Phase 2 calls `POST /itip` and publishes email notification payload. | esn-sabre DAV server (via `AMQPSchedulePlugin`) | `calendar:itip:localDelivery` | `tcalendar:itip:localDelivery` | `tcalendar:itip:localDelivery:dead-letter` | `calendar:event:notificationEmail:send` via `ItipEmailNotificationPublisher` |
| `EventEmailConsumer` | Sends notification emails based on payload emitted by `ItipLocalDeliveryConsumer`. | `ItipEmailNotificationPublisher` (inside side service) | `calendar:event:notificationEmail:send` | `tcalendar:event:notificationEmail:send` | `tcalendar:event:notificationEmail:send:dead-letter` | - |

---

## RabbitMQ Topology

![RabbitMQ async scheduling topology](./assets/async-scheduling-topology.png)

Mermaid source:

```mermaid
flowchart LR
    exItip(("Exchange: calendar:itip:localDelivery"))
    exEmail(("Exchange: calendar:event:notificationEmail:send"))

    qItip["Queue: tcalendar:itip:localDelivery"]
    dlqItip["Queue: tcalendar:itip:localDelivery:dead-letter"]
    qEmail["Queue: tcalendar:event:notificationEmail:send"]
    dlqEmail["Queue: tcalendar:event:notificationEmail:send:dead-letter"]

    decide{"recipients > 1 ?"}
    stepSingle["process single recipient<br/>AND publish notification message"]

    exItip --> qItip --> decide
    decide -->|yes| exItip
    decide -->|single recipient| stepSingle --> exEmail
    exEmail --> qEmail

    qItip -. failure .-> dlqItip
    qEmail -. failure .-> dlqEmail

    classDef exchange fill:#E8F1FF,stroke:#2F6FEB,stroke-width:2px,color:#0B1F44;
    classDef queue fill:#ECFDF3,stroke:#1A7F37,stroke-width:1.5px,color:#0F2A1C;
    classDef dlq fill:#FFF1F2,stroke:#C62828,stroke-width:1.5px,color:#4A0E0E;
    classDef logic fill:#FFF9DB,stroke:#B08900,stroke-width:1.5px,color:#3B2F00;

    class exItip,exEmail exchange;
    class qItip,qEmail queue;
    class dlqItip,dlqEmail dlq;
    class decide,stepSingle logic;
```
