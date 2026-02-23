#!/bin/sh
set -e

# NOTE:
# mongo-c-driver SCRAM-SHA-256 uses a 28-byte salt, while PostgreSQL uses a 16-byte salt.
# To keep compatibility with MongoDB clients in esn-sabre,
# we manually patch `pg_authid.rolpassword` with a precomputed SCRAM-SHA-256 hash
PG_HOST="postgres"
PG_PORT="5432"
PG_USER="username"
PG_PASSWORD="password"
PG_DATABASE="postgres"
NEW_ROL_PASSWORD="SCRAM-SHA-256\$4096:dm4MIMl6+sUG6p/eeOGsYEYFs1y7UcbrLnbBOg==\$cRTjw3T3qpNaLceUY9UVoUC87LJhcIf/mLaNS0sI/qo=:8Fc3Chlq4vXgDzG2xnBA4ds0gnAt6KLBKd8yFeHRLBE="

export PGPASSWORD="${PG_PASSWORD}"

echo "[pg-authid-patch] Waiting for postgres..."
until pg_isready -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USER}"; do
  sleep 2
done

echo "[pg-authid-patch] Running SQL patch..."

psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USER}" -d "${PG_DATABASE}" <<EOF
UPDATE pg_authid
SET rolpassword = '${NEW_ROL_PASSWORD}'
WHERE rolname = 'username';
EOF

echo "[pg-authid-patch] Done."