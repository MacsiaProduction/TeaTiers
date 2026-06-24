#!/usr/bin/env bash
# Daily Postgres dump for the TeaTiers stack on pelican-node. Re-homes the backup that was lost
# when the Yandex Cloud VM (its off-box pg_dump -> Object Storage timer) was retired (decision #143).
# Run from this directory (loads .env for POSTGRES_USER/DB); wired as a systemd timer -- see README.
# ponytail: local rotated dump only. Off-box copy (rsync to another host / S3) is the upgrade path
# once /resolve- or batch-enrichment-written rows (not reproducible from the committed seed) matter.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a   # POSTGRES_USER, POSTGRES_DB (POSTGRES_PASSWORD unused: local-socket auth)

dir=backups
mkdir -p "$dir"
out="$dir/teatiers-$(date +%Y%m%d-%H%M%S).sql.gz"
tmp="$out.tmp"
trap 'rm -f "$tmp"' EXIT   # a failed pg_dump leaves no half-written .gz behind

docker exec teatiers-db pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" | gzip > "$tmp"
mv "$tmp" "$out"
find "$dir" -name 'teatiers-*.sql.gz' -mtime +14 -delete   # keep ~2 weeks
echo "backup: $out ($(du -h "$out" | cut -f1))"
