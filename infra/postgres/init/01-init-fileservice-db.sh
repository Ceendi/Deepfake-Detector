#!/bin/bash
set -e
# Runs ONCE on an empty pgdata, as the superuser POSTGRES_USER. Creates the file-service's
# own database + own least-privilege user (database-per-service). The user owns its database,
# so it has full rights on its tables and flyway_schema_history but no access to the
# orchestrator's database. Password is interpolated into '...' — keep it apostrophe-free.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER ${FILE_SERVICE_DB_USER} WITH PASSWORD '${FILE_SERVICE_DB_PASSWORD}';
    CREATE DATABASE ${FILE_SERVICE_DB_NAME} OWNER ${FILE_SERVICE_DB_USER};
EOSQL
