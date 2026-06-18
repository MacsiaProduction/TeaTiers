# Decision

TeaTiers should **not try to become an exhaustive tea SKU database for MVP**. Under the locked constraints, there is no compliant open dataset that covers the Russian vendor/SKU long tail at the scale users expect. The sustainable strategy is:

**Use the shared catalog as a verified “famous teas + canonical types + high-demand misses” reference layer, and make local custom teas/OCR the primary long-tail path.**

The catalog should grow by **demand-driven operator curation from aggregate miss logs**, plus a very narrow **anonymous suggestion/correction queue**, not by crawling, not by importing community/vendor catalogs, and not by LLM-authored rows. All sources below were checked against upstream pages where possible; accessed 19 June 2026.

---

# 1. Dataset table

| Dataset / source                                                                                        |                                                                                                 Exact license |                                     Redistribute + re-serve in TeaTiers? |                                                                                       Approx tea-relevant coverage |                                             RU names? |                                             ZH names? | Structured metadata                                                                          | Update cadence                                                   | Notes                                                                                                                                                |
| ------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------: | -----------------------------------------------------------------------: | -----------------------------------------------------------------------------------------------------------------: | ----------------------------------------------------: | ----------------------------------------------------: | -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Wikidata structured data** ([wikidata.org][1])                                                        |                                                   **CC0-1.0** for structured data; non-main namespaces differ |                                                                  **Yes** |                                            Low hundreds of tea-ish entities after filtering, not thousands of SKUs |                                               Partial |                                               Partial | Strong for broad concepts; weak/inconsistent for cultivar, origin, tea style, flavor, vendor | Live; RDF/dumps plus 24h incremental dumps                       | Best open core. Good for canonical famous teas/types, aliases, Wikipedia links, Commons links, external IDs. Not enough for RU long tail.            |
| **Wikipedia article text / summaries** ([foundation.wikimedia.org][2])                                  |                                                            **CC-BY-SA-4.0** / GFDL legacy terms for much text |                 **Yes, but isolate attribution/share-alike obligations** |                                                                   Famous teas, tea types, history, regional styles |                                 Yes for some articles |                                 Yes for some articles | Mostly prose, not structured rows                                                            | Live / dumps                                                     | Use as a source for operator-authored summaries or attributed excerpts. Do not silently mix copied text into CC0 rows.                               |
| **Wikimedia Commons tea images** ([Викисклад][3])                                                       |                                              Per-file free license: CC0, CC-BY, CC-BY-SA, public domain, etc. |                                           **Yes, per file license only** |                                                                 Images of famous teas, leaves, ceremonies, regions |                                                   N/A |                                                   N/A | File metadata/license                                                                        | Live                                                             | Fits decision #24. Never import arbitrary web images; only curated per-license Commons files.                                                        |
| **Open Food Facts taxonomy / database** ([wiki.openfoodfacts.org][4])                                   | **ODbL-1.0** for database; **DbCL** for individual contents; images often CC-BY-SA and may contain trademarks |                                                 **Yes only if isolated** |                     Food categories, some packaged tea products, brands, ingredients; not reliable RU tea coverage |  Some multilingual taxonomy terms; product names vary |           Some multilingual terms; product names vary | Categories, ingredients, labels, brands, nutrition                                           | Continuously updated                                             | Keep in a separate OFF-derived table or adapter. Do not blend ODbL records into the core catalog unless you accept share-alike database obligations. |
| **AGROVOC** ([FAOHome][5])                                                                              |                                                                                                 **CC-BY-4.0** |                                                **Yes, with attribution** |                                   Agricultural vocabulary; likely only generic tea/Camellia concepts, not products |  Yes, FAO lists Russian among CC-BY-covered languages |  Yes, FAO lists Chinese among CC-BY-covered languages | Concepts, labels, definitions, broader/narrower relations                                    | Maintained continuously; released monthly                        | Useful for controlled vocabulary, aliases, taxonomy normalization. Not a catalog breadth source by itself.                                           |
| **NALT — USDA National Agricultural Library Thesaurus** ([lod.nal.usda.gov][6])                         |                        Appears **CC0-1.0 / public-domain-style USDA data**, but verify exact download package |                                   **Likely yes; verify package license** |                                                          Broad agriculture/food thesaurus, some tea/agronomy terms |                                      No meaningful RU |                                      No meaningful ZH | Concepts, synonyms, subject categories                                                       | Annual January updates per Ag Data Commons summary               | Good English normalization source. Low direct value for RU/Chinese catalog breadth.                                                                  |
| **FoodOn ontology** ([GitHub][7])                                                                       |                                                                                                 **CC-BY-4.0** |                                                **Yes, with attribution** |                                                           Generic food ontology concepts; a few tea/beverage terms |                                        Mostly English |                                        Mostly English | Ontology classes, food roles, relations                                                      | GitHub releases / maintained ontology                            | Useful for internal type/facet normalization. Not a user-visible tea list.                                                                           |
| **USDA FoodData Central** ([fdc.nal.usda.gov][8])                                                       |                                                                                   **CC0-1.0 / public domain** |                                                                  **Yes** |                                       Generic tea beverages and US packaged branded foods; not RU vendor long tail |                                                    No |                                                    No | Nutrition, branded food fields, ingredients, data type                                       | Branded Foods monthly; other datasets yearly/twice yearly/legacy | Good for nutrition reference if ever needed. Poor fit for Russian tea names and Chinese tea aliases.                                                 |
| **EFSA FoodEx2 / EFSA catalogues** ([European Food Safety Authority][9])                                |                   EFSA legal notice: reuse authorised with acknowledgement; per-document conditions may apply |      **Yes/unclear: use cautiously with attribution and per-file check** |                                Food classification hierarchy, including beverage/tea-like categories, not products |              Mostly English / EU classification terms |                                      No meaningful ZH | Hierarchies, facets, descriptors                                                             | EFSA catalogue repo says catalogues are published quarterly      | Useful as an internal classification vocabulary. Not enough for catalog rows.                                                                        |
| **FAOSTAT** ([FAOHome][10])                                                                             |                         **CC-BY-4.0** for FAO datasets, with caveat that some third-party datasets may differ |                             **Yes, with attribution and metadata check** |                                                              Country/year commodity stats for tea production/trade | Interface/metadata may be multilingual; not tea names |                  Not useful for Chinese product names | Country, commodity, year, production/trade metrics                                           | Dataset-specific                                                 | Useful for origin/background metadata, not tea names.                                                                                                |
| **Catalogue of Life** ([catalogueoflife.org][11])                                                       |                                                                      **CC-BY-4.0 unless otherwise indicated** |                                                **Yes, with attribution** |                                                                Taxonomic backbone for Camellia and related species |                                   No catalog RU names |                                   No product ZH names | Taxonomy, accepted names, synonyms                                                           | Versioned releases; 2026-05-15 release cited                     | Useful for botanical correctness, not consumer tea coverage.                                                                                         |
| **WCVP via GBIF — World Checklist of Vascular Plants** ([gbif.org][12])                                 |                           **CC-BY-4.0** for WCVP dataset; GBIF-hosted datasets can be CC0, CC-BY, or CC-BY-NC |     **Yes for CC-BY/CC0 records; avoid CC-BY-NC for commercial app use** |                                                        Camellia taxonomy, synonyms, distribution; no consumer teas |                                                    No |                              No meaningful product ZH | Taxonomy, plant names, provenance                                                            | Dataset published 4 June 2026 in cited GBIF record               | Good for plant/cultivar taxonomy sanity checks. Not a tea SKU/name source.                                                                           |
| **World Flora Online taxon pages** ([worldfloraonline.org][13])                                         |                                                                        **CC-BY-4.0** per cited WFO taxon page |                                                **Yes, with attribution** |                                                                         Plant taxonomy such as *Camellia sinensis* |                                                    No |                                      No product names | Taxonomic names, synonyms                                                                    | Continuously maintained/versioned                                | Useful only for botanical backbone.                                                                                                                  |
| **Dataset of Camellia Cultivar Names in the World / Biodiversity Data Journal** ([bdj.pensoft.net][14]) |                                                                         Article/dataset appears **CC-BY-4.0** | **Probably yes for the published dataset; verify downloadable artifact** | Large Camellia cultivar-name dataset; cited source reports 45,210 cultivar names and 429 additionally used for tea |                                      No meaningful RU |    Some East Asian-origin names, but not a RU catalog | Accepted/synonym/extinct/in-use/cultivar metadata                                            | Static 2021 dataset                                              | Potentially the best non-Wikidata open source for cultivar aliases. Still not vendor teas or supermarket blends.                                     |
| **International Camellia Register live site** ([camellia.iflora.cn][15])                                |                                                                   Site states copyright / all rights reserved |                                             **No, do not scrape/mirror** |                                              Huge live Camellia register; cited page reports 53,329 cultivar names |                                                    No | UI has Chinese, but not a redistributable tea catalog | Cultivar pages, photos, references                                                           | Live                                                             | Use only if a specific page/license grants reuse. Do not treat the site as open data.                                                                |
| **LanguaL Food Description Thesaurus** ([langual.org][16])                                              |           “Free of charge” is stated, but no clear open redistribution license found; copyright notice exists |                           **Unclear / no for mirroring until clarified** |                                                                       Food description thesaurus, not tea-specific |                                      No meaningful RU |                                      No meaningful ZH | Faceted food description                                                                     | Maintained                                                       | Looks useful, but “free to download/use” is not enough for re-serving in TeaTiers.                                                                   |
| **FoodRepo** ([PMC][17])                                                                                |                  Paper/API material indicates **CC-BY-4.0**, but current database/API terms need confirmation |                                                              **Unclear** |                                                    Swiss food products; possible tea products but not RU long tail |                                                    No |                                                    No | Product/nutrition/brand data                                                                 | Unclear                                                          | Not worth MVP effort. Verify current terms before any import.                                                                                        |
| **TMDB — Tea Metabolome Database** ([ngdc.cncb.ac.cn][18])                                              |                 No clear redistributable license found; cited metadata even says accessibility “Unaccessible” |                                                         **No / unclear** |                                                                            Chemistry/metabolites, not catalog rows |                                                    No |                                                    No | Tea constituents, source references                                                          | Unclear/outdated                                                 | Not useful for catalog breadth.                                                                                                                      |
| **GB/T 30766-2014 Tea Classification** ([chinesestandard.net][19])                                      |                                                           Copyrighted standard; no open dataset license found |                                         **No for mirroring text/tables** |                                                                                        Tea classification standard |                                               Chinese |                                               Chinese | Classification terms                                                                         | Standard publication                                             | Can inform operator-authored categories, but do not copy standard text/tables.                                                                       |
| **ISO 20715:2023 Tea — Classification of tea types** ([ISO][20])                                        |                                                                       ISO standard, copyrighted / paid access |                                         **No for mirroring text/tables** |                                                                                            Tea type classification |                                               English |                                                    No | Classification                                                                               | Standard publication                                             | Useful as a design reference only if lawfully accessed; do not import content verbatim.                                                              |
| **GOST 32593-2013 / related tea standards** ([КНТД][21])                                                |                                                         Standards text; no open redistributable license found |                                         **No for mirroring text/tables** |                                                                                   RU tea terminology / definitions |                                               Russian |                                                    No | Terms/classification                                                                         | Standard publication                                             | Use only as non-copied inspiration for own wording.                                                                                                  |

