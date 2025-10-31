#!/bin/bash
set -euo pipefail

SERVER_URL="http://tcalendar-side-service.linagora.local:8000"
RESOURCE_API="$SERVER_URL/resources"

echo "Creating resource: TV (admins: bob, alice)"
curl -k -X POST "$RESOURCE_API" \
  -H "Content-Type: application/json" \
  -d '{
        "name": "TV-VN",
        "description": "Television",
        "creator": "bob@linagora.local",
        "icon": "laptop",
        "domain": "linagora.local",
        "administrators": [
            { "email": "bob@linagora.local" },
            { "email": "alice@linagora.local" }
        ]
      }'


echo "Creating resource: Projector (admin: bob)"
curl -k -X POST "$RESOURCE_API" \
  -H "Content-Type: application/json" \
  -d '{
        "name": "Projector",
        "description": "Meeting room projector",
        "creator": "bob@linagora.local",
        "icon": "camera",
        "domain": "linagora.local",
        "administrators": [
            { "email": "bob@linagora.local" }
        ]
      }'
