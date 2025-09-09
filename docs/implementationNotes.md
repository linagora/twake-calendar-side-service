# Implementation notes

## Alarm scheduling

Alarms are being schedules when receiving corresponding RabbitMQ events sent by **esn-sabre** on the following exchanges:

- `calendar:event:alarm:created`
- `calendar:event:alarm:updated`
- `calendar:event:alarm:deleted`
- `calendar:event:alarm:cancel`
- `calendar:event:alarm:request`

Upcoming alarms are stored in the `calendar:event:alarm:created` collection.

The scheduler poll recurring this collection in order to find new alarms. Upon new alarms it takes a lease onto the `twake_calendar_alarm_events` 
collection to protect from concurrent updates, then send the alarm. For recurring events a new entry is saved in `twake_calendar_alarm_events_ledge`
collection for triggering the alarm for next occurrence and the email is sent if the event is not outdated.

This overall algorithm is:
 - Ensuring **at least once** delivery of alarm emails when faced with node failures and extreme timeouts.
 - Resilient if stopped for extended period of time as past recurring events are safely expended.