**Bottom line from the dataset search:** open sources can improve **taxonomy, aliases, botanical correctness, and famous-tea coverage**. They do **not** solve the RU long tail of vendor SKUs, blends, marketplace names, and seasonal private-label teas.

---

# 2. Wikidata coverage reality-check

Wikidata is still the best legal open core because its structured data is CC0, it has labels/aliases, it links to Wikipedia and Commons, and it already has identifiers such as Open Food Facts category/ingredient IDs on broad tea concepts. The main `tea` item alone shows useful structure: subclass links, native Chinese label `茶`, pinyin `chá`, made-from-material statements, natural product of taxon *Camellia sinensis*, Commons links, and external IDs. ([wikidata.org][22])

But it should be treated as a **low-hundreds reference graph**, not a coverage engine. I could not obtain a reliable live WDQS count through the browsing environment, so the safest product decision is to make the count part of your import CI. A realistic operating estimate for TeaTiers is:

| Wikidata slice                                                                                                                |                           Practical estimate for TeaTiers | Confidence |
| ----------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------: | ---------: |
| Tea-related entities after broad filtering: tea, tea types, named famous teas, tea culture objects, Camellia-related concepts |                    **~200–600** candidates before cleanup |     Medium |
| User-visible tea catalog rows worth showing after cleanup                                                                     |                                              **~100–300** |     Medium |
| Rows with Russian labels                                                                                                      |                         **Likely <50–60%** of useful rows |     Medium |
| Rows with Chinese labels                                                                                                      | **Likely better for famous Chinese teas, poor elsewhere** |     Medium |
| Rows with both RU + ZH labels                                                                                                 |                             **Likely tens, not hundreds** | Medium-low |
| Rows with useful structured origin/type/cultivar metadata                                                                     |                                  **Tens to low hundreds** |     Medium |
| Rows that represent branded RU vendor SKUs/blends                                                                             |                                             **Near zero** |       High |

