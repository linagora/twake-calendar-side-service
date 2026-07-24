#!/bin/sh
set -eu

SERVER_URL="${SERVER_URL:-http://tcalendar-side-service.linagora.local:8000}"
DOMAIN="${DOMAIN:-linagora.local}"
TEAM_CALENDAR_NAME="${TEAM_CALENDAR_NAME:-team-calendar}"
TEAM_CALENDAR_DISPLAY_NAME="${TEAM_CALENDAR_DISPLAY_NAME:-Team Calendar}"
TEAM_CALENDAR_API="$SERVER_URL/domains/$DOMAIN/team-calendars"

extract_team_calendar_id() {
  sed -n 's/.*"id":"\([^"]*\)".*/\1/p'
}

find_team_calendar_id() {
  team_calendars="$1"

  printf '%s' "$team_calendars" \
    | tr -d '\n' \
    | sed 's/},{/}\
{/g' \
    | grep -F "\"name\":\"$TEAM_CALENDAR_NAME\"" \
    | head -n 1 \
    | extract_team_calendar_id
}

echo "Looking for team calendar: $TEAM_CALENDAR_NAME"
existing_team_calendars="$(curl --fail-with-body -sS -k "$TEAM_CALENDAR_API")"
team_calendar_id="$(find_team_calendar_id "$existing_team_calendars" || true)"

if [ -n "$team_calendar_id" ]; then
  echo "Team calendar already exists: $TEAM_CALENDAR_NAME ($team_calendar_id)"
else
  echo "Creating team calendar: $TEAM_CALENDAR_NAME"
  response="$(curl --fail-with-body -sS -k -X POST "$TEAM_CALENDAR_API" \
    -H "Content-Type: application/json" \
    -d "{
      \"name\": \"$TEAM_CALENDAR_NAME\",
      \"displayName\": \"$TEAM_CALENDAR_DISPLAY_NAME\"
    }")" || {
      printf '%s\n' "$response" >&2
      exit 1
    }

  team_calendar_id="$(printf '%s' "$response" | extract_team_calendar_id)"
fi

if [ -z "$team_calendar_id" ]; then
  echo "Unable to resolve team calendar id for: $TEAM_CALENDAR_NAME" >&2
  exit 1
fi

echo "Adding team calendar members: bob (admin), alice (read-write)"
curl --fail-with-body -sS -k -X POST "$TEAM_CALENDAR_API/$team_calendar_id/members/invitee" \
  -H "Content-Type: application/json" \
  -d '{
    "share": {
      "set": [
        {"dav:href": "mailto:bob@linagora.local", "dav:administration": true},
        {"dav:href": "mailto:alice@linagora.local", "dav:read-write": true}
      ]
    }
  }'

echo ""
