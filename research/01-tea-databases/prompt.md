# 01-tea-databases — public/open tea databases, licenses, multilingual coverage, and how to mirror them

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, …). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

## Context

Project: **TeaTiers** — a personal, local-first tier-list Android app for teas.
Users add teas, rank them into customizable tiers, attach a purchase location
(Yandex/Google/OpenStreetMap geopoint, a marketplace link, or free text), and keep
notes. All user data stays on-device (Room). A single Linux backend (Kotlin + Spring
Boot 3.x + PostgreSQL 16, Flyway migrations) acts purely as a **shared tea-catalog
service**: it ingests/mirrors public tea data and exposes a multilingual search API
that the app queries live and caches.

Locked decisions relevant here:
- The catalog must be **locale-aware from the start**: every tea carries Russian,
  English, and Chinese (Simplified, ideally + pinyin) names.
- Catalog is **rich metadata**: tea type, origin/region, optional cultivar,
  oxidation, brand/vendor — not just a name.
- Russia-first product; data redistribution and local mirroring legality matter.

## Objective

Decide how to source and populate a high-quality, multilingual (ru/en/zh) tea
catalog: which public/open tea databases or datasets exist, whether we may legally
mirror/redistribute them, how complete their ru/zh coverage is, and what schema and
ingestion strategy to use — or whether to hand-curate a seed set instead.

## Questions

1. **Enumerate the sources.** List the public/open tea databases and datasets that
   could seed a tea catalog. Consider at least: Steepster, RateTea, TeaDB, Tea
   Guardian, Cha Dao, World Tea Directory, Wikidata (tea entities / `P279` subclasses
   of tea), Wikipedia category trees (en/ru/zh), Open Food Facts (tea category),
   Kaggle tea datasets, and any notable **Russian** (e.g. чайные базы, RuTea-style
   resources) or **Chinese** (百度百科 / 茶叶 databases, Chinese tea cultivar lists)
   sources. For each: name, URL, what it covers, approximate record count, the data
   fields it exposes, whether there is an API or only a website, and update cadence.
2. **License & legality.** For each source give the exact license / terms of use.
   Is bulk download, local mirroring, and **redistribution** permitted? Scraping
   allowed or prohibited? Attribution/share-alike obligations (e.g. CC-BY-SA, ODbL)?
   Flag any source we must not mirror or redistribute.
3. **Multilingual coverage.** Which sources actually provide **Russian** and/or
   **Chinese** (Simplified/Traditional) tea names, or pinyin/romanization? How
   complete is that coverage? Where would ru/zh names have to be added manually or
   via machine translation, and what authoritative references exist for ru/zh tea
   naming?
4. **Schema.** What canonical fields recur across sources (name, type, cultivar,
   origin/region, oxidation level, vendor/brand, flavor/tasting notes)? Propose a
   **minimal normalized PostgreSQL schema** for a locale-aware tea catalog: a `tea`
   table + a `tea_name(tea_id, locale, name, is_primary)` table (or equivalent),
   metadata columns, and the indexing needed for fast multilingual prefix/substring
   search (incl. Cyrillic and CJK).
5. **Ingestion & mirroring.** Recommend a concrete approach to import + locally cache
   these into Postgres: one-time import vs periodic sync, expected total on-disk
   size, a cross-source **identity/dedup** strategy (same tea from two sources), and
   how to attribute/track each record's source and license per the share-alike terms.
6. **Fallback if data is thin.** If open data turns out too sparse or English-only
   for a quality ru/en/zh catalog, recommend a fallback: a curated seed list of
   ~200–500 common teas (Chinese, Japanese, Indian, etc.) with authoritative sources
   for the ru/zh names, and how big a manual-curation effort that realistically is.

## Evidence standards

- Prefer maintained upstream source / official docs / the dataset's own license page
  over blog posts.
- Pin exact dataset versions / record counts where stated; explicitly flag anything
  you are not certain exists or whose license you could not verify.
- Cite every claim with a link and its publication/last-checked date; prefer recent
  sources. Be explicit when a license forbids redistribution.

## Return

1. A **comparison table** of sources: `Name | Coverage | ~Records | API? | License |
   Mirror/redistribute OK? | ru names? | zh names?`.
2. A **recommended sourcing strategy** (which sources to mirror, which to skip on
   license grounds, and whether to lead with a curated seed).
3. A **PostgreSQL schema sketch** for the locale-aware catalog (DDL or close to it).
4. 5–8 high-quality reference links with dates.
5. An explicit **"uncertain / could not verify"** list.

---

Models run: <opus, gpt, gemini, kimi>   ·   Date: 2026-06-14

## Adaptations (if any)

- <model>: <what you changed for this tool, and why>