The CI queries should count at least these buckets:

```sparql
# Candidate tea-ish item set.
# Start conservative, then manually maintain allow/deny lists.
SELECT (COUNT(DISTINCT ?item) AS ?n) WHERE {
  {
    ?item wdt:P31/wdt:P279* wd:Q6097.   # instance of subclass of tea
  } UNION {
    ?item wdt:P279* wd:Q6097.           # subclass tree under tea
  } UNION {
    ?item wdt:P186 wd:Q1075712.         # made from tea leaves, if modeled
  } UNION {
    ?item wdt:P1582 wd:Q201164.         # natural product of taxon Camellia sinensis, if modeled
  }
}
```

```sparql
# RU and ZH label coverage over your candidate set.
SELECT
  (COUNT(DISTINCT ?item) AS ?items)
  (COUNT(DISTINCT ?ruItem) AS ?withRu)
  (COUNT(DISTINCT ?zhItem) AS ?withZh)
WHERE {
  {
    ?item wdt:P31/wdt:P279* wd:Q6097.
  } UNION {
    ?item wdt:P279* wd:Q6097.
  }

  OPTIONAL {
    ?item rdfs:label ?ruLabel.
    FILTER(LANG(?ruLabel) = "ru")
    BIND(?item AS ?ruItem)
  }
  OPTIONAL {
    ?item rdfs:label ?zhLabel.
    FILTER(LANG(?zhLabel) IN ("zh", "zh-hans", "zh-cn"))
    BIND(?item AS ?zhItem)
  }
}
```

