#!/bin/sh

# Import RabbitMQ definitions using the management API

# Configuration
RABBITMQ_HOST="http://rabbitmq:15672"
USERNAME="tcalendar"
PASSWORD="tcalendar"
DEFINITIONS_FILE="/provision/rabbitmq-definitions.json"

# Check if definitions file exists
if [ ! -f "$DEFINITIONS_FILE" ]; then
  echo "❌ Definitions file not found at $DEFINITIONS_FILE"
  exit 1
fi

# Perform POST request to RabbitMQ management API
curl -i -u "$USERNAME:$PASSWORD" \
     -H "Content-Type: application/json" \
     -X POST \
     "$RABBITMQ_HOST/api/definitions" \
     --data-binary "@$DEFINITIONS_FILE"
