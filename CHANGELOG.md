# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)

## [unreleased]

### New Features

 - ISSUE-864 Booking links: reference the originating booking link in generated event ICS through the `X-OPENPAAS-BOOKING-LINK` property
 - ISSUE-867 Booking links: index the originating booking link and expose a `bookingLink` event search criterion on it
 - ISSUE-875 Booking links: admin task to delete all events created by a booking link (with an optional `since` filter), convenient for mass clean up e.g. after a link compromission
 - ISSUE-958 Booking links: optional `extraAttendees` field, to hand over a single link for a meeting involving several people. Offered slots intersect the availability of the extra attendees, as seen by the booking link owner, and booked events invite them.

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