Where Wikidata fails for TeaTiers:

1. **Russian transliterations of Chinese teas** are uneven. Famous teas may have `Лунцзин`, `Да Хун Пао`, `Тегуаньинь`; long-tail mountain, factory, cultivar, roast, harvest, and vendor spellings will be missing or inconsistent.

2. **Branded and blended supermarket teas are mostly absent.** A Russian user’s real shelf has “Greenfield…”, “Ahmad…”, “Мойчай…”, “Wildberries/Ozon listing names”, house blends, seasonal blends, and local shop names. Wikidata is not built for that SKU layer.

3. **Flavor-relevant fields are sparse.** Wikidata can model broad type and origin, but it rarely has reliable structured bitterness/smokiness/roast/oxidation/processing intensity fields.

4. **Cultivar and product are conflated.** `Camellia sinensis`, cultivar, processing style, region, brand, and finished product are different entities in a good TeaTiers model; Wikidata often leaves those distinctions incomplete.

5. **Aliases are not enough for search.** Tea search needs Russian phonetic spellings, pinyin with/without tones, Hanzi, simplified/traditional variants, vendor spellings, typos, and partial marketplace names. Wikidata labels are a starting set, not a search index.

**Bulk sync verdict:** a full Wikidata bulk sync is not worth it for MVP. A **small periodic subgraph sync** is worth it: maintain a list of accepted Wikidata QIDs plus a WDQS candidate query, refresh labels/aliases/claims weekly or monthly, and store only the small tea subgraph with provenance. Wikidata publishes RDF dumps and 24-hour incremental dumps, but for a 4 GB VM and one operator, full-dump handling is unnecessary complexity. ([wikidata.org][1])

---

# 3. Breadth strategy comparison

| Strategy                                                               |                                               Coverage gain |                                                         Compliance / license risk |           Ops / operator burden |                                   Privacy: stays no-PII? |                                             Data-quality risk | Verdict                                                                              |
| ---------------------------------------------------------------------- | ----------------------------------------------------------: | --------------------------------------------------------------------------------: | ------------------------------: | -------------------------------------------------------: | ------------------------------------------------------------: | ------------------------------------------------------------------------------------ |
| **More open datasets beyond current sources**                          |                                               Low to medium |                                Low if CC0/CC-BY; medium for ODbL/unclear datasets |               Low once imported |                                                      Yes |                               Medium: taxonomy ≠ catalog rows | Use for normalization/facets, not for breadth expectations.                          |
| **Wikidata on-demand resolve only**                                    |                                               Low to medium |                                                     Very low, CC0 structured data |                             Low |                                                      Yes |                               Medium: sparse aliases/metadata | Keep. Good MVP fallback.                                                             |
| **Small periodic Wikidata subgraph sync**                              |                                               Low to medium |                                                                          Very low |                      Low-medium |                                                      Yes | Lower than live resolve because operator can curate allowlist | Do it after MVP if resolve latency or availability matters.                          |
| **Full Wikidata dump processing**                                      |                                 Same coverage as small sync |                                                                          Very low |            High for little gain |                                                      Yes |                                                        Medium | Not worth it on one small VM.                                                        |
| **OFF import into core catalog**                                       | Medium for packaged food globally, low for RU tea long tail |                          High because ODbL share-alike can affect database design |                          Medium |                                                      Yes |                                                   Medium-high | Do not mix into core. Keep isolated if used.                                         |
| **Anonymous “suggest this tea” form**                                  |                                                      Medium |                                             Low if contribution grant is explicit |                          Medium |                    Yes, if no email/user ID/IP retention |                  Medium: spam, duplicates, hallucinated names | Best user-contribution variant for MVP+.                                             |
| **Anonymous correction form**                                          |                     Medium for quality, low for new breadth |                                                       Low with contribution grant |                      Low-medium |                                                      Yes |                                                    Low-medium | Add with suggestions. Corrections are easier to moderate than new rows.              |
| **Promote user’s custom local tea to shared catalog**                  |                                              High potential | Medium: must get explicit license grant and strip personal notes/photos/locations |                     Medium-high | Yes only with explicit opt-in and careful payload limits |                                                   Medium-high | Later phase. Do not auto-promote.                                                    |
| **Passive aggregate miss logging**                                     |                                        High for real demand |                                             Low if name-string-only and aggregate |                             Low |                      Yes if no account/device/IP linkage |                                     Low: only a demand signal | Highest ROI. Do this first.                                                          |
| **Operator curation of top-N misses**                                  |                                                        High |                             Low if rows are operator-authored and sourced legally |         Medium but controllable |                                                      Yes |                                                    Low-medium | Main growth engine.                                                                  |
| **Operator-only LLM/batch assist over misses**                         |                               Medium-high productivity gain |             Medium if LLM output becomes content; low if only clustering/drafting |                      Low-medium |                       Yes if only aggregate miss strings |                                                        Medium | Allowed only as private tooling; production rows must be human-reviewed and sourced. |
| **Public wiki/community catalog without accounts**                     |                                              High potential |                                                                       Medium-high |    High abuse/moderation burden |                                      Hard to keep no-PII |                                                          High | Not MVP-compatible.                                                                  |
| **Product reframe: catalog as reference seed + strong custom-add/OCR** |                     Very high UX gain, not catalog-row gain |                                                                          Very low |                             Low |                                                      Yes |                                                           Low | Essential. Prevents “catalog miss = app failure.”                                    |
| **Vendor opt-in feeds under contract/CC0**                             |                                   High for selected vendors |                                                          Low if contract is clear | Medium-high relationship burden |                                                      Yes |                                                        Medium | Later only. Worth considering after traction.                                        |
| **Crawling commercial/community sites**                                |                                                        High |                                                  Prohibited by locked constraints |                            High |                                                  Depends |                                                          High | Do not do.                                                                           |

