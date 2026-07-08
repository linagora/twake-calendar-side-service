# Emails sent by the calendar

The side service sends the transactional emails for the calendar stack. All mails are internationalized
based on the recipient user settings (locale and timezone) and rendered from the
[Pug templates](../app/src/main/resources/templates).

The table below summarizes every case where a mail is sent and to whom.

| Trigger | Recipient | Template |
| --- | --- | --- |
| A new event is created | Attendees | `event-invite` |
| An event is updated | Attendees | `event-update` |
| An event is cancelled | Attendees | `event-cancel` |
| An attendee replies (accept / decline / tentative) | Organizer | `event-reply` |
| An attendee proposes a counter (new time) | Organizer | `event-counter` |
| An event alarm fires | The user who owns the alarm | `event-alarm` |
| A resource is booked in an event | The resource administrator(s) | `resource-request` |
| A resource administrator accepts or declines a booking | The event organizer | `resource-reply` |
| A calendar is shared (delegation) | The delegated user | `calendar-delegate-created` |
| A user is added as administrator of a resource | The new administrator | `calendar-resource-admin-created` |
| A calendar import completes | The user who requested the import | `import-calendar` |
| A contacts import completes | The user who requested the import | `import-contacts` |
| A booking link reservation is created (acknowledgement) | The booker | `event-booking-request-received` |
| A booking link reservation is created (ICS proposal) | The booking link owner | `event-propose` |
| A booking link owner confirms a reservation | The booker | `event-booking-confirmed` |
| A booking link reservation is cancelled (acknowledgement) | The booker | `event-booking-request-cancelled` |
| A booking link reservation is cancelled (ICS cancellation) | The booking link owner | `event-cancel` |
