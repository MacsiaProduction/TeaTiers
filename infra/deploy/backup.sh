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
tmp="${out}.partial"
# Remove a partial dump on any failure (set -e/pipefail aborts the script mid-pipe). After a
# successful mv, $tmp no longer exists, so this EXIT trap is a harmless no-op.
trap 'rm -f "$tmp"' EXIT

# pg_dump runs inside the container; nothing is published off the compose network. Write to a
# .partial first and rename only on success (mv is atomic on the same filesystem), so a crashed or
# truncated dump never leaves a corrupt .sql.gz that a restore might later pick up.
docker exec "$DB_CONTAINER" pg_dump --no-owner --no-privileges -U "$USER" "$DB" | gzip -c > "$tmp"
mv "$tmp" "$out"
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

# Prune local dumps past the retention window, but ALWAYS keep the newest 3 completed dumps regardless of
# age (OPS-P2-3): if backups stop for longer than RETENTION_DAYS while the VM lives, a blind age-prune would
# reap the last good dump. `ls -1t` lists completed dumps newest-first (backup names never contain spaces);
# `tail -n +4` skips the newest 3, and each older one is age-pruned. `|| true` so a no-dumps glob-miss under
# `set -o pipefail` can't abort the script.
ls -1t "$BACKUP_DIR"/teatiers-*.sql.gz 2>/dev/null | tail -n +4 | while IFS= read -r f; do
  find "$f" -mtime "+${RETENTION_DAYS}" -delete
done || true
# Stale `.partial` files (a hard kill / power loss skips the EXIT trap) are junk with no keep-floor —
# reaped purely by age so orphaned partials can't accumulate forever.
find "$BACKUP_DIR" -name 'teatiers-*.sql.gz.partial' -type f -mtime "+${RETENTION_DAYS}" -delete
echo "pruned dumps older than ${RETENTION_DAYS}d (keeping the newest 3)"