---

# 4. User-contribution model without accounts

## Option A — Anonymous “suggest this tea”

Best fit for one-operator MVP+.

The app shows this only after a miss or from a row screen:

```text
Didn’t find it? Suggest this tea for the shared catalog.
Only tea names and public product facts should be submitted.
Do not include personal notes, purchase locations, photos, receipts, or contact details.
By submitting, you license this suggestion to TeaTiers under CC0 for inclusion in the shared catalog.
```

Minimum payload:

```json
{
  "submitted_name": "Да Хун Пао сильной прожарки",
  "optional_original_name": "大红袍",
  "optional_pinyin": "da hong pao",
  "optional_brand_or_vendor": "operator-reviewed string, not required",
  "optional_type_hint": "oolong",
  "optional_source_url": "optional, stripped of query params",
  "client_locale": "ru-RU",
  "app_version_major": "1",
  "license_grant": "CC0-1.0",
  "created_at": "server timestamp"
}
```

Do **not** accept shared-catalog photos in MVP. A user’s own photo is fine for their local tea record, but shared photo publishing creates license/consent/moderation overhead. If evidence URLs are allowed, strip tracking parameters and treat them only as review hints, not as mirrored data.

Privacy posture: no account, no email, no user ID, no stable device ID. Rate-limit at the edge. If you need abuse controls, store only short-lived daily salted IP hashes, and document that as a deliberate operational exception.

## Option B — Anonymous correction

Lower risk than new rows. Allow users to report:

```json
{
  "catalog_tea_id": "uuid",
  "field": "ru_name | zh_name | pinyin | type | duplicate | source_problem",
  "suggested_value": "Те Гуань Инь",
  "comment": "optional, max 300 chars",
  "license_grant": "CC0-1.0"
}
```

This gives good quality improvement with less spam surface. It should share the same review queue as suggestions.

## Option C — Promote a local custom tea

Good later, but not first. The user’s local tea may contain personal notes, purchase places, photos, ratings, and OCR text. Promotion must therefore be a **separate explicit flow** that submits only a sanitized public subset:

```json
{
  "names": {
    "ru": "...",
    "en": "...",
    "zhHans": "...",
    "pinyin": "..."
  },
  "type_hint": "...",
  "brand_or_vendor": "optional",
  "public_package_text_excerpt": "optional, user confirms they may submit",
  "license_grant": "CC0-1.0"
}
```

Never submit notes, ratings, board placement, purchase location, marketplace URL with tracking parameters, local photo IDs, or OCR image data.

## Option D — Open community editing

Not recommended. Comparable catalog apps with true long-tail coverage usually rely on accounts, reputational moderation, or explicit contribution terms. Untappd lets users add missing beers after search, but only registered users can create beers; Open Library makes bulk data available and asks contributions to be CC0; BoardGameGeek now requires API registration/authorization; these are all signs that long-tail public catalogs eventually need identity, moderation, or access control. ([help.untappd.com][23])

---

# 5. Demand-driven curation from misses

This is the highest-ROI mechanism because it grows exactly where TeaTiers users feel pain.

## What to log

Log only **aggregate, normalized, name-string-only misses**:

```sql
CREATE TABLE catalog_miss_query (
  normalized_query        text PRIMARY KEY,
  display_sample          text NOT NULL,
  query_script            text NOT NULL, -- ru / latin / hanzi / mixed / unknown
  client_locale           text NULL,     -- coarse only, e.g. ru-RU
  first_seen              timestamptz NOT NULL DEFAULT now(),
  last_seen               timestamptz NOT NULL DEFAULT now(),
  hit_count               integer NOT NULL DEFAULT 1,
  reviewed_state          text NOT NULL DEFAULT 'new',
  review_note             text NULL
);
```

