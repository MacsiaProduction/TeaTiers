# Rating — 21-catalog-scraping-foundation

Prompt: ./prompt.md   ·   Date judged: 2026-06-21

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus     |    5     |   5   |       5       |    2     |    5    |  4.30 |  1   |
| gpt      |    5     |   5   |       5       |    1     |    4    |  4.30 |  2   |
| deepseek |    4     |   3   |       4       |    2     |    4    |  3.20 |  3   |
| alice    |    3     |   4   |       3       |    3     |    4    |  2.70 |  4   |
| gemini   |    2     |   4   |       3       |    3     |    4    |  2.35 |  5   |
| qwen     |    2     |   4   |       2       |    4     |    3    |  1.90 |  6   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**This run hinges on one separation and two "do-NOT-auto-merge" lines.** The whole point of run 21 was that
#131's import machinery was unbuildable; the buildable correction is to **separate three keys that #131
conflated**: (1) **source-record identity** = `(source, external_id / canonical_url)`, which drives re-import
**idempotency**; (2) **canonical-entity identity** = the cross-script resolution of `Да Хун Пао` / `Da Hong
Pao` / `大红袍` into one tea, which is a **human-confirmed** decision in the pilot; (3) **per-field
provenance** = which source gave each fact, which the single `tea.source` column cannot carry. Two models
nailed all three (**opus, gpt**); the rest each re-introduced the exact anti-pattern the code review flagged.
Grounding facts the judge re-verified against live HEAD: `tea.source` CHECK is at `V1__catalog_schema.sql:28`
(`'wikidata','curated','ai','user'` — physically rejects `'scrape'`); `name_norm = lower(f_unaccent(name))`
with the pg_trgm GIN is at `V4__name_norm_trgm.sql`; the server has **no upsert anywhere** (`CatalogSeeder` is
insert-or-skip `:38`, `WikidataUpsertService` is create-or-get); the prod/test DB is **`postgres:16-alpine`**
(`AbstractIntegrationTest` + the V4 collation note) — so **PostgreSQL-18-only `uuidv7()` does not exist on this
box**; the next free Flyway integer is **V7**; the API returns the raw `BIGINT` `tea.id` in `TeaSummaryDto.id`
/ `TeaDetailDto.id`, and the APK caches it as `catalogTeaId`. The legal line is correct across the top three
(facts not copyrightable; RU ГК РФ Art. 1334 operative for a RU-on-RU scrape, **not** the EU Directive; ToS is
the enforceable lever per *Ryanair v PR Aviation* C‑30/14; goal = a clean public APK, not zero risk).

**Winner: opus** — the cleanest **foundation-first** plan and the one whose every schema/identity claim is
grounded in the real model. It is build-ready in the exact order this repo ships: PR1 = `tea.source += 'scrape'`
CHECK + `public_id`/lifecycle + legacy-id map (the smallest correct unblock), then gated staging, then the
explicit importer, then identity, then the operator review CLI. The three-keys separation is explicit and
correct; **no fuzzy/transliteration match ever auto-merges in the pilot** — Tier 0 authoritative (curated +
human-confirmed aliases) establishes identity, Tier 1–3 (exact-norm, pg_trgm, pypinyin/Palladius candidates)
only **enqueue for review**. It is the one model that corrected #131's stdlib-robotparser assumption with a
real reason (`urllib.robotparser` is first-match-wins and not RFC-9309 longest-match/wildcard aware → **adopt
Protego from the pilot**), pinned a verified-live stack (`httpx 0.28.1`, `selectolax 0.4.10` Lexbor backend,
`Protego 0.6.1`, `pypinyin 0.55.0`) with the LGPL-Modest-engine caveat and a hashed-lock requirement, and was
**honest about what it could not verify** (artoftea.ru robots/ToS not machine-readable in recon → run-time-only,
per-run robots re-check). Per-field provenance is a single clean `tea_field_provenance` table; the public id is
`public_id UUID UNIQUE` with `tea_legacy_id_map` for the shipped APK and `status ∈ active/retracted/merged` +
`merged_into_public_id` for soft-rollback. Demerits keeping it off a perfect halluc: it claims a "live recon of
artoftea.ru" (OpenGraph "confirmed", clean OpenCart URLs) it then admits it could not fully fetch — the recon
confidence slightly overshoots its own caveat; and the Flyway integers are placeholders (correctly flagged).

**gpt (co-winner, #2)** — the **most rigorous** answer and the lowest-fabrication (halluc 1: no invented pins,
no fabricated source counts, every claim cited or hedged). It alone surfaced three load-bearing correctness
points opus left implicit: (a) the **brand/vendor non-collapse rule** — "Vendor X Da Hong Pao" must **not**
auto-merge into a generic "Da Hong Pao"; route to review unless a reviewer attaches it as an alias — which
prevents both over-merging vendor lots and silently minting a canonical tea per pack-size/grade; (b) the
**active-assignment** provenance model (`tea_field_observation` many → `tea_field_assignment` with a
`WHERE status='active'` partial-unique index per `(tea_id, field_path)`), a richer representation of "one tea
assembled from many sources" than a flat provenance row, that also gives **soft-rollback for a bad field
import** for free; (c) **deterministic snapshot restore** via `OVERRIDING SYSTEM VALUE` + sequence reset so a
DB rebuild reproduces ids — the concrete mechanism opus asserts but doesn't show. Its `tea.source` handling
(treat as legacy row-origin summary, add `'scrape','mixed'`, real truth in observations/assignments) and its
additive alias `ON CONFLICT (tea_id, locale, name_norm) DO UPDATE` are the most correct upsert sketches in the
run. It loses #1 only on being **more "design" than "go"** (9 PRs, two-table observation/assignment the pilot
may not need yet) and recommending **stdlib robotparser for the pilot** where opus's Protego-from-day-one is the
better call; clarity 4 (very long, the recommendation buried under dense DDL).

