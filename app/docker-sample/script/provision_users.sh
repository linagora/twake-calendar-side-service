#!/bin/sh

# Define the server endpoint
SERVER_URL="http://tcalendar-side-service.linagora.local:8000"

# Define a list of users (email,firstname,lastname)
USERS="bob@linagora.local,Bob,Smith,
alice@linagora.local,Alice,Johnson
cedric@linagora.local,Cedric,Nguyen"

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