Do not store IP, account, device ID, installation ID, board ID, local tea ID, photos, notes, purchase place, or user marketplace URL. If the query looks like a URL, either do not log it or normalize it to a safe text token after stripping query parameters and fragments.

## Normalize before counting

Apply the same search normalization used by the app:

* lowercase;
* Unicode normalization;
* `ё` → `е`;
* trim punctuation and repeated whitespace;
* pinyin tone removal;
* simplified/traditional Chinese normalization if you already have the library;
* remove obvious package-size tokens like `100г`, `25 пакетиков`, `50g`;
* keep brand/vendor tokens for review, but do not make them canonical automatically.

## Review workflow

Use three tables: misses, candidates, promoted catalog rows.

```sql
CREATE TABLE catalog_candidate (
  id                     uuid PRIMARY KEY,
  source_kind            text NOT NULL, -- miss_log | suggestion | correction | operator
  source_ref             text NULL,
  candidate_name_raw     text NOT NULL,
  normalized_key         text NOT NULL,
  proposed_type          text NULL,
  proposed_names_json    jsonb NOT NULL DEFAULT '{}',
  proposed_sources_json  jsonb NOT NULL DEFAULT '[]',
  review_state           text NOT NULL DEFAULT 'needs_review',
  created_at             timestamptz NOT NULL DEFAULT now(),
  reviewed_at            timestamptz NULL
);
```

Promotion rules:

1. A miss log is **demand evidence**, not content provenance.
2. A public catalog row must be either:

   * imported from an allowed source with license/provenance; or
   * operator-authored original curation; or
   * user-submitted under explicit CC0 grant and reviewed.
3. `verified=true` means an operator reviewed the row and source/provenance is complete.
4. `unverified` should not mean “AI wrote this”; for MVP, avoid publishing AI-authored rows at all.

## Top-N curation loop

A sustainable loop for one operator:

1. Export top misses by count and recency.
2. Cluster near-duplicates: `да хун пао`, `дахунпао`, `大红袍`, `da hong pao`.
3. Split into buckets:

   * famous/canonical tea type;
   * cultivar/origin/style;
   * brand/vendor blend;
   * obvious typo of existing row;
   * not tea / garbage / personal text.
4. First curate canonical teas and common aliases.
5. For brand/vendor blends, add only if the user-submitted name is enough and the row can be written as minimal factual metadata without scraping: brand, display name, type if obvious, aliases, no copied descriptions.
6. Add source/provenance:

   * Wikidata/Wikipedia/Commons/open dataset where available;
   * `TeaTiers operator original curation` for own-authored rows;
   * `TeaTiers anonymous suggestion, CC0 grant` only for explicit suggestions, not passive miss logs.

## Is an operator-only batch enrichment run compliant?

**Yes, but only with strict boundaries.**

Allowed:

* cluster top misses;
* transliterate/pinyin-normalize candidate names;
* suggest duplicate merges;
* suggest which Wikidata/open-source QIDs to inspect;
* generate a private review spreadsheet.

Not allowed under the AI-off MVP posture:

* publish LLM-authored tea rows directly;
* generate flavor profiles or blurbs and mark them verified;
* use an LLM as the row’s real source;
* bypass per-row provenance.

The correct framing is: **LLM as private operator tooling, not production enrichment**. The production artifact is still human-reviewed, minimally factual, and source/provenance-complete.

---

# 6. Reframe vs. grow

A large part of the correct answer is product framing.

For the RU long tail, “shared catalog hit” cannot be the primary success path. The app should treat catalog search like this:

1. **Known reference tea found** → user adds canonical tea with aliases, type, origin, optional flavor seed.
2. **No hit** → user creates a local custom tea immediately.
3. **OCR/package photo available** → user extracts text locally/server-side by explicit opt-in, reviews it, and stores it locally.
4. **Optional** → user can anonymously suggest the tea to the shared catalog.

The UX copy should avoid implying that “not found” is an error:

```text
Not in the shared reference catalog yet.
Create it as your own tea now; TeaTiers works fully with custom teas.
```

Comparable long-tail catalog products usually solve breadth with either massive open data, accounts, or community contribution. Open Library uses public-domain/CC0-style catalog data and bulk downloads; Untappd lets users create missing beers but requires registered users; BoardGameGeek’s API has moved toward registration/authorization. TeaTiers explicitly rejects accounts and server-side user data, so it should not copy their full community model. It should copy only the **reviewed suggestion queue** and **“can’t find → create” UX** pattern. ([openlibrary.org][24])

---

# 7. Recommended phased strategy

## Phase 0 — Make catalog misses non-fatal

**Do first.**

Product changes:

* Rename the shared catalog in UI to something like **“TeaTiers reference catalog”** or **“Known teas”**, not “the catalog.”
* Put **Create custom tea** as the primary CTA after no result.
* Let custom teas have the same first-class features as catalog teas: board placement, notes, photos, OCR text, purchase place, flavor profile.
* Show catalog provenance only for shared rows, not local-only rows.

