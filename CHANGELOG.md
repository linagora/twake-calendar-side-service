# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)

## [unreleased]

### New Features

 - ISSUE-864 Booking links: reference the originating booking link in generated event ICS through the `X-OPENPAAS-BOOKING-LINK` property
 - ISSUE-867 Booking links: index the originating booking link and expose a `bookingLink` event search criterion on it
 - ISSUE-875 Booking links: admin task to delete all events created by a booking link (with an optional `since` filter), convenient for mass clean up e.g. after a link compromission
 - ISSUE-1068 Webadmin: `POST /users/{usernameToBeUsed}/calendars/{calendarId}?action=export` returns the ICS content of a calendar
 - ISSUE-1074 Webadmin: `POST /users/{usernameToBeUsed}/calendars/{calendarId}?action=import` and `POST /users/{username}/addressbooks/{addressBookId}?action=import` plan a task importing the supplied ICS / vCards into the calendar / address book
 - ISSUE-1080 Webadmin: `POST /unsentMails?action=delete` plans a task deleting the unsent mails matching the `sender`, `recipient` and `limit` filters, sparing admins from deleting them one by one
 - ISSUE-1078 Webadmin: `PATCH /users/{username}/addressbooks/{addressBookId}` updates the name and description of an address book
 - ISSUE-1076 Webadmin: count, export and import the events of the calendars a domain owns, through `GET /domains/{domain}/team-calendars/{teamCalendarId}/eventCount`, `POST /domains/{domain}/team-calendars/{teamCalendarId}?action=export|import` and their `/domains/{domain}/resources/{resourceId}` counterparts
 - ISSUE-1098 Webadmin: `POST /domains/{domain}/team-calendars/{teamCalendarId}/publicRight` and `POST /domains/{domain}/resources/{resourceId}/publicRight` change the public visibility of team calendars and resources
 - ISSUE-958 Booking links: optional `extraAttendees` field, to hand over a single link for a meeting involving several people. Offered slots intersect the availability of the extra attendees, as seen by the booking link owner, and booked events invite them.
 - ISSUE-1106 Common Contacts: backend autocomplete API `POST /api/people/search` on a dedicated port (`common.contact.api.port`), authenticated with the Bearer tokens listed in `common.contact.api.secrets`
 - ISSUE-1116 Webadmin: `GET /domains/{domain}/addressbooks/{addressBookId}/contactCount` counts the contacts of the address books a domain owns (`dab`, `domain-members`)
 - ISSUE-1117 Webadmin: `POST /domains/{domain}/addressbooks/{addressBookId}?action=export` returns the vCards of a domain address book (`dab`, `domain-members`), `?action=import` plans a task importing the supplied vCards into it (`domain-members` excluded)
 - Mail templates: Spanish (`es`), German (`de`) and Italian (`it`) translations of every email
 - ISSUE-1118 Webadmin: `DELETE /domains/{domain}/addressbooks/{addressBookId}/contacts[?sourceDomain=]` plans a task clearing the contacts of a domain address book (`domain-members` excluded), optionally only those having a mail address within `sourceDomain`
 - ISSUE-1119 Webadmin: `POST /domains/{domain}/addressbooks/{addressBookId}?action=copyFrom&sourceDomain=...[&ldapFilter=...]` plans a task copying the users of `sourceDomain` into a domain address book (`domain-members` excluded), e.g. letting teachers auto-complete students but not the other way around

### Fixes

 - ISSUE-1074 `POST /api/import` now shares its parsing with the webadmin import: imported events keep the calendar level properties and time zones they rely on, and re-importing the same vCard file updates the contacts rather than duplicating them.
 - Mail templates: a language without translations now falls back to English instead of the JVM default locale

## [2.1.0] - 2026-05-07

### New Features

 - ISSUE-1222 Webadmin: Scope resources by domain for multi-tenant deployments
 - ISSUE-1221 Webadmin: Additional webadmin endpoints to list registered users scoped by domain
 - ISSUE-1220 Webadmin: Multi-tenant friendly domain-scoped task routes
 - ISSUE-723 Introduce `user.search.limited.domains` configuration to restrict user search to specific domains
 - ISSUE-703 Include domain in technical domain validation response
 - ISSUE-712 Adapt delegation notification emails for resource administrators

### Fixes

 - ISSUE-721 fix(provisioning): handle concurrent user creation conflicts
 - ISSUE-718 Prevent iTIP/IMIP processing for resource calendars
 - ISSUE-717 fix(calendar-amqp): handle invalid recurrence-id when computing recurrences
 - ISSUE-715 Fix mail notification showing incorrect "Previous time" when updating an overridden recurrence instance
 - ISSUE-709 Improve provisioning with systematic names resolution
 - ISSUE-701 Add missing Dead Letter Queue exchange
 - ISSUE-698 fix(itip): skip REPLY delivery when attendee PARTSTAT is unchanged
 - ISSUE-697 Ignore invalid REQUEST iTIP local deliveries
 - ISSUE-686 fix(calendar-notifications): send email when a resource calendar is delegated to a user
 - [FIX] List registered users endpoint should be lenient on invalid entries
 - [FIX] Make CaffeineOidcTokenCache concurrency-safe
 - [FIX] Run Caffeine synchronous cache operations on bounded elastic scheduler

### Improvements

 - [ENHANCEMENT] Add UID and EventPath fields to iTIP/IMIP logs for easier tracing
 - SABRE-328 Add more alarm test cases
 - Pin JDK 25 for builds
