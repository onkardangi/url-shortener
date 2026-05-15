#!/bin/bash
set -e

echo "Creating replication user..."

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER replicator WITH REPLICATION ENCRYPTED PASSWORD 'replicator_password';
EOSQL

# Add rule and reload in one step
echo "host replication replicator all md5" >> "$PGDATA/pg_hba.conf"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    SELECT pg_reload_conf();
EOSQL

echo "Done — replication user created, pg_hba.conf updated and reloaded"