Coverage gain: none directly, but it fixes the first-session experience.

Compliance posture: safest possible; all long-tail data stays on-device.

Operator effort: one-time UI/API work, almost no ongoing work.

## Phase 1 — Finish the verified 300-row seed and improve aliases

The 300-row seed should be biased toward:

* canonical Chinese teas with RU spellings: Лунцзин / 龙井 / Longjing, Да Хун Пао / 大红袍, Те Гуань Инь / 铁观音, Пуэр / 普洱;
* Russian-market generic types: чёрный чай, зелёный чай, улун, шу пуэр, шэн пуэр, жасминовый чай, гречишный чай only if you deliberately model non-*Camellia* infusions;
* common English names and transliterations;
* type/origin/cultivar where factual and sourced.

Use Wikidata/Commons/Wikipedia for rows where available, but write your own compact descriptions unless you intentionally carry CC-BY-SA attribution. Use AGROVOC/FoodOn/NALT/EFSA only for internal type normalization, not as a visible source of many tea rows.

Coverage gain: good for famous/reference teas; poor for vendor SKUs.

Compliance posture: strong.

Operator effort: bounded and sustainable.

## Phase 2 — Add aggregate miss logging

Add the `catalog_miss_query` table and a small operator export.

Rules:

* Log only normalized query strings and counts.
* Do not log successful searches by default.
* Do not log local custom tea contents unless the user explicitly suggests the tea.
* Add privacy-policy text: “TeaTiers may collect anonymous aggregate search strings that returned no shared-catalog result to improve catalog coverage.”

Coverage gain: high, because it reveals the actual RU demand distribution.

Compliance posture: strong if name-string-only and aggregate.

Operator effort: low.

## Phase 3 — Monthly top-miss curation

Create a simple admin-only workflow:

```text
top misses → cluster duplicates → classify → create candidate → source/rewrite → promote verified row
```

Promotion priority:

1. existing known tea missing aliases;
2. famous tea missing from seed;
3. high-frequency vendor-neutral style;
4. high-frequency brand/vendor product if it can be represented minimally without copying third-party descriptions;
5. low-frequency ambiguous SKUs stay local-only.

Expected result: after enough usage, the catalog becomes **locally relevant**, not globally exhaustive. The catalog will cover what TeaTiers users actually type.

Compliance posture: strong.

Operator effort: predictable: review only the top N misses per cycle.

## Phase 4 — Add anonymous suggestions and corrections

Add two endpoints:

```http
POST /api/v1/catalog/suggestions
POST /api/v1/catalog/corrections
```

Constraints:

* no accounts;
* no contact field;
* no published photos;
* max payload sizes;
* rate limit;
* CC0 contribution grant checkbox;
* all submissions hidden until reviewed;
* admin queue only.

This gives users a deliberate way to contribute public data without making passive miss logs carry legal/provenance weight.

Coverage gain: medium-high.

Compliance posture: good if CC0 grant is explicit.

Operator effort: medium, controlled by queue caps.

## Phase 5 — Operator-only batch assist

Use private scripts over top misses:

* dedupe clusters;
* script/language detection;
* pinyin normalization;
* Wikidata candidate matching;
* candidate spreadsheet generation.

A one-off LLM can be used as a private assistant only if every row is later human-reviewed, rewritten, and sourced. Do not ship LLM-authored flavor profiles, descriptions, or names as production enrichment while the AI tier is off.

Coverage gain: medium productivity boost.

Compliance posture: acceptable only with human review and non-LLM provenance.

## Phase 6 — Later, optional vendor opt-in feeds

The only scalable way to cover RU vendor catalogs without crawling is **permissioned feeds**:

* vendor sends CSV/JSON;
* license/contract says TeaTiers may store and re-serve names and minimal product metadata;
* images excluded unless explicit license;
* no scraping;
* provenance row points to vendor feed and license.

This relaxes no-crawl but not the legal posture. It adds relationship overhead, so it is not MVP work.

---

# Final recommendation

TeaTiers should adopt a **three-layer catalog model**:

1. **Verified reference core**: 300–600 operator-curated famous teas, canonical types, Chinese/Russian aliases, Wikidata/Wikipedia/Commons/open-source provenance.
2. **Demand-grown shared layer**: rows created from top aggregate misses and explicit anonymous suggestions, always reviewed, always provenance-complete.
3. **Private local long tail**: every user’s real shelf, OCR package text, notes, photos, purchase places, and flavor profiles stay on-device.

The KPI should not be “catalog contains every tea.” It should be:

* user can add any tea in under a minute;
* common/famous teas autocomplete well;
* repeated misses become shared rows over time;
* no crawler, no account system, no PII, no ODbL contamination of the core catalog, no LLM-authored production rows.

