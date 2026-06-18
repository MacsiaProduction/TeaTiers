# 16-catalog-breadth — compliant ways to grow the tea catalog to useful breadth (RU long tail) without web crawling, with the AI tier off

english text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** — a local-first Android tier-list app for teas (Kotlin + Jetpack Compose
client; Kotlin + Spring Boot backend; PostgreSQL; single Yandex Cloud VM; OCR sidecar). Users
create boards, rank teas into custom tiers, attach a purchase location (free-text or marketplace
URL), notes, photos, and a 0–5 flavor profile. **All user data lives on-device**; the backend is
a **read-only shared tea catalog** (+ a dormant on-demand enrichment endpoint). Tea names carry
**Russian, English, and Chinese (Hanzi + pinyin)**; the audience is **Russia-first**, distributed
via **RuStore + direct APK (no Google Play Services)**.

Hard constraints already locked by prior decisions — your answer MUST respect these:

- **No open-ended web crawling / scraping** to discover or enrich teas. We never mirror
  commercial or community sites (Steepster, RateTea, TeaDB, Moychay, Baidu, etc.). Confirmed
  legally + operationally (research run 08, decision #45): under our no-VPN / Yandex-native lock
  there is no compliant web-grounding path, and storing third-party search/scrape results is
  ToS-/license-blocked.
- **No arbitrary web images.** Catalog images are only curated CC/Wikimedia or the user's own
  photo (decision #24).
- **Per-row provenance is mandatory** (source, source_url, license, retrieved_at). We only mirror
  a redistributable open core: **Wikidata (CC0)**, **Wikipedia (CC-BY-SA, attributed)**, **Open
  Food Facts taxonomy (ODbL, kept isolated to avoid copyleft pollution)**.
- **The AI enrichment tier ships OFF for the MVP** (decision #88/#100): no LLM-authored rows in
  production for now. The free **Wikidata→Wikipedia resolve** path may be on.
- **Catalog today:** a hand-curated, own-authored, `verified` seed of ~100 teas (target ~300),
  deliberately biased toward the ru+en long tail; plus Wikidata-first `/resolve` on a miss.
- **No accounts / no server-side user data / no PII.** Any user-contribution mechanism must not
  break that (or must be explicitly scoped as a deliberate exception).

The core problem this run informs: **with crawling banned and the AI tier off, the only breadth
mechanisms in the MVP are hand-curation (≤300 teas) and Wikidata resolve.** ~300 teas is tiny
against the real RU long tail (a single vendor catalog is thousands of SKUs/blends/vendor names),
so the modal first experience for a user logging the teas they actually own is "the catalog
doesn't know my tea." We need a **compliant, low-ops, single-operator-sustainable** strategy to
grow catalog coverage — without scraping, without paid/always-on infra creep, and without
breaking the local-first/no-PII spine.

## Objective

Decide the **catalog-breadth strategy** for TeaTiers: how to grow the shared tea catalog from a
~300-tea curated seed to enough real-world coverage that a RU user's typical "add the tea I just
bought" search reliably hits — using only legally redistributable sources, minimal ops, and one
operator's sustainable effort.

## Questions

1. **Open structured datasets (beyond Wikidata/Wikipedia/OFF).** Enumerate every
   redistributable, openly-licensed dataset that materially covers teas — tea cultivars/types,
   Chinese/Russian/English tea names, GOST/GB-T classifications, geographic origins, vendor-neutral
   product taxonomies. For each: exact name, source/URL, **exact license** (and whether it permits
   storing + re-serving in our catalog with attribution), approximate coverage/row count,
   languages (does it have ru and/or zh names?), data quality, and update cadence. Explicitly flag
   datasets that look usable but are NOT redistributable. Include Wikidata SPARQL coverage limits
   for teas (how many tea entities actually have ru + zh labels + flavor-relevant properties).
2. **How far does Wikidata realistically get us?** Quantify it: roughly how many distinct tea
   entities exist in Wikidata under the relevant subtree, how many have Russian labels, how many
   have Chinese labels, and how many have useful structured metadata (type, origin, cultivar).
   Where is Wikidata weakest for our case (RU transliterations of Chinese teas? branded/blended
   supermarket teas? vendor SKUs)? Is a periodic Wikidata bulk sync worth it vs on-demand resolve?
3. **User-contribution model (no accounts).** Design options for letting users grow the shared
   catalog *without* introducing accounts or server-side PII: e.g. anonymous "suggest this
   tea / correct this row" submissions, the user's own custom teas optionally promoted to the
   shared catalog, an operator review queue. For each option: the privacy/PII implications (does
   it stay no-PII?), the moderation/abuse burden on a single operator, the data-quality risk, and
   the minimum backend surface needed. Which is the best fit for a one-operator, no-account app?
4. **Demand-driven curation ("seed from misses").** We can log, server-side, the
   *aggregate, name-string-only, no-PII* set of search/resolve queries that returned no result.
   How should we turn that miss-log into catalog growth — manual operator curation of the top-N
   misses, a tightly-capped operator-only batch enrichment run, or something else? What's the
   smallest pipeline (storage, review surface, promote-to-verified workflow) that makes this
   sustainable? How does this interact with the AI-tier-off posture (is a one-off operator-run
   enrichment batch over the top misses a compliant, cheap way to convert misses into permanent
   `verified` rows)?
5. **Reframe vs. grow.** Is the right answer partly *product framing* rather than data — i.e.
   position the catalog explicitly as a "famous-tea reference seed" and lean on the strong
   custom-add + OCR-grounded path for the long tail? What do comparable catalog-backed hobby apps
   (e.g. for coffee, wine, whisky, board games, books) do about long-tail coverage under similar
   constraints, and what's transferable?
6. **Recommended strategy + sequencing.** Given all the above and our constraints (no crawl, AI
   off, single 4 GB VM, one operator, RU-first, no-PII), recommend a concrete phased strategy:
   what to do first, what each phase buys in coverage, the ongoing operator effort, and the
   compliance posture of each step. Call out anything that would require relaxing a locked
   constraint (and whether it's worth it).

## Evidence standards

- Prefer maintained upstream source / official docs / the actual dataset license files over blog
  posts. Cite every dataset claim with a link + the license SPDX id (or exact license name) and
  whether redistribution-with-attribution is permitted.
- Pin dataset names/URLs/licenses exactly; **explicitly flag anything you are not certain exists
  or whose license you could not confirm** (do not assume "open").
- Cite every factual claim with a link and its publication/access date; prefer recent sources.
- Be concrete about Russian + Chinese coverage specifically — generic "it has tea data" is not
  enough.

## Return

1. A **dataset table**: name · URL · exact license · redistribute+re-serve? (Y/N/unclear) ·
   approx coverage · ru names? · zh names? · structured metadata · update cadence · notes.
2. A short **Wikidata coverage reality-check** (rough counts for tea entities with ru / zh labels
   + structured metadata; where it fails for us).
3. A **comparison of breadth strategies** (open-datasets · Wikidata bulk-sync · user-contribution
   (each variant) · demand-driven seed-from-misses · product reframe), scored on: coverage gain ·
   compliance/license risk · ops/operator burden · privacy (stays no-PII?) · data-quality risk.
4. A **recommended phased strategy** with sequencing and the ongoing operator effort per phase.
5. 5–8 high-quality reference links (dataset homepages/licenses + any directly comparable
   precedent).
