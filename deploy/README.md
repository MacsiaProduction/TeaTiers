# deploy/ — TeaTiers production deployment

`tea.macsia.fun`, live on **pelican-node** (RU manager host), Docker-Compose, **Komodo-managed**.
Migrated off the Yandex Cloud VM on `2026-06-24` — the VM, its static IP, and the whole OpenTofu
stack were deleted (the app is no longer VPN-free / single-VM; it co-locates with the VPN control
plane). Image delivery is unchanged: `ghcr.io` via the `publish-image.yml` + `ocr-sidecar.yml`
workflows.

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
| `compose.yaml` | the stack (server + db + ocr-sidecar), committed |
| `.env.example` | non-secret template, committed |
| `.env` | real values incl. `POSTGRES_PASSWORD` — **host-local, gitignored, `chmod 600`** |

## How it runs (Komodo)

Komodo stack **`teatiers`** is a *files-on-host* stack whose run-directory is this folder in the
host checkout: `/home/macsia/vpn/git/TeaTiers/deploy`. The checkout is refreshed daily, **read-only**
(`pull-git.timer` → `git pull --ff-only`; Komodo does **not** auto-deploy — redeploys are manual via
the Komodo UI). Komodo loads `.env` from this directory automatically.

Manual equivalent (from this directory on the host):

```bash
docker compose pull && docker compose up -d
docker compose ps
```

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

## Known gap

DB backups are **not yet re-homed**. The retired VM had an off-box `pg_dump` → Yandex Object Storage
timer (deleted with the OpenTofu stack). The catalog is mostly reproducible from the committed seed,
but `/resolve` writes rows that are not — add a pelican-side dump if that data becomes load-bearing.
