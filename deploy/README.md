# deploy/ — TeaTiers production deployment

`tea.macsia.fun`, live on **pelican-node** (RU manager host), Docker-Compose. This `deploy/` folder is
the single source of truth for the stack (it replaced the old `~/vpn/git/stacks/teatiers/` run-dir and
the deleted OpenTofu `infra/`). Migrated off the Yandex Cloud VM on `2026-06-24` — the VM, its static IP,
and the whole OpenTofu stack were deleted (the app co-locates with the VPN control plane).

**Image delivery is self-service for now.** GitHub Actions billing is exhausted, so the
`publish-image.yml` / `ocr-sidecar.yml` ghcr build+push workflows don't run and ghcr can't be pushed by
hand (no token has `write:packages`). The server image is **built natively on the host** via
`./deploy.sh` (see below). Restore the ghcr flow — and switch back to `docker compose pull` — once CI
billing returns or a `write:packages` PAT exists.

## Topology

```
client ──HTTPS──► edge Caddy (host /opt/edge, TLS-ALPN-01 on :443)
                    └─reverse-proxy──► teatiers-server:8080   (shared external `proxy` net)
                                          ├── db  (postgres, internal `data` net, no internet)
                                          └── ocr-sidecar (internal `ocr` net, no internet/no db)
```

- **No bundled Caddy, no published 80/443 here** — the shared edge Caddy terminates TLS for both
  `tea.macsia.fun` and `komodo.macsia.fun` and reaches `teatiers-server:8080` over the external
  `proxy` Docker network. `:80` on the host is deliberately left free for `certbot standalone`.
- DNS: `tea.macsia.fun` → pelican-node (Cloudflare A record).

## Files

| File | Role |
|------|------|
| `compose.yaml` | the stack (server + db + ocr-sidecar), committed. Top-level `name: teatiers` pins the Compose project. |
| `deploy.sh` | self-service build-on-host + reconcile + health-gate, committed |
| `.env` | real values incl. `POSTGRES_PASSWORD` + `SERVER_IMAGE` — **host-local, gitignored, `chmod 600`** |

## How it runs

The stack is Compose **project `teatiers`** (pinned by `compose.yaml`'s top-level `name:`, so a manual
`docker compose` from this folder reconciles the real stack instead of orphaning it as project `deploy`).
The host checkout at `/home/macsia/vpn/git/TeaTiers` is refreshed by `pull-git.timer` → `git pull --ff-only`.

**Deploy (self-service, while CI is down)** — from the host:

```bash
ssh macsia_node
cd ~/vpn/git/TeaTiers && git pull --ff-only && deploy/deploy.sh
```

`deploy.sh` builds the server image natively, pins `SERVER_IMAGE` in `.env` to the `:<sha12>` tag
(so a stray `docker compose pull` fails loud rather than silently reverting to the stale ghcr `:latest`),
runs `docker compose up -d` (recreates only the changed service), and waits for health. **db + its
`teatiers_teatiers_pgdata` volume are untouched** by a server-only deploy.

Rollback: ghcr `:latest` still holds the last CI-built image, so `SERVER_IMAGE=…:latest` +
`docker compose pull && docker compose up -d` reverts. Once CI billing / a `write:packages` PAT returns,
push the built tag to ghcr and go back to a pull-based deploy.

## Verify

```bash
curl -fsS https://tea.macsia.fun/actuator/health   # {"status":"UP"}
```

## Scrape upload channel (other-machine drop)

Secure one-way pipe so the **other (free-AI) machine** pushes scrape output to
pelican-node for import (the scraper/importer wiring is future work, TeaTiers #137 —
this is only the upload pipe + contract).

- **Receiving side (pelican-node):** isolated drop user `teascrape` (uid 1003,
  password-locked). `~teascrape/.ssh/authorized_keys` is locked to
  `restrict,command="/usr/bin/rrsync -wo /home/teascrape/drop" <pubkey>` — every
  session is confined to **write-only rsync** into `/home/teascrape/drop/` (no pty,
  no read-back, no `..` escape; all verified). SSH endpoint: `node.macsia.fun:47894`.
- **Upload (from the scraper machine):**

```bash
RSH="ssh -p 47894 -i ~/.ssh/teascrape_ed25519"
# local layout: ./out/<YYYY-MM-DD>/<runId>.ndjson
rsync -a --rsh="$RSH" ./out/ teascrape@node.macsia.fun:
```

- **Data contract** — `SourceObservation` NDJSON, one observation per line, facts-only
  (TeaTiers #136/#141): no prose/description/image/review, no `source`, no
  `verificationStatus` (the importer sets `source='scrape'` + non-verified itself).
  Unknown `type` / non-ISO `originCountry` are **rejected**, not coerced; `evidence`
  (proof of fetch) and a fresh `RobotsEvidence` (`decision="allow"`, 2xx) are mandatory.
  Fields: `server/.../dto/ScrapeImportDtos.kt` (`SourceObservation`, `ScrapedFacts`,
  `ScrapedName`, `FetchEvidence`).
- **Import (future, #137):** the importer stages `source_record` + review candidates
  (never writes canonical teas), then the operator drives the two-phase apply via the
  `review-cli` profile (`pending` → `close-ingestion` → `mark-reviewed` → `apply-run`).
  Until #137 lands, uploads accumulate in `/home/teascrape/drop/`.

The channel keypair was generated at `~/vpn/secrets/teascrape_ed25519{,.pub}`; deploy
the private half to the scraper box and `shred` it on pelican (pubkey stays in
`authorized_keys`).

## Backups

`backup.sh` re-homes the DB dump the retired VM used to push off-box to Yandex Object Storage (deleted
with the OpenTofu stack, #143). It runs `pg_dump` against the `teatiers-db` container, gzips to
`./backups/`, and rotates to ~14 days. The catalog is reproducible from the committed seed, but
`/resolve`- and batch-enrichment-written rows are **not** — so this matters once enrichment lands.

Install on the host once (run-dir = this folder), via a systemd timer:

```ini
# /etc/systemd/system/teatiers-backup.service
[Unit]
Description=TeaTiers Postgres dump
[Service]
Type=oneshot
WorkingDirectory=/home/macsia/vpn/git/TeaTiers/deploy
ExecStart=/home/macsia/vpn/git/TeaTiers/deploy/backup.sh
```

```ini
# /etc/systemd/system/teatiers-backup.timer
[Unit]
Description=Daily TeaTiers Postgres dump
[Timer]
OnCalendar=daily
Persistent=true
[Install]
WantedBy=timers.target
```

```bash
chmod +x backup.sh
sudo systemctl daemon-reload && sudo systemctl enable --now teatiers-backup.timer
./backup.sh   # one-shot smoke test
```

> **ponytail:** local same-disk dump only — guards against a bad migration / accidental drop / logical
> corruption, **not** disk loss. Off-box copy (rsync to another host, or S3) is the upgrade path; wire it
> when the enriched catalog is load-bearing.
