#!/bin/bash
set -e

# Wait for primary to be ready
until pg_isready -h postgres -U replicator; do
  echo "Waiting for primary..."
  sleep 2
done

# Only initialize if data directory is empty
if [ -z "$(ls -A /var/lib/postgresql/data)" ]; then
  echo "Cloning primary with pg_basebackup..."

  pg_basebackup \
    -h postgres \
    -U replicator \
    -D /var/lib/postgresql/data \
    -P \
    -Xs \
    -R   # -R writes recovery config automatically

  echo "Base backup complete"
fi

# Start Postgres
exec docker-entrypoint.sh postgres \
  -c hot_standby=on \
  -c hot_standby_feedback=on