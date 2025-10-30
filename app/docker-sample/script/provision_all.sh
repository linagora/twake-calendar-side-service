#!/bin/sh

# Wait for Twake Calendar service to be up
echo "Waiting for twake-calendar-side-service to be available..."

while true; do
  status=$(curl -s -o /dev/null -w "%{http_code}" http://tcalendar-side-service.linagora.local:8000/healthcheck)
  if [ "$status" -eq 200 ]; then
    echo "twake-calendar-side-service is up!"
    break
  fi
  echo "Still waiting... (HTTP status: $status)"
  sleep 2
done

# Run user provisioning
echo "Provisioning users..."
sh /provision/provision_users.sh
echo "Provisioning resources..."
sh /provision/provision_resources.sh
