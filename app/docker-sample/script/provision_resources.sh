#!/bin/bash
set -euo pipefail

SERVER_URL="http://tcalendar-side-service.linagora.local:8000"
DOMAIN="linagora.local"
RESOURCE_API="$SERVER_URL/domains/$DOMAIN/resources"

EXISTING_RESOURCES="$(curl --fail-with-body -sS -k "$RESOURCE_API")"

resource_exists() {
  resource_name="$1"
  printf '%s' "$EXISTING_RESOURCES" | grep -F -q "\"name\":\"$resource_name\""
}

provision_resource() {
  resource_name="$1"
  log_message="$2"
  payload="$3"

  if resource_exists "$resource_name"; then
    echo "Resource already exists: $resource_name, skipping"
    return
  fi

  echo "$log_message"
  response="$(curl --fail-with-body -sS -k -X POST "$RESOURCE_API" \
    -H "Content-Type: application/json" \
    -d "$payload")" || {
      printf '%s\n' "$response" >&2
      return 1
    }

  EXISTING_RESOURCES="$(curl --fail-with-body -sS -k "$RESOURCE_API")"
}

provision_resource "TV-VN" "Creating resource: TV (admins: bob, alice)" '{
        "name": "TV-VN",
        "description": "Television",
        "creator": "bob@linagora.local",
        "icon": "laptop",
        "administrators": [
            { "email": "bob@linagora.local" },
            { "email": "alice@linagora.local" }
        ]
      }'


provision_resource "Projector" "Creating resource: Projector (admin: bob)" '{
        "name": "Projector",
        "description": "Meeting room projector",
        "creator": "bob@linagora.local",
        "icon": "camera",
        "administrators": [
            { "email": "bob@linagora.local" }
        ]
      }'
