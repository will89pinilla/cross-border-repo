#!/bin/sh
# Creates the database roles of spec §9. Runs once, on an empty data directory, as the
# PostgreSQL superuser. Used by Docker Compose and by Testcontainers (same file).
#   accounts_owner: owns the schema; Flyway migrates with it.
#   accounts_app:   the application; table privileges are granted in V1 (D13).
set -eu

: "${ACCOUNTS_OWNER_PASSWORD:?ACCOUNTS_OWNER_PASSWORD is required}"
: "${ACCOUNTS_APP_PASSWORD:?ACCOUNTS_APP_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v db="$POSTGRES_DB" \
     -v owner_pw="$ACCOUNTS_OWNER_PASSWORD" \
     -v app_pw="$ACCOUNTS_APP_PASSWORD" <<'SQL'
CREATE ROLE accounts_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE accounts_app LOGIN PASSWORD :'app_pw';

-- The owner of the database also owns schema public (pg_database_owner, PostgreSQL 15+).
ALTER DATABASE :"db" OWNER TO accounts_owner;
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO accounts_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
