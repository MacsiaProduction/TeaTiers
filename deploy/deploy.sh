#!/usr/bin/env bash
# Self-service deploy for the TeaTiers stack (Compose project `teatiers`), run on pelican-node from
# this directory. While GitHub Actions billing is unavailable the CI publish-image -> ghcr -> pull flow
# is dead (#144 follow-up), so this builds the server image NATIVELY on the host (it's a 31 GiB / 4 vCPU
# box with buildx -- fine) and reconciles the running stack in place. `git pull --ff-only` FIRST:
#   ssh macsia_node 'cd ~/vpn/git/TeaTiers && git pull --ff-only && deploy/deploy.sh'
# ponytail: local build + in-place reconcile. When ghcr push is restored (a write:packages PAT, or CI
# billing returns) push the built tag and switch back to `docker compose pull` for registry-as-truth.
set -euo pipefail
cd "$(dirname "$0")"

repo=ghcr.io/macsiaproduction/teatiers-server
sha12=$(git rev-parse --short=12 HEAD)
img="${repo}:${sha12}"

echo ">> building ${img} on $(uname -m)"
docker build -t "$img" -t "${repo}:latest" ../server

# Pin SERVER_IMAGE to the freshly-built local tag so a stray `docker compose pull` fails loud (the tag
# is absent from ghcr) instead of silently reverting to the stale ghcr :latest.
sed -i "s#^SERVER_IMAGE=.*#SERVER_IMAGE=${img}#" .env
echo ">> $(grep '^SERVER_IMAGE=' .env)"

echo ">> reconciling (project name pinned to 'teatiers' by compose.yaml; recreates only changed services)"
docker compose up -d

echo ">> waiting for server health"
for _ in $(seq 1 24); do
    if docker exec teatiers-server curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
        set -a; . ./.env; set +a
        ver=$(docker exec teatiers-db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
            -tAc 'select max(version::numeric) from flyway_schema_history' | tr -d ' ')
        echo ">> healthy; Flyway at v${ver}"
        exit 0
    fi
    sleep 5
done
echo "!! server not healthy after 120s -- docker compose -p teatiers logs --tail=50 server" >&2
exit 1
