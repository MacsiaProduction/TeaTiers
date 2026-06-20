# 20-catalog-scraping — populating the tea catalog by scraping RU tea sites (data rule reversed)

english text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** — a local-first Android (Kotlin + Compose) tier-list app for teas, **Russia-first**.
A small Kotlin/Spring + Postgres catalog/enrichment backend runs on a single Yandex Cloud VM. The catalog
schema (Postgres): `tea` (type, origin_country, region, cultivar, oxidation, brand, a normalized dedup
key), `tea_name` (locale ∈ {en, ru, zh-Hans, pinyin}, name, is_primary, source, license) with a
`name_norm` generated column + pg_trgm GIN index, `tea_description` (locale, short_text, source, license),
`tea_flavor` (0–5 dimensions), `tea_image` (url, license, source_url). There is an `enrichment` tier:
Wikidata (CC0) first → YandexGPT LLM fallback that **generates** the flavour profile + a short RU blurb
(decision #22 — we write our own/AI descriptions, we do NOT copy vendor prose). A demand-driven miss-log
(`catalog_miss`, decision #116) records unresolved `/resolve` queries so the operator can curate the
most-wanted teas.

**Policy change (the reason for this run).** Earlier decisions deliberately kept the catalog
"legally-clean": **#24** (only curated CC/Wikimedia images, no web image fetching) and **#45** (no
crawling the web). The owner has now **reversed the self-imposed no-crawl / CC-only data-sourcing rule**
for this **personal project** — we may scrape RU tea sites to populate the catalog faster. (The repo code
license stays Apache-2.0; that is unrelated to scraping rights.) Run 16 (`research/16-catalog-breadth`,
winner gpt) already explored catalog growth WITHOUT scraping (reframe-as-reference-seed, seed-from-misses);
this run adds **scraping** as a now-allowed tool and designs it.

**The one hard line we still care about — redistribution.** TeaTiers ships a public APK (v0.1.0 is out) and
plans wider release. So distinguish sharply between:
- **Facts** (tea names ru/zh/pinyin, type, origin/region, cultivar, oxidation, vendor) — low redistribution
  risk; these are what we want.
- **Copyrighted expression** (verbatim vendor descriptions, photos) — high risk to ship in a public
  product. Preferred pattern: scrape facts, keep `source`/`source_url` attribution, and let the EXISTING
  LLM enrichment generate our own descriptions — do NOT bake verbatim vendor prose/images into the public
  catalog. The design should make that easy, and flag anywhere it would put copied expression into the
  shipped product.

## Objective

Decide **how to scrape RU tea sites to populate the catalog**: (1) which sources, (2) the scrape/parse
tooling + politeness strategy, (3) the data model + **dedup against the existing catalog**, (4) the
facts-vs-copyrighted-expression handling so the public APK stays clean, and (5) how it integrates with the
existing seed + enrichment + miss-log. Be concrete enough to build from.

## Questions

1. **Sources.** Name the best concrete RU-reachable sources for *factual* tea data (ru + zh/pinyin names,
   type, origin/region, cultivar): tea vendors (e.g. artoftea.ru, tea.ru, real tea shops), tea
   encyclopedias/wikis, marketplaces (Ozon/WB/Yandex Market product pages). For each: what structured data
   it exposes, page structure (server-rendered vs JS), `robots.txt` posture, rough catalog size, and how
   clean/consistent its taxonomy is. Rank them for a RU-first tea catalog.
2. **Scrape + parse tooling** (Python, runnable locally or on the 4 GB VM): `httpx`/`requests` +
   `selectolax`/`BeautifulSoup`/`lxml` for server-rendered pages vs `scrapy` for a real crawl vs a headless
   browser (`playwright`) only if JS-rendered. Pin exact versions + licenses. Politeness: robots.txt
   honoring (`urllib.robotparser`/`reppy`), rate-limit/backoff, caching/incremental re-scrape, identifying
   User-Agent. What's the least heavy stack that gets the job done?
3. **Data model + dedup.** Map scraped fields → `tea`/`tea_name`/`tea_description` (with `source` +
   `source_url`). How to **dedup against the existing catalog** (the normalized dedup key #16 +
   pg_trgm/`name_norm`) and across multiple sources for the same tea (Да Хун Пао / Da Hong Pao / 大红袍 are
   one tea). How to merge/conflict-resolve fields from different sources. Idempotent re-import.
4. **Facts vs copyrighted expression (the redistribution line).** Which scraped fields are safe to store +
   ship in the public APK (factual) vs which must NOT be redistributed verbatim (descriptions, images).
   Recommend a concrete policy: scrape facts → enrichment generates our descriptions; if we store raw
   scraped text at all, keep it server-side as enrichment *input* (like `sourceText`), not shipped output.
   Briefly note the real EU database sui-generis right + RU copyright reality, and how attribution
   (`source`/`source_url`/`license`) should be recorded. Don't over-lawyer — give a usable rule.
5. **Integration + operating model.** One-off **bulk import** (scrape locally → CSV/JSON → import into
   Postgres via the existing seed path) vs an **ongoing scraper** (a job/service)? How it coexists with the
   curated seed, the demand-driven miss-log (#116 — scrape the most-wanted misses first?), and the
   enrichment tier. Where does scraping run (local box vs the VM vs the pelican node)? How to QA the
   imported data (a verified/unverified flag already exists).
6. **Recommendation + sequencing.** A concrete first cut: top 1–2 sources, the tool stack, the
   dedup+import pipeline, the facts-only-into-public policy, and what to build first. Be decisive.

## Evidence standards

- Prefer maintained upstream source / official docs. Pin exact library versions + licenses; flag anything
  you're unsure exists (prior runs saw fabricated pins). For each named site, state what you actually
  verified about its structure/robots vs assumed.
- For the legal note: cite the real mechanism (copyright on descriptions/photos; EU sui-generis DB right;
  site ToS) with sources — but keep it short and practical; this is a personal project that accepts the
  gray area, the goal is to keep the *public APK* clean, not to eliminate all risk.

## Return

A prioritized plan: ranked sources, the scrape/parse + politeness stack (pinned), the data-model + dedup +
idempotent-import design, the facts-vs-expression policy that keeps the public APK clean, and an
operating-model recommendation. End with a one-paragraph recommendation we can turn into a decision.
