# Emails sent by the calendar

The side service sends the transactional emails for the calendar stack. Templates use the recipient's locale and,
where relevant, timezone, with fallback settings when needed. They are rendered from the
[Pug templates](../app/src/main/resources/templates).

The table below summarizes every case where a mail is sent and to whom.

| Trigger | Recipient | Template |
| --- | --- | --- |
| A new event is created | Attendees | `event-invite` |
| SMTP rejects a new invitation's attendee with `550 5.1.1 Unknown user` | Event organizer (or the notification's `senderEmail`) | `mail-delivery-failed` |
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
| A booking link reservation is cancelled (ICS cancellation) | The booking link owner | `event-cancel` |

## Unsent mails

For SMTP failures other than the `Unknown user` case below, the side service retries delivery. If delivery still
fails, the mail is retained (when retention is configured and the message fits the size limit) - envelope, MIME
message and failed sending trials - so that an administrator can inspect it and schedule its re-emission through the
[unsent mails webadmin routes](apis/webadmin.md#unsent-mails-routes). A resend pushes the very MIME message that
had been submitted: no template is rendered anew.

A retained mail is kept until it is deleted or successfully resent.

When every recipient is rejected with `550 5.1.1 Unknown user`, delivery is not retried or retained. For each failed
new invitation, the side service attempts to send the organizer a localized notification containing the failed
email's subject and recipient address. If the event has no organizer, it uses the notification's `senderEmail`.
Ordinary sends of other email types discard this specific SMTP failure.
