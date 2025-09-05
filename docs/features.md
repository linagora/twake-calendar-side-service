# Features

Exposes [a subset of the OpenPaaS API](apis/openpaasApi.md), allowing OpenPaaS SPA to run smoothly atop this side service.

[Proxies dav calls](apis/davProxy.md) in order to enable direct OIDC authentication for the SPA in order not to rely on secondary authentication
mechanisms.

Exposes a [webadmin interface](apis/webadmin.md) for management.

Additionally, the side service handles sending emails for the calendar stack. Mails are internationalized based on user
settings. This includes:

- Even email notification:
    - Upon new event send calendar invites to attendees
    - Upon event updates notifies attendees
    - Upon event cancellation, notifies attendees
    - Upon attendee attendance status update, notifies the organizer
    - Upon counter proposal, sends a email to the organizer to moderate the counter proposal
- Event alarms: send email reminder to the user as set up in the event on fix date, which involves scheduling.
- Resource moderation request, allowing resource administrator to accept/deny events in the name of resources
- Resource moderation notifications, letting event organizer know about resource moderation decisions
- Calendar / address book import notifications

The side service also enables synchronizing domain members into ESN-Sabre DAV server.

## Automatic user provisioning

If enabled, users are automatically registered upon their first OpenID connect interaction.

The following claims are used:
 - `email` to determine the email
 - `given_name` (may be omitted) to determine the firstname
 - `family_name` (may be omitted) to determine the lastname

Otherwise, the project exposes a [webadmin endpoint for user registration](apis/webadmin.md#registration-of-a-new-user).