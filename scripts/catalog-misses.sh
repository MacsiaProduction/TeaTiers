#!/usr/bin/env bash
# Operator tool for the demand-driven catalog miss log (decisions #116/#117). Reads `catalog_miss` on
# the prod VM over SSH and prints the top unresolved tea queries to curate into the seed. The table is
# no-PII by construction (normalized query string + count + first/last DATE only — no IP, session,
# device, or time-of-day). Read-only: this script never writes.
#
# Usage:
#   scripts/catalog-misses.sh top [N]          # top N misses by demand (default 50), pretty table
#   scripts/catalog-misses.sh since YYYY-MM-DD  # misses last seen on/after a date
#   scripts/catalog-misses.sh csv [N]          # top N as CSV (paste into a spreadsheet)
#
# Env (override the prod defaults):
#   TEATIERS_SSH       ssh target          (default: yc-user@93.77.185.62)
#   TEATIERS_SSH_KEY   identity file       (default: ~/.ssh/teatiers)
#   DB_CONTAINER       postgres container  (default: teatiers-db)
#   POSTGRES_USER / POSTGRES_DB            (default: teatiers / teatiers)
#
# Workflow: see scripts/catalog-curation-runbook.md (classify -> promote into the seed -> redeploy).
set -euo pipefail

ssh_target="${TEATIERS_SSH:-yc-user@93.77.185.62}"
ssh_key="${TEATIERS_SSH_KEY:-$HOME/.ssh/teatiers}"
db_container="${DB_CONTAINER:-teatiers-db}"
db_user="${POSTGRES_USER:-teatiers}"
db_name="${POSTGRES_DB:-teatiers}"

# Runs the SQL on $1 inside the db container, piping it via stdin (so no remote quoting of the query).
# Output is pipe-delimited tuples (psql -tA -F '|').
run_sql() {
  printf '%s\n' "$1" | ssh -i "$ssh_key" -o ConnectTimeout=15 "$ssh_target" \
    "docker exec -i ${db_container} psql -tA -F '|' -U ${db_user} -d ${db_name}"
}

cmd="${1:-top}"
case "$cmd" in
top)
  n="${2:-50}"
  [[ "$n" =~ ^[0-9]+$ ]] || { echo "N must be a number" >&2; exit 2; }
  { echo "rank|query|misses|first_seen|last_seen"
    run_sql "SELECT query_norm, miss_count, first_seen, last_seen FROM catalog_miss ORDER BY miss_count DESC, last_seen DESC LIMIT ${n};" \
      | awk -F'|' 'NF>=4 {printf "%d|%s|%s|%s|%s\n", NR, $1, $2, $3, $4}'
  } | column -t -s'|'
  ;;
since)
  date="${2:?usage: catalog-misses.sh since YYYY-MM-DD}"
  [[ "$date" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "DATE must be YYYY-MM-DD" >&2; exit 2; }
  { echo "query|misses|first_seen|last_seen"
    run_sql "SELECT query_norm, miss_count, first_seen, last_seen FROM catalog_miss WHERE last_seen >= DATE '${date}' ORDER BY miss_count DESC LIMIT 500;"
  } | column -t -s'|'
  ;;
csv)
  n="${2:-100}"
  [[ "$n" =~ ^[0-9]+$ ]] || { echo "N must be a number" >&2; exit 2; }
  echo "query,misses,first_seen,last_seen"
  run_sql "SELECT query_norm, miss_count, first_seen, last_seen FROM catalog_miss ORDER BY miss_count DESC LIMIT ${n};" | sed 's/|/,/g'
  ;;
*)
  echo "usage: $0 {top [N] | since YYYY-MM-DD | csv [N]}" >&2
  exit 2
  ;;
esac