**Reuse** — into the build (the locked design synthesizes opus's structure + gpt's rigor):
- **opus:** the **PR1→PR6 foundation-first sequencing**; the `ingest_*` staging namespace kept separate from
  public `tea*`; `tea_field_provenance` (the simpler per-field table the pilot will actually use); `public_id
  UUID` + `tea_legacy_id_map` + `status ∈ active/retracted/merged` + `merged_into_public_id`; **Protego from
  the pilot** (the RFC-9309 reason); the verified pin set + hashed-lock + LGPL-Modest caveat; the importer DTO
  with **no `source`/`verification_status` defaults, rejecting `'verified'`**; per-run robots re-check + ToS
  owner-sign-off as hard preflights; "no auto-merge in the pilot, calibrate thresholds later on a labeled
  corpus."
- **gpt:** the **three-keys separation** stated cleanly; the **brand/vendor non-collapse** rule; the
  **shared-normalizer** invariant (the scraped-name normalizer MUST replicate `lower + f_unaccent` or pg_trgm
  silently misses); `tea.source += 'mixed'` for multi-source rows; the **additive alias upsert** on
  `(tea_id, locale, name_norm)`; `OVERRIDING SYSTEM VALUE` + sequence-reset for deterministic restore; the
  tombstone API behavior (active → detail, merged → survivor with `supersededBy`, retracted → compact tombstone
  not 404); the NDJSON import-bundle artifact layout; the `raw_facts` JSON-schema guard (reject
  `description|review|body|html|image|photo` keys, long strings).
- **deepseek:** the clean `scrape_raw` (immutable) → `scrape_parsed` split keyed `(source, external_id)`; the
  "curated is the trusted baseline; scrape supplements, never overrides curated fields without review" priority;
  short-retention (≤24h) raw-prose rule; the honest pin set.
- **alice:** the **demand-driven operating-model framing** (one-off local import driven by `catalog_miss`, not a
  daemon, off the API path / prod VM); `source_gate` as a standalone per-source registry table.
- **gemini:** the **per-run `robots.txt` preflight with fail-closed** code shape; the Kotlin **miss-log
  `InputSanitizer`** (opaque search term only — never a URL/shell/SQL arg); CI seed-content guard concept.
- **qwen:** the explicit articulation that exposing the internal sequence id is itself an operational/security
  risk (motivates `public_id`) — useful framing, even though its mechanism was wrong here.

**Discard** — keep OUT of the build:
- **Auto-merge on a similarity threshold (gemini ≥0.75, alice Lev>0.85 / trigram>0.7, qwen >0.8, deepseek's
  silent exact-auto-create) — REFUTED for the pilot.** The review's whole point: in the pilot **nothing
  auto-merges or auto-creates** a canonical tea; every cross-script/fuzzy candidate is review-only, thresholds
  calibrated later on a labeled corpus. Numeric thresholds quoted before a fixture set are guesses.
- **`ON CONFLICT (dedup_key) DO UPDATE` as the canonical upsert (gemini) — REFUTED.** Idempotency is keyed on
  the **source record** `(source, external_id/canonical_url)`, not on the canonical key; keying the upsert on
  `dedup_key` is exactly the #131 bug (corrections/new-source aliases silently no-op). `dedup_key` stays a weak
  internal search hint.
- **`uuidv7()` / `uuid_generate_v7()` as a column default (qwen, gemini) — UNBUILDABLE on this box.** The DB is
  **`postgres:16-alpine`**; native `uuidv7()` is PostgreSQL **18**-only and `uuid_generate_v7()` is not a real
  function. Use `gen_random_uuid()` (pgcrypto/`uuid-ossp`, present) for `public_id`, or generate the UUID app-
  side; **do not** size index strategy on a UUIDv7 assumption.
- **Removing `tea.verification_status` (qwen) — wrong.** It is load-bearing for the existing enrichment lifecycle
  (`EnrichmentStubService`, V2). The importer must set a **non-verified** status and reject `'verified'`, not
  delete the column.
- **Running the scraper as a scheduled job on the prod Yandex VM (qwen) — violates the fixed boundary.** Scraping
  is a **local one-off**, off the API path and off prod (opus/gpt/alice/deepseek all agree).
- **Stale/older pins:** gemini `httpx 0.27.0` / `Protego 0.3.1` / `pypinyin 0.51.0`, deepseek `selectolax 0.4.9`,
  qwen `httpx 0.14.3` — all behind current stable; alice's `palladizator` and `cyrtranslit` are real-ish but the
  Palladius↔pinyin bridge is a **curated repo table**, not a maintained library (opus/gpt/deepseek correct).
- **Persisting raw vendor prose / `raw_html` / `raw_payload` server-side (gemini `raw_payload BYTEA`, qwen
  `raw_payload`) — only as short-retention, access-controlled `raw_evidence` off the API path, never in the
  public catalog and never shipped. The first cut can skip server-side raw-prose entirely (structured facts are
  enough).
- **`tea.id` reserved-range partitioning (gemini "IDs 1–100,000 curated, scrape starts at 100,001") — over-
  engineered;** the stable-id guarantee is "never renumber, never hard-delete, never reuse," delivered by
  `public_id` + soft-rollback + deterministic seed ids, not by range carving.
