# 09-typo-search — typo-tolerant multilingual catalog search

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, …). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** — a local-first Android app for teas with a Kotlin/Spring Boot
catalog backend and PostgreSQL. Users type tea names in Russian, English, Chinese
Hanzi, or pinyin. The backend stores a locale-aware catalog:

- `tea(id, type, origin_country, ..., dedup_key, ...)`
- `tea_name(tea_id, locale in en|ru|zh-Hans|pinyin, name, is_primary, ...)`
- PostgreSQL already has `CREATE EXTENSION pg_trgm`, `CREATE EXTENSION unaccent`,
  and `CREATE INDEX tea_name_trgm_idx ON tea_name USING gin (name gin_trgm_ops)`.

Current live search is still exact substring:

```kotlin
cb.like(cb.lower(name.get("name")), "%${escapeLike(it.lowercase())}%", '\\')
```

`task.md` now explicitly requires a ready search index that tolerates "several
wrong symbols". The app is hosted on one small Yandex Cloud VM with Docker Compose:
backend + self-hosted Postgres + Caddy. Operational simplicity matters; adding a
new always-on service is allowed only if it is clearly worth the RAM/sync cost.

Existing decisions:

- Russia-first, no Google Play Services dependency.
- Backend holds only shared catalog data, no user data.
- Prefer license-safe open data: curated seed, Wikidata, Wikipedia, Open Food Facts
  isolated by provenance.
- No open-ended web crawling for enrichment.
- Single-VM architecture is accepted for MVP; Managed PostgreSQL and extra services
  are upgrade paths.

## Objective

Choose the best implementation path for typo-tolerant catalog search in TeaTiers:
in-Postgres `pg_trgm`/related extensions versus a dedicated open-source search
engine such as Meilisearch, Typesense, or OpenSearch.

## Questions

1. For a catalog of roughly 300 to 50,000 teas with 4 locale/name rows each, can
   PostgreSQL `pg_trgm` satisfy typo-tolerant search for Russian Cyrillic, English,
   pinyin with/without tone marks, and Chinese Hanzi? Give concrete SQL patterns,
   indexes, thresholds, and ranking formulas.
2. What are the limitations of `pg_trgm` for CJK/Hanzi and short strings? Should we
   add generated normalized columns, an alias table, pinyin normalization, `unaccent`,
   ICU collations, `zhparser`/`pg_cjk_parser`, or a separate search-token table?
3. Compare Meilisearch, Typesense, and OpenSearch for this exact project: typo
   tolerance quality, multilingual/CJK behavior, Docker/RAM footprint on a small VM,
   sync strategy from Postgres, Kotlin/Spring integration, licensing, and operational
   complexity.
4. Recommend one approach for MVP and one fallback if it fails. Include an evaluation
   plan with a small gold set covering ru/en/pinyin/zh typos and expected behavior.
5. Identify any claims that must be verified live before implementation: exact current
   versions, Docker memory, CJK tokenizer/plugin availability, JVM client maturity, or
   Postgres extension availability on `postgres:16-alpine`.

## Evidence standards

- Prefer maintained upstream source / official docs over blog posts.
- Pin exact versions; explicitly flag anything you are not certain exists.
- Cite every claim with a link and its publication date; prefer recent sources.
- Treat benchmark claims skeptically unless they are from official docs or reproducible
  tests.

## Return

Return:

1. A comparison table: Postgres `pg_trgm`, Meilisearch, Typesense, OpenSearch.
2. A recommended MVP implementation with SQL/API-level details.
3. A fallback implementation if MVP search quality fails.
4. A gold-set evaluation plan with 20-30 example queries across ru/en/pinyin/zh.
5. A short "do not do" list for approaches that are too risky or overbuilt.

---

Models run: opus, gpt, gemini, deepseek   ·   Date: 2026-06-17

## Adaptations (if any)

- None yet.
