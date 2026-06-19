# Catalog curation runbook — seed-from-misses

The MVP catalog grows by a **weekly demand-driven loop** (decisions #116/#117): real `/resolve` misses
are logged to `catalog_miss` (no-PII), the operator promotes the most-wanted real teas into the curated
seed, and a redeploy ships them. This is the *only* compliant growth loop while crawling is banned (#45)
and the AI tier is off (#88/#100). Budget ~30 min/week.

## 1. Review the demand

```bash
scripts/catalog-misses.sh top 50          # top 50 unresolved queries by demand
scripts/catalog-misses.sh since 2026-06-19 # only what's new since a date
scripts/catalog-misses.sh csv 100 > misses.csv
```

Each row is `query` · `misses` (how many people asked) · `first_seen` · `last_seen`. The table holds
**only** the normalized query string + counts + dates — no IP, session, device, user, or time-of-day.

## 2. Classify each top miss

For each query from the top, decide:

- **Real tea, worth adding** → promote (step 3). Prioritize high `miss_count` and recent `last_seen`.
- **Already resolvable via Wikidata** → skip; the free `/resolve` tier (#115) already covers it (it
  only logged because it isn't in the `P31/P279* Q6097` subtree with an exact label — fine, the user
  still got a result on a fuzzier query). Spot-check with a resolve call before adding a duplicate.
- **Typo / variant of an existing tea** → the pg_trgm fuzzy search (#79) should already catch it; verify
  it does. If not, add an **alias name** to the existing seed row rather than a new tea.
- **Spam / gibberish / a brand SKU we can't license** → drop (ignore). Don't seed un-redistributable
  vendor blends or anything without a clean provenance/license (see step 4).

## 3. Promote into the seed

Edit `server/src/main/resources/seed/catalog-seed.json` — append a tea object matching the existing
schema (names per locale, type, origin, descriptions with `source`/`license`, flavor profile, and
**provenance**: `source`, `source_url`, `license`, `retrieved_at`). The seed loader is **idempotent**
(`CatalogSeeder`, dedup by normalized key), so re-adding an existing tea is a no-op.

- Prefer the **Wikidata (CC0) / Wikipedia (CC-BY-SA, attributed) / OFF taxonomy (ODbL, isolated)** open
  core for facts; own-authored short blurbs keep the card clean.
- Bump the seed `version` if your tooling/tests key off it.

## 4. Verify provenance / license (mandatory)

Every row needs real provenance. Do **not** copy descriptions from commercial/community sites
(Steepster, RateTea, TeaDB, Moychay, Baidu, …) — that's the locked no-mirror rule (#45). Wikipedia text
is CC-BY-SA → attribute + link; the short blurb should be original. Images stay CC/Wikimedia or absent.

## 5. Ship

Open a PR with the seed change (server code path → CI), merge, let `publish-image.yml` rebuild, then
redeploy the server (`docker compose pull server && up -d server`) — the seed upsert runs on startup.
Re-verify a previously-missing query now resolves.

## 6. Hygiene

- `catalog_miss` currently keeps rows **forever** (no retention). That's fine at launch volume; revisit a
  decay/retention rule once there's real traffic (decision #116 left this explicit-but-open).
- Keep the loop **weekly** so the feedback signal doesn't decay — stale demand data curates the wrong teas.
