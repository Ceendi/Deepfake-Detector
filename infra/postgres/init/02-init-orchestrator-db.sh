#!/bin/bash
set -euo pipefail

# The same transaction provisions fresh volumes and upgrades an existing application database.
: "${POSTGRES_USER:?required}" "${POSTGRES_DB:?required}"
: "${ORCHESTRATOR_DB_USER:?required}" "${ORCHESTRATOR_DB_PASSWORD:?required}"
if [[ "$ORCHESTRATOR_DB_USER" == "$POSTGRES_USER" ||
      "$ORCHESTRATOR_DB_USER" == "${FILE_SERVICE_DB_USER:-fileservice}" ||
      "$POSTGRES_DB" == "postgres" || "$POSTGRES_DB" == template* ||
      "$POSTGRES_DB" == "${FILE_SERVICE_DB_NAME:-fileservice}" ]]; then
    echo 'Refusing overlapping application/admin roles or a non-application database' >&2
    exit 1
fi
export ORCHESTRATOR_DB_USER ORCHESTRATOR_DB_PASSWORD POSTGRES_USER POSTGRES_DB
psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_user ORCHESTRATOR_DB_USER
\getenv app_password ORCHESTRATOR_DB_PASSWORD
\getenv admin_user POSTGRES_USER
\getenv app_db POSTGRES_DB
BEGIN;
SET LOCAL lock_timeout = '5s';
SELECT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user' AND
    (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    OR EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles r ON m.member = r.oid
               WHERE r.rolname = :'app_user') AS unsafe_role \gset
\if :unsafe_role
    \echo 'Refusing to repurpose a privileged role or a role with memberships'
    \quit 1
\endif
SELECT format('CREATE ROLE %I', :'app_user')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user') \gexec
ALTER ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD :'app_password';
ALTER DATABASE :"app_db" OWNER TO :"app_user";
ALTER SCHEMA public OWNER TO :"app_user";
-- REASSIGN OWNED would also transfer shared objects, including other databases.
SELECT format('ALTER %s %I.%I OWNER TO %I',
    CASE c.relkind WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW'
                   WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END,
    n.nspname, c.relname, :'app_user')
FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid
JOIN pg_roles r ON c.relowner = r.oid
WHERE n.nspname = 'public' AND r.rolname = :'admin_user'
    AND c.relkind IN ('r', 'p', 'S', 'v', 'm')
ORDER BY CASE WHEN c.relkind IN ('r', 'p') THEN 0 ELSE 1 END \gexec
COMMIT;
SQL
