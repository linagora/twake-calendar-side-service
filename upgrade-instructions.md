# Upgrade Instructions

This document describes breaking changes and migration steps required when upgrading to newer versions of Twake Calendar.

## [UNRELEASED]

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