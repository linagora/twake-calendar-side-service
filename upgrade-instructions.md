# Upgrade Instructions

This document describes breaking changes and migration steps required when upgrading to newer versions of Twake Calendar.

## 2.4.0 (upcoming)

### Rebuilt calendar event search index around source calendars

Date: 12/06/2026

Calendar event search indexing now uses the event source calendar as the indexing scope instead of the
OpenPaaS account. This allows searching events from personal calendars, subscribed calendars, delegated
calendars and resource calendars through their resolved source `CalendarURL`.

The OpenSearch document identity and routing changed accordingly:

- old document IDs were based on `accountId`;
- new document IDs are based on `baseCalendarId`;
- new documents store and query `baseCalendarId` instead of `accountId`;
- full reindexing now covers both user calendars and resource calendars.

#### Breaking Change

Existing OpenSearch documents are not compatible with the new indexer. They still contain `accountId`,
use old document IDs, and do not contain `baseCalendarId`. Reusing the existing calendar event index can
lead to stale or duplicated search results.

#### Required Actions

Before deploying the upgraded calendar-side-service, configure a new calendar event index and new aliases
in `opensearch.properties` to avoid conflicts with the old index:

```properties
opensearch.index.calendar.events.name=calendar-events-v2
opensearch.alias.read.calendar.events.name=calendar-events-v2-read
opensearch.alias.write.calendar.events.name=calendar-events-v2-write
```

Use values different from the previous ones.

After deploying the upgraded service with the new OpenSearch settings and confirming it starts without
OpenSearch index or alias errors, run a full reindex so that user calendar events and resource calendar
events are indexed into the new index:

```
POST {webadminBaseURL}/calendars?task=reindex
```

After reindexing, verify that event search works with the new index. Then delete the old calendar event
index only after confirming no calendar-side-service instance still uses the old aliases:

```bash
DELETE /calendar-events
```

## 2.2.0

### Removed obsolete search queues

Date: 02/06/2026

The search indexer no longer binds the search queues
`tcalendar:event:request:search` and `tcalendar:event:cancel:search`
For Sabre 4.7, equivalent search payloads are already delivered through
the existing search queues bound to exchange `calendar:event:updated` and `calendar:event:deleted`.

#### Breaking Change

Old deployments may still have these `:search` bindings and queues.
If left in place, messages will keep accumulating there with no consumer.

#### Required Actions

After all calendar-side-service instances have been upgraded, delete these obsolete search queues:

- `tcalendar:event:request:search`
- `tcalendar:event:request:search-dead-letter`
- `tcalendar:event:cancel:search`
- `tcalendar:event:cancel:search-dead-letter`

### Added resourceName field to Calendar Event index mapping

Date: 13/05/2026

We added the `resourceName` field to the OpenSearch calendar event index. This field stores the actual
CalDAV resource filename (e.g. `sabredav-a1888d6e-….ics`) as chosen by the CalDAV client when the event
was created. It was previously absent, causing the search API (`POST /calendar/api/events/search`) to
build event `href` values from the VEVENT UID instead of the real resource path, which produced broken
links when the two differed.

#### Breaking Change

Existing indexed documents do not contain `resourceName`. Their search results will still fall back to
the UID-based href until they are reindexed.

#### Required Actions

**1. Add the field to your existing index mapping:**

```bash
PUT /calendar_events/_mapping
{
  "properties": {
    "resourceName": {
      "type": "keyword",
      "index": false
    }
  }
}
```

**2. Run a full reindex** so that all existing documents get the correct `resourceName` value:

```
POST {webadminBaseURL}/calendars?task=reindex
```

Without step 2, events indexed before this upgrade will continue to return a UID-based href in search
responses.

**Note:** Replace `calendar_events` with your actual index name if different.

## 2.1.0 

This version is a rolling update from 2.0.0

## Previous versions

### Changed document ID computation for OpenSearch indexing

Date: 09/12/2025

Updated Event Document ID computation to remove DTSTART, ensuring a stable identifier and preventing duplicate documents
in OpenSearch.

#### Breaking Change

Existing indexed documents still use the old DTSTART-based IDs. Updates to existing events may create duplicates after
deployment.

#### Required Action

Admins **must run a full reindex** after upgrading:

```
POST {webadminBaseURL}/calendars?task=reindex
```

Without this step, old and new documents may coexist and appear duplicated in search.

### Added sequence field to Calendar Event index mapping

Date: 03/12/2025

We added the `sequence` field to support proper event versioning and conflict resolution during indexing.  
This field must be declared in the OpenSearch mapping as an `integer` (non‑indexed).

If you have an existing OpenSearch index for calendar events, you need to add the `sequence` field to your
index mapping:

Add the new field to your existing index using the OpenSearch API:

```bash
PUT /calendar_events/_mapping
{
  "properties": {
    "sequence": {
      "type": "integer",
      "index": false
    }
  }
}
```

### Added videoconferenceUrl field to Event Search API

Date: 01/12/2025

We added support for returning video conference URLs in the Calendar Search API (`POST /calendar/api/events/search`). 
Events containing the `X-OPENPAAS-VIDEOCONFERENCE` property will now include this value in the search response as `x-openpaas-videoconference`.

This enhancement enables the frontend to display a "Join visio" button for calendar events with attached video meetings, 
improving user experience.

Impact: This is a **breaking change** for existing OpenSearch indices. 

If you have an existing OpenSearch index for calendar events, you need to add the `videoconferenceUrl` field to your index mapping:

Add the new field to your existing index using the OpenSearch API:

```bash
PUT /calendar_events/_mapping
{
  "properties": {
    "videoconferenceUrl": {
      "type": "keyword",
      "index": false
    }
  }
}
```

**Note:** Replace `calendar_events` with your actual index name if different.