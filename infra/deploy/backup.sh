#!/usr/bin/env bash
# Daily logical backup of the on-VM Postgres (decision: VM Postgres backup).
# Dumps the catalog DB out of the teatiers-db container, gzips it under BACKUP_DIR, and prunes
# dumps older than RETENTION_DAYS. Off-box copy to Yandex Object Storage is opt-in (BACKUP_S3_URI):
# the catalog is reproducible from Flyway + the committed seed, so a same-disk dump is an adequate
# default for a single-operator hobby deploy; flip on S3 for true off-box durability.
#
# Install on the VM with the systemd timer in this folder (see infra/README.md "Backups").
set -euo pipefail

ENV_FILE="${ENV_FILE:-/opt/teatiers/.env}"
BACKUP_DIR="${BACKUP_DIR:-/opt/teatiers/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
DB_CONTAINER="${DB_CONTAINER:-teatiers-db}"

# POSTGRES_DB / POSTGRES_USER come from the deploy .env (same file the stack uses).
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a
DB="${POSTGRES_DB:-teatiers}"
USER="${POSTGRES_USER:-teatiers}"

mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%d-%H%M%S)"
out="$BACKUP_DIR/teatiers-${stamp}.sql.gz"

# pg_dump runs inside the container; nothing is published off the compose network.
docker exec "$DB_CONTAINER" pg_dump --no-owner --no-privileges -U "$USER" "$DB" | gzip -c > "$out"
echo "wrote $out ($(du -h "$out" | cut -f1))"

# Optional off-box copy. Needs the AWS CLI + AWS_* creds in the environment (e.g. a backup SA's
# static key); leave BACKUP_S3_URI unset to keep dumps local-only.
if [ -n "${BACKUP_S3_URI:-}" ]; then
  # Pin the region: on a Yandex VM the AWS CLI otherwise derives a malformed 'ru-central1-' from
  # instance metadata and aborts. Object Storage's region is ru-central1.
  aws --endpoint-url "${AWS_ENDPOINT_URL:-https://storage.yandexcloud.net}" \
    --region "${AWS_REGION:-ru-central1}" \
    s3 cp "$out" "${BACKUP_S3_URI%/}/$(basename "$out")"
  echo "uploaded to ${BACKUP_S3_URI%/}/$(basename "$out")"
fi

# Prune local dumps past the retention window.
find "$BACKUP_DIR" -name 'teatiers-*.sql.gz' -type f -mtime "+${RETENTION_DAYS}" -delete
echo "pruned dumps older than ${RETENTION_DAYS}d"
