# Rating — 20-catalog-scraping

Prompt: ./prompt.md   ·   Date judged: 2026-06-21

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus     |    5     |   5   |       5       |    2     |    5    |  4.30 |  1   |
| gpt      |    5     |   5   |       5       |    1     |    4    |  4.30 |  2   |
| alice    |    3     |   4   |       3       |    4     |    4    |  2.60 |  3   |
| gemini   |    2     |   4   |       3       |    4     |    4    |  2.25 |  4   |
| deepseek |    2     |   3   |       2       |    4     |    4    |  1.80 |  5   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**The run hinges on one verified source decision and one clean legal line.** Live-fetch verification
settled both. **`artoftea.ru` is the right first source**: server-rendered OpenCart with permissive robots
for product/category pages, **no JSON-LD** (so a CSS-selector parser is required, not a structured-data
reader), exposing exactly the factual fields the schema wants (Cyrillic + transliterated-Chinese name,
type, «Регион сбора чая», harvest year, country). **`tea.ru` is correctly excluded** — its live
`robots.txt` blocks `/catalog/`, `/product/`, `/products/`. Three verified disambiguators separated the
models: (1) **artoftea's flavour is descriptive FREE TEXT, not a numeric 0–5 facet scale** — this *refutes*
gemini's and opus's "0–5 / 8-facet" claim (gemini made it the entire reason to pick the site); (2)
**moychay's *active* catalog is ~700–800 items, not the 5,000–10,000** every model except gpt asserted (the
46k figure is the out-of-stock archive); (3) **the real dedup machinery is `dedup_key` (pinyin-canonical:
`name|slug|TYPE`) + pg_trgm `word_similarity` (`<%`) at 0.3 on a `name_norm` generated column that does NOT
transliterate Cyrillic→Latin** (`f_unaccent` passes Cyrillic through) — so every quoted threshold
(0.6/0.7/0.85) is fabricated, alice's "Cyrillic collapses via `name_norm`" premise is wrong, and the
`ON CONFLICT` keys alice/deepseek invented don't exist. The **legal line is sound across the board** (facts
aren't copyrightable per ГК РФ 1259(5); vendor prose/photos are protected; route raw scraped text to LLM
enrichment **INPUT only**, never shipped, per decision #22) — the one shared flaw is leaning on the **EU
Database Directive as if it governs a RU-on-RU scrape** (operative law is RU Art. 1334; per the Directive's
own Art. 11 the EU sui-generis right doesn't even benefit Russian DB makers). **Build-ready first cut:** a
tiny pinned **httpx + selectolax (+ pypinyin** for cross-script keys) local one-off scraper, a
robots/politeness gate, **facts-only into the public catalog with a build-time guard**, idempotent upsert on
the existing `dedup_key`, driven by the `catalog_miss` (#116) most-wanted list.

**Winner: opus** — the most build-ready, lowest-fabrication plan that actually matches the real codebase. It
is the **only** model whose dedup design mirrors the repo: a **pinyin-canonical key** (the real `dedup_key`
uses a pinyin slug component) with pg_trgm on `name_norm` as the fuzzy fallback, where every other model
either invented a key (gemini's `{country}_{region}_{name}`) or omitted `dedup_key` entirely
(alice/deepseek). Its pins are all real + license-correct (`httpx 0.28.1`, `selectolax 0.4.10`,
`pypinyin 0.55.0`, `scrapy 2.16.0`, `playwright 1.60.0`, lxml/bs4) and it **deliberately declined to pin
reppy** for the exact maintenance reason that sank alice. Strongest legal note alongside gpt (correct
*Ryanair v PR Aviation* C-30/14, correct RU Art. 1334 with the rebuttable ≥10,000-element presumption,
"ToS is the real lever, take an insignificant part, regenerate prose"). And it was honest about gaps — it
explicitly did NOT fetch artoftea's robots root, flagged it ASSUMED, and made "fetch it first" step 1.
Two real demerits keep it off a perfect halluc score: the "8 facets 0-5 flavour, verified" claim (refuted —
free text) and misidentifying moychay as Bitrix with a ~10k active catalog (custom stack, ~700–800).

**gpt (co-winner, #2)** — the **cleanest answer of all five (halluc 1: no refuted pins, no invented
constraints, no fabricated source counts)** and the most rigorous on two points opus missed: it alone
surfaced the load-bearing correctness insight that **the scraped-name normalizer must be the SAME
`lower + f_unaccent` that builds `name_norm`** or pg_trgm matches silently miss; and it was the only model
to label moychay's ~10k as an unverified third-party estimate. Its facts-vs-expression policy is the most
**operationally enforceable** (export/CI gates blocking `tea_description.source LIKE 'scrape:%'`,
null/unknown image licenses, source-null names). It loses the top spot only on being **more "design" than
"go"** (heavy staging-table schema, two sites first) and verifying less live than opus (more left as
"runtime preflight"); minor: cites the `%`/`similarity()` pair where production uses the `<%`
`word_similarity` operator. Clarity 4 (not 5) — very long, the recommendation is buried under dense tables.

**Reuse** — into the build:
- **opus:** the **pinyin-canonical dedup key** (`Style.NORMAL`, tone-free) + pg_trgm `name_norm` fuzzy
  fallback — mirrors the real `dedup_key`; the **pypinyin caveat** (`Style.CYRILLIC` keeps tone digits and
  doesn't merge syllables `пу эр`≠`Пуэр` → needs a Palladius↔pinyin exceptions table) — genuinely useful,
  unique to opus; the verified pin set (`httpx 0.28.1` + `selectolax 0.4.10` + `pypinyin 0.55.0`, **stdlib
  `urllib.robotparser`**, SQLite `content_hash` cache); **step 1 = fetch `artoftea.ru/robots.txt` first**;
  the tea.ru exclusion on the live robots block; one-off **local** bulk import driven by `catalog_miss` (#116);
  facts-only with a **build-time guard** rejecting shipped vendor prose/images.
- **gpt:** the **shared-normalizer** insight (scraped-name normalizer MUST replicate `lower + f_unaccent` or
  pg_trgm misses) — the single most load-bearing dedup-correctness point in the run; the conservative
  weighted dedup score + **anti-vendor-lot-explosion** rule (don't mint a canonical tea per
  harvest-year/pack-size/grade/roast); the **export/CI quality gates** as concrete facts-only enforcement;
  the honest "robots NOT conclusively verified → runtime preflight mandatory" posture; the cleanest legal
  framing (DB copyright vs sui-generis separated). (Trim the heavy staging schema; the gate operator is `<%`.)
- **alice:** the verified **artoftea robots structure** (only service paths `/route=account`,
  `/route=checkout`, `/route=product/search` + sort/filter params disallowed); the Ozon read (doesn't
  disallow `/product/`, Clean-param-heavy); the **operating-model framing** (one-off bulk import not a
  crawler, local dev box, miss-log integration, QA → `verified=true`).
- **deepseek:** the confirmed secondary source others missed — **`h2ocompany.ru`** (real, server-rendered
  1C-Bitrix tea shop); the coexistence priority (curated/verified wins, scraped facts enrich, Wikidata
  preferred); `tenacity`/`loguru`/`diskcache`/`psycopg2` pins are real if a heavier stack is ever wanted.
- **gemini:** **`anyascii`** for Cyrillic→Latin transliteration (real, ISC — a complement to pypinyin for
  display-name generation); the `hishel` + `AsyncSqliteStorage` "scrape once, parse many" cache pattern; the
  conflict-authority hierarchy (curated seed > vendor1 > vendor2, never overwrite curated, fill nulls only);
  discard-photos-immediately + quarantine-prose-server-side.

**Discard** — keep OUT of the build:
- **artoftea numeric 0–5 flavour scale (gemini, opus) — REFUTED.** The live page shows descriptive free text
  («Сладкий, сочный, с небольшой терпкостью»). Do NOT build a `tea_flavor` 1:1 mapping off artoftea — treat
  flavour as prose for LLM enrichment, mapped to the schema's fixed dimensions by the generator.
- **moychay 5,000–10,000 active catalog (all but gpt) — REFUTED.** Active stock ~700–800 (the 46,216 is the
  out-of-stock archive). Don't size crawl scope or the ≥10,000-element legal presumption off it.
- **Fabricated/nonexistent pins:** alice `reppy==0.5.x` (highest real is the abandoned 0.4.14) and deepseek
  `playwright-stealth==1.0.0` (earliest real 1.0.2) — both fail to install. Use stdlib `urllib.robotparser`.
- **Invented dedup thresholds + schema keys:** every quoted similarity threshold (alice 0.6, deepseek 0.7,
  gemini 0.85/0.50) is fabricated — production is `word_similarity` (`<%`) at **0.3** + a `strpos` fallback.
  The `ON CONFLICT` keys alice (`tea_id,locale,source_url`) + deepseek (`source_url,source`) don't exist
  (idempotency is on the unique `dedup_key`); gemini's `{country}_{region}_{name}` is invented. `name_norm`
  does NOT transliterate Cyrillic→Latin, so it alone won't collapse Да Хун Пао / Da Hong Pao — that's the
  pinyin/`dedup_key` path.
- **Wrong-stack / wrong-doctrine:** deepseek's `python manage.py import_tea_catalog` (backend is
  Kotlin/Spring `CatalogSeeder`); its new `is_llm_generated`/`is_raw_scraped`/`is_shippable` flags duplicate
  existing `source`/`license` columns; its Art. 1274 "moral-rights attribution" is the wrong doctrine (1274
  is a free-use/quotation exception). gemini's "≥10,000 elements = AUTOMATIC protection per EU Directive
  96/9" is doubly off (it's a *rebuttable RU* presumption, Art. 1334/1260; the EU Directive has no such
  threshold).
- **EU Database Directive as the operative law (gpt, gemini, opus)** — over-lawyered for a RU-on-RU scrape;
  keep it only as an analogy. Operative mechanism: RU ГК РФ **Art. 1334**.
- **tea.ru "mostly a blog" (deepseek) — REFUTED.** It's a full 25-year e-commerce store; the correct reason
  to exclude it is the live `robots.txt` block, not "it's a blog."
- **`source_text` as an existing field** — there is NO `source_text` column today (the only `sourceText` is a
  transient request DTO into enrichment). A server-side raw-text/staging blob is correct *in spirit* but is a
  **net-new migration**, not a reuse of an existing column.
