#!/bin/sh

# Define the server endpoint
SERVER_URL="http://tcalendar-side-service.local:8000"

# Define a list of users (email,firstname,lastname)
USERS="bob@linagora.com,Bob,Smith
alice@linagora.com,Alice,Johnson
cedric@linagora.com,Cedric,Nguyen,
admin@linagora.com,Admin,Admin"

printf "%s\n" "$USERS" | while IFS=',' read email firstname lastname; do
  echo "Registering user: $email"

  curl -k -X POST "$SERVER_URL/registeredUsers" \
    -H "Content-Type: application/json" \
    -d "{
      \"email\": \"$email\",
      \"firstname\": \"$firstname\",
      \"lastname\": \"$lastname\"
    }"

  echo ""

  curl -k -X PUT "$SERVER_URL/users/$email" \
    -H "Content-Type: application/json" \
    -d "{
      \"password\": \"secret\"
    }"

  echo ""
done
