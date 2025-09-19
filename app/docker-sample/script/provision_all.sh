#!/bin/sh

# Wait for RabbitMQ to be healthy
echo "Waiting for RabbitMQ to be healthy..."
until nc -z rabbitmq 5672; do
  sleep 2
done

# Run RabbitMQ queue provisioning
echo "Provisioning RabbitMQ queues..."
sh /provision/import-rabbitmq-definitions.sh

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
