#!/usr/bin/env bash
# Run once on an existing PostgreSQL deployment, with Docker access.
# Keeps the old database and a private logical backup; refuses a populated target.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
docker compose up -d timescaledb
for attempt in $(seq 1 60); do
  if docker compose exec -T timescaledb pg_isready -h 127.0.0.1 -U meter -d meter >/dev/null 2>&1; then break; fi
  sleep 1
done
existing=$(docker compose exec -T timescaledb psql -U meter -d meter -Atc "SELECT to_regclass('public.requests') IS NOT NULL")
if [ "$existing" != "f" ]; then
  echo "Target already has a requests table; refusing to overwrite it."
  exit 1
fi
docker compose stop app
mkdir -p backups
backup="backups/pre-timescale-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose exec -T postgres pg_dump -U meter -d meter --format=custom --no-owner --no-acl > "$backup"
docker compose exec -T timescaledb pg_restore -U meter -d meter --no-owner --no-acl --exit-on-error < "$backup"
before=$(docker compose exec -T postgres psql -U meter -d meter -Atc 'SELECT count(*) FROM requests')
after=$(docker compose exec -T timescaledb psql -U meter -d meter -Atc 'SELECT count(*) FROM requests')
if [ "$before" != "$after" ]; then
  echo "Request count differs after restore; app remains stopped. Backup: $backup"
  exit 1
fi
echo "Restored $after historical request records. Backup: $backup"
docker compose up -d --build app mock prometheus
echo "Check application readiness and hypertables before stopping the legacy postgres service."
