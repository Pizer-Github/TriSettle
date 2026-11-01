#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# 00_init_roles.sh
# Runs once on first PostgreSQL container start.
# Creates the restricted app_worker role (runtime role — no BYPASSRLS).
# POSTGRES_USER (app_owner) is already created by the postgres Docker image
# and owns all schema objects / runs Flyway migrations.
# APP_DB_USER (app_worker) is the Spring Boot runtime role.
# ─────────────────────────────────────────────────────────────────────────────
set -e

WORKER_USER="${APP_DB_USER:-app_worker}"
WORKER_PASS="${APP_DB_PASSWORD:-change_me_worker_password}"
DB="${POSTGRES_DB:-invoice_factoring}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$DB" <<-EOSQL
    -- Create the runtime application role (no superuser, no BYPASSRLS)
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${WORKER_USER}') THEN
            CREATE ROLE "${WORKER_USER}" WITH
                LOGIN
                NOSUPERUSER
                NOCREATEDB
                NOCREATEROLE
                NOINHERIT
                PASSWORD '${WORKER_PASS}';
        END IF;
    END
    \$\$;

    -- Grant connect on the database
    GRANT CONNECT ON DATABASE "${DB}" TO "${WORKER_USER}";

    -- Grant usage on public schema (Flyway/app_owner will grant table-level perms via migration)
    GRANT USAGE ON SCHEMA public TO "${WORKER_USER}";
EOSQL

echo "✅  Role '${WORKER_USER}' ready on database '${DB}'"
