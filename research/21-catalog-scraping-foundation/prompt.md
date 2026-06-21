# 21-catalog-scraping-foundation — the CORRECTED scrape→catalog design (run 20 plan was unbuildable)

english text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** — a local-first Android (Kotlin + Compose) tier-list app for teas, **Russia-first**.
A small Kotlin/Spring + PostgreSQL catalog/enrichment backend runs on one Yandex Cloud VM (Docker Compose).
The public APK (v0.1.0) is out; the app caches the server's catalog tea **id** on-device.

**This is run 21 — a correction of run 20.** Run 20 (`research/20-catalog-scraping`, winner *opus*, decision
**#131**) picked the sources + facts-only policy correctly, but an independent code review
(`context/review/2026-06-21-catalog-scraping-plan-review.md` + the third-pass synthesis) found the locked
**import/identity machinery is not buildable as written**. Take the source choices as settled and design the
CORRECTED foundation. The verified problems with #131:

1. **No upsert exists anywhere in the server.** `CatalogSeeder` is insert-or-skip (`:38 continue`), Wikidata
   import and the enrichment stub are create-or-skip too. An importer built to "upsert on `dedup_key`"
   silently no-ops on existing rows — it cannot apply corrections, new aliases, per-field provenance, or
   conflict decisions.
2. **`tea.source` has a CHECK constraint** `('wikidata','curated','ai','user')` (`V1__catalog_schema.sql:28`)
   that **physically rejects** a `scrape` row. #131's planned guard targets `tea_description.source`
   (free-text, unconstrained) — the wrong column. Internally inconsistent.
3. **`dedup_key` is NOT cross-script canonical.** It is `name|slug|TYPE` for ONE chosen primary
   name/pinyin/type (`DedupKeys.kt`); it does **not** prove `Да Хун Пао`, `Da Hong Pao`, and `大红袍` are the
   same entity. `pypinyin` bridges zh→pinyin but **cannot** transliterate a Cyrillic title.
4. **Re-import idempotency must be keyed by the SOURCE RECORD** (`source + external_id`/`canonical_url`), not
   by the canonical tea key. Source-record identity and canonical-entity resolution are different problems.
5. **The provenance schema can't represent a canonical tea assembled from multiple source records**
   (per-field source/url/license/confidence).
6. **Stable public id.** The API exposes DB `BIGINT` `tea.id`; the Android app persists it as a UNIQUE
   `catalogTeaId` and uses it as the key for `catalog.detail(...)`. A bulk import + seed regeneration / DB
   rebuild can **re-number** teas → every installed client's saved tea points at the wrong tea or 404s.
   There is no soft-rollback: a bad merge that deletes/re-numbers a canonical tea orphans shipped clients.
7. **Persisting raw vendor prose server-side is unnecessary for the first cut** and adds copyright / leakage
   / retention / access-control risk. Structured facts are enough for the importer and for grounded
   description generation (the existing LLM tier writes our OWN blurb, decision #22).
8. **ToS gate is absent** from #131 though run 20's own legal note named ToS (per *Ryanair v PR Aviation*) as
   the real enforceable lever; robots is checked once in #131, not per-run.

The hard line still holds (decisions #129/#131): scrape **facts** (ru/zh/pinyin names, type, origin/region,
cultivar, vendor) into the **public** catalog; **never** ship verbatim vendor descriptions, reviews, or
photos. The repo license stays Apache-2.0.

## Catalog schema (PostgreSQL, today)

`tea` (id BIGINT identity, type, origin_country, region, cultivar, oxidation, brand, `source CHECK ∈
{wikidata,curated,ai,user}`, `verification_status`, a normalized dedup key), `tea_name` (locale ∈
{en,ru,zh-Hans,pinyin}, name, is_primary, source, license; `name_norm` generated col = `lower(f_unaccent(name))`
with a pg_trgm GIN index), `tea_description` (locale, short_text, source, license), `tea_flavor` (0–5 dims),
`tea_image` (url, license, source_url). `catalog_miss` (decision #116) logs unresolved `/resolve` queries for
demand-driven curation. The app is mid-design on a v7 split (`CatalogTeaRef` cached ref vs user `TeaSample`,
decision #132).

## Objective

Design the **buildable** scrape→catalog system: (1) the source-observation→canonical **identity + import
pipeline**, (2) **cross-script canonical identity** + matching against the existing catalog, (3) the
**schema changes** (an upsert path, a `tea.source` migration, source-record + per-field provenance, a stable
public id + soft-rollback), (4) the **facts-only** boundary + ToS/robots gate, (5) the **OSS reuse** decisions,
and (6) the **smallest correct first deliverable** + sequencing. Be concrete enough to build from.

## Questions

1. **Identity & matching.** How do you actually unify `Да Хун Пао` / `Da Hong Pao` / `大红袍` into one
   canonical tea? Design the cross-script canonicalization (the current `dedup_key` can't; pypinyin can't
   transliterate Cyrillic — Palladius↔pinyin tables? a curated alias seed? a human-confirmed match queue?).
   Specify exact-match, trigram-candidate, and human-review tiers and the thresholds. Keep it consistent with
   the server `name_norm` (`lower(f_unaccent(name))`) and the existing pg_trgm index.
2. **Source-record staging & idempotency.** Design the observation→canonical pipeline: an immutable raw
   evidence record, a parsed source record keyed on `(source, external_id/canonical_url)`, a normalized
   candidate, deterministic candidate matching, a review/conflict queue, an approved canonical upsert, a
   versioned clean snapshot. What tables/fields (source id, canonical url, retrieval time, content hash,
   parser version, raw factual fields, field-level provenance, match decision, reviewer/status, import-run id)?
   How is re-import idempotent and how do corrections/aliases flow into an existing canonical row?
3. **Schema changes.** Concretely: the `tea.source` CHECK migration (add `scrape`? a separate `provenance`
   model? per-field provenance vs the current per-table `source`); the real UPSERT path (the server has none —
   design it: a new explicit importer + DTO with NO `source`/`verification_status` defaults, rejecting
   `verified`); how a canonical tea assembled from multiple sources records per-field source/url/license.
4. **Stable public id + rollback.** Decide the stable-public-identity policy so a large import / reseed /
   DB rebuild never re-numbers a tea the Android app already cached as `catalogTeaId`. Soft-rollback:
   deactivate/retract + preserve the id, never hard-delete or reuse an id once returned to clients. How does
   this interact with the v7 `CatalogTeaRef`?
5. **Facts-only boundary + gates.** Confirm: scrape facts only; do NOT persist raw vendor prose server-side
   (or if kept as enrichment input, access-controlled, short-retention, never shipped); the existing LLM tier
   generates our own descriptions (#22). A build-time/CI guard that the public catalog carries no scraped
   prose / unlicensed images. A per-source **ToS gate** (`terms_url` + `terms_checked_at` + owner sign-off)
   as a hard preflight, and **per-run** robots re-check (not one-time). Miss-log text must never become a
   fetch URL / shell arg.
6. **OSS reuse + operating model.** Pin exact versions + licenses. The pilot is demand-driven (top
   `catalog_miss` terms) and tens of records — is the locked **httpx + selectolax + stdlib `urllib.robotparser`**
   right for the pilot, with **Scrapy + Protego** reserved for full sitemap traversal later? Where does each
   earn its keep? `pypinyin` limits (can't bridge Cyrillic). Where does scraping run (local one-off, not the
   API path, not the prod VM)? How does it coexist with the curated seed + miss-log + enrichment + the v7 work?
7. **Recommendation + sequencing.** The smallest CORRECT first deliverable (the review says it is the
   **foundation**, not the crawler): name the ordered, PR-sized steps. What to build first, what to defer.
   End with a one-paragraph recommendation we can lock as a decision (superseding the unbuildable parts of #131).

## Evidence standards

- Prefer maintained upstream docs. Pin exact library versions + licenses; flag anything you're unsure exists
  (prior runs saw fabricated pins). For each named site, state what you actually verified vs assumed; live
  pages are volatile — the design must re-check source/robots at run time.
- Ground every schema/identity claim in the real model above. Do NOT invent an upsert, a cross-script
  `dedup_key`, or a `tea.source` value that doesn't exist — design the change explicitly.
- Legal: short + practical (RU ГК РФ Art.1334 is operative, NOT the EU Directive for a RU-on-RU scrape; ToS
  is the enforceable lever; facts aren't copyrightable). Goal = keep the PUBLIC APK clean, not zero risk.

## Return

A prioritized, buildable plan: the identity+import pipeline, the concrete schema migrations (upsert,
`tea.source`, source-record + per-field provenance, stable-id + soft-rollback), the facts-only + ToS/robots
gate, the pinned OSS stack, and the ordered PR-sized first cut. End with a one-paragraph lockable recommendation.