The only constraint worth relaxing later is **vendor opt-in import under explicit license**. The constraints not worth relaxing are crawling commercial/community sites and publishing AI-authored rows as verified catalog data.

---

# 5–8 high-quality reference links

1. Wikidata dumps and license model: structured data under CC0; RDF and incremental dumps. ([wikidata.org][1])
2. AGROVOC license and maintenance: FAO-maintained, CC-BY-4.0, monthly releases, Russian and Chinese covered among FAO languages. ([FAOHome][5])
3. Open Food Facts reuse/API licensing: ODbL database, DbCL contents, CC-BY-SA caveats for images. ([wiki.openfoodfacts.org][4])
4. FoodData Central data types, update cadence, and CC0/public-domain status. ([fdc.nal.usda.gov][8])
5. FoodOn GitHub/license: CC-BY-4.0 food ontology. ([GitHub][7])
6. EFSA FoodEx2 and catalogue reuse/legal notice. ([European Food Safety Authority][9])
7. Camellia cultivar dataset and International Camellia Register caveat. ([bdj.pensoft.net][14])
8. Comparable catalog precedents: Untappd missing-item creation, Open Library CC0 contribution/bulk data, BoardGameGeek API registration. ([help.untappd.com][23])

[1]: https://www.wikidata.org/wiki/Wikidata%3ADatabase_download "Wikidata:Database download - Wikidata"
[2]: https://foundation.wikimedia.org/wiki/Policy%3ATerms_of_Use?utm_source=chatgpt.com "Wikimedia Foundation Terms of Use"
[3]: https://commons.wikimedia.org/wiki/Commons%3ALicensing?utm_source=chatgpt.com "Commons:Licensing"
[4]: https://wiki.openfoodfacts.org/Reusing_Open_Food_Facts_Data?utm_source=chatgpt.com "Reusing Open Food Facts Data"
[5]: https://www.fao.org/agrovoc/maintenance "Maintenance | AGROVOC"
[6]: https://lod.nal.usda.gov/nalt/en/?utm_source=chatgpt.com "NALT Full - NAL Agricultural Thesaurus - USDA"
[7]: https://github.com/foodontology/foodon "GitHub - FoodOntology/foodon: The core repository for the FOODON food ontology project. This holds the key classes of the ontology; larger files and the results of text-mining projects will be stored in other repos. · GitHub"
[8]: https://fdc.nal.usda.gov/ "USDA FoodData Central"
[9]: https://www.efsa.europa.eu/en/data/data-standardisation "Data standardisation | EFSA"
[10]: https://www.fao.org/contact-us/terms/db-terms-of-use/en/ "
	Statistical Database Terms of Use  | FAO | Food and Agriculture Organization of the United Nations
"
[11]: https://www.catalogueoflife.org/data/taxon/5RSSV "Camellia sinensis (L.) Kuntze | COL"
[12]: https://www.gbif.org/dataset/f382f0ce-323a-4091-bb9f-add557f3a9a2 "The World Checklist of Vascular Plants (WCVP)"
[13]: https://www.worldfloraonline.org/taxon/wfo-0000582676/?utm_source=chatgpt.com "Camellia sinensis (L.) Kuntze"
[14]: https://bdj.pensoft.net/article/61646/?utm_source=chatgpt.com "The Dataset of Camellia Cultivars Names in the World"
[15]: https://camellia.iflora.cn/ "International Camellia Register-Camellia,Cultivars,Species,Photos"
[16]: https://www.langual.org/?utm_source=chatgpt.com "LanguaL™ - an international framework for food description ..."
[17]: https://pmc.ncbi.nlm.nih.gov/articles/PMC6040205/?utm_source=chatgpt.com "FoodRepo: An Open Food Repository of Barcoded ... - PMC"
[18]: https://ngdc.cncb.ac.cn/databasecommons/database/id/4080 "
        TMDB - Database Commons
    "
[19]: https://www.chinesestandard.net/PDF/English.aspx/GBT30766-2014?srsltid=AfmBOook7T6LZSJ2qcC1XNwoAk0S1B7xNMO29lW6KOMV6JvBDi2votVo&utm_source=chatgpt.com "GB/T 30766-2014: Classification of tea (PDF English)"
[20]: https://www.iso.org/obp/ui/en/?utm_source=chatgpt.com "ISO 20715:2023(en), Tea — Classification of tea types"
[21]: https://docs.cntd.ru/document/1200111504?utm_source=chatgpt.com "ГОСТ 32593-2013 Чай и чайная продукция. Термины и ..."
[22]: https://www.wikidata.org/wiki/Q6097?utm_source=chatgpt.com "tea"
[23]: https://help.untappd.com/hc/en-us/articles/360034392751-Add-a-New-Beer-Brewery?utm_source=chatgpt.com "Add a New Beer/Brewery"
[24]: https://openlibrary.org/help/faq/using?utm_source=chatgpt.com "Using Open Library Data"
