# Growing the TeaTiers Catalog Without Crawling and With AI Enrichment Off: A Compliance-First Breadth Strategy

## TL;DR
- The realistic answer is **reframe-plus-grow, not grow alone**: ship the curated ~300-tea seed as an explicit "famous-tea reference," lean hard on the custom-add + OCR path for the long tail, and bolt on two compliant growth engines — a periodic Wikidata/Wikipedia mirror (canonical tea types and named teas) and a no-PII, demand-driven "seed-from-misses" operator queue. No single open dataset, and not Wikidata, gets you to real SKU-level breadth.
- Open, redistributable datasets give you a strong **ontology/skeleton** (tea types, cultivars, origins, multilingual names) but **zero product/SKU coverage**: Wikidata (CC0), Wikipedia (CC-BY-SA), AGROVOC (CC-BY 4.0 for its ru+zh content), FoodOn (CC-BY-4.0), USDA FoodData Central (CC0), the Rospatent GI/NMPT open register (Russian open licence), and the EU eAmbrosia GI register are all legally re-servable with attribution; GS1 GPC and the GB/T and GOST standard texts are **not** redistributable.
- The compliant, one-operator-sustainable growth path is: aggregate, name-string-only, no-PII miss-logging → top-N misses promoted by hand (or via a tightly-capped, operator-run one-off enrichment batch) into permanent verified rows, plus an anonymous "suggest this tea" queue that the operator reviews. Avoid open-ended bulk imports of the OFF product table into the verified core (ODbL share-alike contamination risk); keep OFF isolated.

## Key Findings

1. **No open dataset solves the long tail.** Every redistributable source is taxonomy-shaped (types, cultivars, origins, standards vocabulary), not catalog-shaped (the thousands of branded/blended SKUs a real RU buyer owns). The breadth gap is structural, not a sourcing oversight. FoodOn states this explicitly about itself — it covers "a can of kidney beans – but not a can of 99% fat free black beans produced by a certain manufacturer" — confirming its taxonomy-not-catalog shape, which is true of the entire open-data field for teas.
2. **Wikidata is a backbone, not a catalog.** The Q6097 ("tea") instance/subclass tree is on the order of several hundred entities (an estimated ~400–600, up to ~1,000 including cultivars and all named teas) — good coverage of canonical types and famous (especially Chinese) teas, with high Chinese-label and moderate-to-high Russian-label coverage, but near-zero branded-product/SKU coverage and patchy RU transliterations (mostly in aliases). This sits inside a base of 122,072,697 Wikidata items (per Wikidata:Statistics, June 2026), so the tea slice is a vanishingly small, well-curated subset.
3. **A periodic Wikidata bulk sync is worth it** (a few hundred high-quality, multilingually-labelled, CC0 rows is a meaningful uplift over a 300-row seed and is cheap to refresh), but it should be **paired** with on-demand /resolve, not replace it.
4. **The demand-driven "seed-from-misses" loop is the highest-leverage growth engine** and is compliant if the miss-log stays aggregate and name-string-only. A one-off, operator-run enrichment batch over the top misses converts transient demand into permanent verified rows without shipping the always-on AI tier.
5. **Anonymous contribution is feasible without accounts or PII**, but pure-anonymous submissions carry a real spam/abuse burden. The OpenStreetMap precedent is instructive and cautionary: per the OSMF Privacy Policy, "we do not support anonymous contributions and retain additional, non-geographic, data on a legitimate interest basis (see GDPR article 6.1f), to enable… detecting, removing and correcting spam accounts, vandalism…"; OSM has barred anonymous edits since April 2009. TeaTiers is deliberately choosing the opposite trade-off (more spam, in exchange for no-PII), so it needs non-identifying spam mitigations.
6. **Comparable hobby apps converge on the same pattern**: a curated/authoritative core plus user-generated entries behind an admin approval queue, with explicit duplicate-merge tooling. BoardGameGeek, Goodreads/LibraryThing, Untappd and Vivino all live with the "my item isn't in the catalog" problem by letting users add and then moderating.

## Details

### 1. Open structured datasets (the dataset table)

| Dataset | URL | Exact license | Redistribute + re-serve? | Approx coverage | ru names? | zh names? | Structured metadata | Update cadence | Notes |
|---|---|---|---|---|---|---|---|---|---|
| **Wikidata** | wikidata.org | CC0 1.0 | **Yes** (public domain; no attribution legally required) | ~400–600 tea entities (up to ~1,000 with cultivars/named teas); 122,072,697 items total (Wikidata:Statistics, Jun 2026) | Partial (est. ~40–70% of tea items) | Partial (est. ~65–90% of tea items) | Yes: type, origin (P495/P17), cultivar, taxon links, identifiers | Continuous; weekly/daily dumps | Best backbone; SKUs excluded by notability policy. Pull altLabels for transliterations. |
| **Wikipedia** | wikipedia.org | CC-BY-SA 4.0 | **Yes, with attribution + share-alike on text** | Articles for major types/named teas in ru, en, zh | Yes (ruwiki) | Yes (zhwiki) | Prose, infoboxes | Continuous | Resolve target from Wikidata sitelinks. Attribute; keep text isolated from CC0 fields. |
| **Open Food Facts — taxonomy** (categories.txt) | github.com/openfoodfacts/openfoodfacts-server (taxonomies/food/categories.txt) | ODbL 1.0 (DbCL for contents) | **Yes, with attribution + share-alike** | Tea category branch + multilingual synonyms | Some | Some | Category hierarchy, synonyms | Continuous (GitHub) | Useful for category mapping/synonyms. **Share-alike**: keep isolated to avoid copyleft pollution of CC0 core. |
| **Open Food Facts — product DB** | world.openfoodfacts.org/data | ODbL 1.0 (images CC-BY-SA) | **Yes but copyleft** | 4 million products from 150 countries added by 25,000+ contributors (OFF GitHub org, Jun 2026), incl. branded teas | Some | Few | Brand, barcode, ingredients, images | Daily | Has actual branded SKUs, but ODbL share-alike + crowd-data quality risk. Do NOT merge into verified CC0 core; query in an isolated table only. |
| **AGROVOC** | fao.org/agrovoc | CC-BY 4.0 (FAO-language content: en, ru, fr, es, ar, zh) | **Yes, with attribution** | 41,341 concepts and 1,196,984 terms in up to 42 languages (FAO, Mar 2025 release); tea/Camellia subtree | **Yes (ru official lang)** | **Yes (zh official lang)** | SKOS thesaurus, multilingual labels, relations | Continuous (since July 2025) | Strong for ru+zh controlled vocabulary of tea terms; not product-level. |
| **FoodOn** | foodon.org / github.com/FoodOntology | CC-BY 4.0 | **Yes, with attribution** | "a set of over 9,600 generic food product categories" (FoodOn.org); ~9,445 classes in foodon_product_import.owl (Dooley et al., npj Science of Food 2018); tea terms | Limited | Limited | OBO ontology, Wikipedia xrefs | ~Quarterly (e.g., 2025-08-01) | Generic food categories; English-centric; explicitly excludes branded SKUs. Good for ontology alignment. |
| **USDA FoodData Central** | fdc.nal.usda.gov | CC0 1.0 (public domain; USDA requests attribution) | **Yes** | "over 300,000 foods including Foundation Foods, branded products, SR Legacy, and survey foods (FNDDS)" (USDA FDC API), incl. branded teas (US) | No | No | Nutrients, brand, UPC | Periodic | Public-domain branded data, but US-market and en-only; weak for RU long tail. |
| **Rospatent open GI/NMPT register** | rospatent.gov.ru/opendata/7730176088-nmpt | Rospatent Open Licence (free, perpetual, non-exclusive, no contract) | **Yes, per open licence terms** | All RU GIs/NMPTs incl. e.g. "Краснодарский чай", "Мацеста чай"; CSV (28 open datasets as of Apr 2026) | **Yes (ru)** | No | Name, region, holder | Periodic | Authoritative for Russian-origin tea GIs; small but high-trust. |
| **EU eAmbrosia GI register** | ec.europa.eu/agriculture/eambrosia; data.europa.eu | EU reuse (data.europa.eu, generally CC-BY 4.0 for the dataset — confirm distribution) | **Yes, with attribution (confirm dataset distribution licence)** | EU + third-country GIs incl. Darjeeling (2003), Ceylon (2007), Kangra (2005) | No | No | Name, type, country, specs | Continuous | Few tea GIs; China teas (Longjing etc.) not EU-registered. en-centric. |
| **GS1 GPC** | gpc-browser.gs1.org | GS1 proprietary terms | **No — not redistributable as a dataset** | Tea "brick" + attributes | Multilingual browser | Multilingual browser | Segment/Family/Class/Brick | Twice yearly | Use only the single tea brick code as an internal mapping label; do not mirror the schema. |
| **China GB/T tea standards** (GB/T 30766-2014 classification; GB/T 14456 green tea) | chinesestandard.net; gbstandards.org | Copyrighted national standards (DRM/paid) | **No** | 6-category classification (green/yellow/white/oolong/black/dark) | No | Yes (in text) | Definitions | Revised periodically | The *facts* (6 categories) are usable; the standard *text* is not redistributable. |
| **Russian GOST tea standards** (GOST 32593-2013 terms; GOST 32573-2013 black tea; GOST ISO 3720) | protect.gost.ru; allgosts.ru | Copyrighted (АО «Кодекс» holds rights on scans) | **No** | RU tea terminology/classification | **Yes (ru)** | No | Terms & definitions | Periodic | Use the RU terminology *facts* (e.g., listovoy/granulirovanny/pressovanny) as vocabulary; do not mirror the document. |
| **Tea cultivar / Camellia descriptors** (IPGRI Descriptors for tea) | cgspace.cgiar.org | CGIAR/Bioversity (varies; often CC-BY) | Unclear — confirm per item | Cultivar descriptor list | No | Some | Agronomic descriptors | Static (1997) | Reference for cultivar fields; not a names catalog. |
| **Kaggle/Zenodo/Mendeley tea datasets** (TeaLeafAgeQuality; black-tea fermentation Zenodo 4469326; "Tea FAO dataset") | kaggle.com; zenodo.org; data.mendeley.com | Mixed (CC0 / CC-BY / CC-BY-SA / unstated) | Case-by-case; several unclear | Image/agronomic datasets; FAO production stats | No | No | Images, fermentation params, crop tonnage | Static | **Not tea-name catalogs** — these are ML/agronomy datasets, not useful for product breadth. Flag as not redistributable unless license confirmed. |
| **DBpedia** | dbpedia.org | CC-BY-SA 3.0 | Yes, with attribution + share-alike | Derived from Wikipedia | Some | Some | RDF infobox triples | Periodic | Redundant with Wikidata/Wikipedia path; share-alike. |

**Explicitly flagged as NOT redistributable**: GS1 GPC schema, GB/T standard texts, GOST standard texts (the scanned documents). In all three, the underlying *facts* (the 6-category Chinese classification; the RU GOST tea terms) are not copyrightable and may be re-expressed in your own words, but you must not mirror the documents or schemas.

**Flagged as usable-but-dangerous**: the OFF *product* database (ODbL share-alike will "pollute" a CC0/curated core if merged) and OFF images (CC-BY-SA, plus third-party trademark/design rights on packaging — exactly the "no arbitrary web images" risk you already exclude).

### 2. Wikidata coverage reality-check

Running `SELECT (COUNT(DISTINCT ?item) AS ?count) WHERE { ?item wdt:P31/wdt:P279* wd:Q6097. }` returns on the order of **several hundred entities** (estimated ~400–600; up to ~1,000 if you union in Camellia sinensis (Q101815) cultivars, which are modeled as taxa outside the Q6097 beverage tree, plus all named Chinese/Japanese/Taiwanese teas). This is **not** thousands.

Of that set, **Chinese (zh) labels are present on an estimated ~65–90%** (the named-tea portion — Longjing, Tieguanyin, pu-erh, etc. — is Chinese by origin and almost always carries a zh label), and **Russian (ru) labels on an estimated ~40–70%** (prominent types and famous teas are covered; the long tail of minor cultivars and niche named teas thins out). English labels are near-universal. These percentages should be confirmed by running the two label-filtered queries:
- ru: add `?item rdfs:label ?l. FILTER(LANG(?l)="ru")`
- zh: add `?item rdfs:label ?l. FILTER(LANG(?l)="zh")`

For a broader net that also captures cultivars (which sit in the taxonomy tree as taxa, not under the beverage Q6097):
```sparql
SELECT (COUNT(DISTINCT ?item) AS ?count) WHERE {
  { ?item wdt:P31/wdt:P279* wd:Q6097. }
  UNION { ?item wdt:P279* wd:Q6097. }
  UNION { ?item wdt:P31 wd:Q4886; (wdt:P171|wdt:P279)* wd:Q101815. }
}
```
To capture transliterations, swap `rdfs:label` for `skos:altLabel`.

**Where Wikidata fails for TeaTiers**: (a) vendor SKUs — effectively zero (excluded by Wikidata:Notability); (b) branded/supermarket teas — very sparse, brand-level at best (Lipton, Twinings, Tetley as companies, not their product lines), not product-level; (c) RU transliterations of Chinese teas — present only inconsistently and mostly as `skos:altLabel` aliases (competing romanizations: Palladius vs Western; pu-erh/puer/puerh; Tieguanyin/Tie Guan Yin), so a sync keyed on `rdfs:label`@ru alone will miss them. Mitigation: also pull `skos:altLabel`@ru and @zh.

**Bulk sync vs on-demand resolve**: do both. A periodic full mirror of the ~few-hundred tea entities (with ru/zh labels + aliases + origin/type/cultivar) is small, CC0, and turns the canonical-tea experience instant and offline-friendly; on-demand /resolve then catches anything missed and anything newly created upstream. The marginal cost of the bulk sync is trivial on a 4GB VM.

### 3. User-contribution model without accounts or PII

The constraint is hard: no accounts, no server-side user data, no PII. Options:

- **(A) Anonymous "suggest this tea / correct this row" → operator review queue.** A user submits a name (ru/en/zh), optional type/origin, optional free-text note. Server stores only the submission content + timestamp — **no IP, no device ID, no account**. Operator reviews and promotes good rows to the verified core. *Privacy*: stays no-PII if you deliberately do **not** log IP/identifiers — note that OSM's own privacy policy takes the opposite stance ("we do not support anonymous contributions and retain additional, non-geographic, data on a legitimate interest basis… to enable… detecting, removing and correcting spam accounts, vandalism…"), and OSM has barred anonymous edits since April 2009. You are choosing the opposite trade-off: accepting more spam to stay no-PII. *Moderation burden*: moderate-to-high if spam arrives. *Data-quality risk*: medium (unverified strings). *Backend surface*: one POST endpoint + one moderation view. **Best fit for TeaTiers.**
- **(B) User's own custom teas optionally promoted to shared catalog.** The local-first custom-add a user already makes can carry an opt-in "share this tea name (no personal data) to help others" flag that ships only the name/type/origin string. *Privacy*: no-PII if only the tea descriptor is sent. *Burden*: same review queue as (A). *Quality*: same. This is essentially (A) sourced from the natural add-flow — the lowest-friction variant and the recommended primary mechanism.
- **(C) Open contribution with auto-publish (no review).** Rejected: incompatible with one-operator moderation and with verified-provenance.

**Spam/abuse mitigation that stays no-PII**: server-side rate-limiting by coarse bucket (e.g., per-app-instance ephemeral token that is not personal data), submission size/character-set validation, a client-side proof-of-work or app-attestation gate, dedup against existing rows, and a hard cap on queue size. All moderation is human and batched.

**152-FZ and GDPR**: a free-text tea name plus timestamp, with no identifiers, is not personal data under either regime; pseudonymous data (hashed device IDs, IPs) **is** still personal data under GDPR and would pull you into scope and, under 152-FZ, into Russian data-localization obligations. The design rule: store the *content* of the suggestion and nothing that identifies the *submitter*. If you must rate-limit, prefer non-personal ephemeral tokens or differential/aggregate counters over storing IPs.

### 4. Demand-driven curation ("seed from misses")

This is the growth engine that directly fixes "the catalog doesn't know my tea." Mechanism:
- **Log, server-side, only the aggregate, name-string-only, no-PII set of search/resolve queries that returned no result** — i.e., store the normalized query string + a count, not who searched, not when-per-user, not IP. A `(query_string, miss_count, first_seen, last_seen)` table is sufficient.
- **Weekly, the operator reviews the top-N misses** (sorted by count). High-frequency real teas get hand-curated into verified rows (often resolvable via Wikidata/Wikipedia; if not, authored from the operator's own knowledge with provenance = self-authored/verified).
- **Optionally, a tightly-capped, operator-run one-off enrichment batch**: even with the AI tier shipped OFF in production, the operator can run an *offline*, human-supervised enrichment pass over, say, the top 100–300 misses, review every output, and promote only verified rows. This is compliant because (a) no LLM-authored rows are auto-published, (b) every row is operator-reviewed before becoming verified, and (c) it is a one-off local job, not always-on infra. It is the cheapest way to convert a backlog of misses into permanent rows.

**Smallest sustainable pipeline**: one miss-log table → one sorted "top misses" admin view → one "promote to verified" action that writes a row with full provenance. No new services; fits the existing read-only backend + dormant enrichment endpoint.

**Privacy of query logging**: search strings *can* contain personal data if users type identifying text, but tea-name queries are low-risk; keep it aggregate (counts, not per-event), strip anything that isn't a plausible tea name on ingest, never associate with a session/user, and set a retention cap. Aggregated, non-attributable counts fall outside 152-FZ/GDPR personal-data scope. Document the rationale.

### 5. Reframe vs grow — what comparable apps do

The honest product truth: **a 300–1,000-row catalog will never match a multi-thousand-SKU vendor reality, so the catalog should be positioned as a curated reference, and the long tail should be carried by the custom-add + OCR path** — exactly what you already have. Precedents:

- **BoardGameGeek**: every game is a user submission, but each passes through admin/"GeekMod" approval **queues** (separate pending queues for games, versions, people, files). Approval latency is real (users report waits of days to weeks). Lesson: a moderated submission queue scales to a huge catalog but creates a moderation backlog — size your operator effort accordingly and cap intake.
- **Goodreads / LibraryThing**: users freely add books; the hard problem is **duplicates**, solved with explicit combine/merge tooling and a "Combiners"/Librarians group. Goodreads transfers all ratings/reviews to the canonical edition on merge. Lesson: you will get duplicate tea rows; build a merge action from day one, and keep user data local so a server-side merge never touches user data.
- **Untappd (beer) / Vivino (wine)**: massive user-generated catalogs with label-photo scanning as the on-ramp; both accept that the catalog is crowd-grown and imperfect. Lesson: photo/label capture (your OCR path) is the proven cold-start answer for "my item isn't here."
- **CellarTracker (wine)**: community-maintained catalog with a single small operator for years — evidence a one-operator, contribution-fed catalog is sustainable if moderation is batched and tooling is good.

Transferable design: curated core + anonymous suggestion queue + OCR capture + duplicate-merge + "reference, not registry" framing.

### 6. Recommended phased strategy and sequencing

**Phase 0 — Reframe (immediate, ~0 ops).** In-product copy positions the catalog as a "famous-tea reference seed." The custom-add flow is the hero path: when a search misses, the very next tap is "add your tea" (name in ru/en/zh, optional type/origin, photo via OCR, all stored on-device). This alone removes the "dead end" feeling. *Coverage buys*: perceived 100% (any tea can be logged locally). *Compliance*: fully within local-first/no-PII.

**Phase 1 — Wikidata/Wikipedia mirror + resolve (1–2 days build, then trivial upkeep).** Bulk-mirror the ~few-hundred tea entities (Q6097 tree + Camellia cultivars + named teas), pulling ru/zh labels **and aliases**, type, origin, cultivar, with per-row provenance (source=Wikidata, license=CC0, retrieved_at). Keep on-demand /resolve on for misses. *Coverage buys*: roughly +300–700 canonical, multilingually-labelled verified rows over the seed. *Compliance*: CC0, clean. Refresh quarterly.

**Phase 2 — Demand-driven miss-log + operator curation (a few hours/week).** Turn on aggregate, name-string-only, no-PII miss logging. Weekly, promote top misses to verified rows (Wikidata-resolvable first; self-authored otherwise). *Coverage buys*: continuously closes the gap exactly where real users are searching — the highest-yield rows per unit effort. *Compliance*: aggregate logging, documented retention.

**Phase 3 — Anonymous suggestion queue (a few hours/week, shared with Phase 2).** Add the opt-in "share this tea (no personal data)" flag on custom-add and a standalone "suggest/correct" submission, feeding the same review queue. Add rate-limiting (non-personal tokens), dedup, and a merge action. *Coverage buys*: crowd-sourced long-tail names the operator wouldn't think of, especially RU regional and vendor-blend names. *Compliance*: no accounts, no PII; accept higher spam in exchange.

**Phase 4 (optional, only if backlog justifies) — One-off operator-run enrichment batch.** Offline, human-reviewed enrichment over the top accumulated misses, promoting only verified rows. *Coverage buys*: a step-change clearing of a large miss backlog. *Compliance*: no auto-published LLM rows; one-off, not always-on — does **not** require flipping the "AI tier ships OFF" production posture.

**What would require relaxing a locked constraint** — and the verdict:
- Mirroring the OFF *product* table to get real branded SKUs would import thousands of branded teas but pulls ODbL share-alike into your core and crowd-data quality risk. *Verdict*: keep OFF isolated/queried-only; not worth contaminating the CC0 verified core.
- Storing IPs/device IDs to fight spam would make moderation easier but breaks no-PII and triggers 152-FZ/GDPR. *Verdict*: not worth it; use non-personal rate-limiting.
- Shipping the always-on AI enrichment tier would maximize breadth but breaks the "AI off" posture and adds infra cost/ops. *Verdict*: not for MVP; the one-off operator batch (Phase 4) captures most of the value compliantly.

## Recommendations

1. **Ship the reframe now** (Phase 0). It is free and fixes the modal first experience immediately by making custom-add + OCR the hero, not a fallback.
2. **Build the Wikidata mirror next** (Phase 1), pulling aliases as well as labels, with strict per-row provenance. This is your single best one-time coverage uplift and it is CC0-clean.
3. **Stand up aggregate, no-PII miss-logging and a weekly top-misses review** (Phase 2). This is the highest-yield ongoing engine; budget a few hours/week.
4. **Add the anonymous, account-less suggestion queue with a merge tool** (Phase 3) once the review habit exists. Accept more spam to stay no-PII; mitigate with non-personal rate-limiting, dedup, and size caps.
5. **Reserve the one-off operator-run enrichment batch** (Phase 4) for when the miss backlog is large enough to justify it; review every row before promotion so the "AI off" production posture is preserved.
6. **Keep OFF taxonomy (for synonyms/category mapping) and the OFF product table strictly isolated** from the CC0/curated verified core to avoid ODbL share-alike contamination. Use AGROVOC (CC-BY 4.0 for its ru+zh content) for the controlled vocabulary of tea terms, and the Rospatent open GI/NMPT register for authoritative Russian-origin tea GIs.
7. **Do not mirror GS1 GPC, GB/T, or GOST documents.** Re-express only the non-copyrightable classification facts (the 6-category Chinese system; RU GOST tea terms) in your own words, with a citation, if you want classification fields.

**Benchmarks that would change the plan**: if weekly unique misses exceed what a few hours can curate, escalate to the Phase 4 batch. If anonymous-queue spam exceeds the operator's review capacity, gate submissions behind app-attestation or temporarily switch to the opt-in custom-add-promotion variant only. If a future, confirmed CC0/CC-BY dataset of RU vendor tea names appears (none exists today), re-evaluate a bulk import.

## Breadth-strategy comparison (scored)

| Strategy | Coverage gain | Compliance/license risk | Ops/operator burden | Stays no-PII? | Data-quality risk |
|---|---|---|---|---|---|
| Open structured datasets (Wikidata/Wikipedia/AGROVOC/FoodOn/GI registers) | Medium (canonical types/named teas; +300–700 rows) | Low (all CC0/CC-BY; keep ODbL/OFF isolated) | Low (one-time + quarterly) | Yes | Low (authoritative) |
| Wikidata bulk-sync | Medium | Very low (CC0) | Very low | Yes | Low |
| User-contribution: anonymous suggest→review (A) | Medium-high (long tail) | Low | Medium-high (moderation) | Yes (no identifiers stored) | Medium |
| User-contribution: opt-in promote own custom (B) | Medium-high | Low | Medium | Yes | Medium |
| User-contribution: auto-publish (C) | High | Low | Unmanageable for 1 op | Yes | High — rejected |
| Demand-driven seed-from-misses | High (targets real demand) | Low (aggregate, no-PII) | Low-medium (weekly) | Yes | Low (operator-verified) |
| Product reframe (reference + OCR/custom-add) | Perceived 100% locally | None | Near-zero | Yes | N/A (user-owned) |

## Caveats
- **The Wikidata tea counts and the ru/zh label percentages are evidence-based estimates, not measured values** — live SPARQL could not be executed (WDQS is cache-only to automated fetchers). Run the provided queries at query.wikidata.org to get exact numbers before sizing Phase 1; the magnitude (several hundred, not thousands) is reliable, the exact figure is not.
- **Several licenses need a final eyes-on confirmation against the upstream license file at integration time**: the eAmbrosia dataset distribution licence on data.europa.eu, the exact Rospatent open-licence text, and any Kaggle/Zenodo/Mendeley tea dataset you might touch (many are CC-BY-SA or unstated — treat as non-redistributable until confirmed).
- **ODbL is share-alike (copyleft for databases).** Any database you publicly distribute that is "produced from" an ODbL source must itself be offered under ODbL — this is the specific reason to keep OFF isolated from your CC0 verified core.
- **Anonymous contribution trades privacy for spam.** The OSM experience shows truly anonymous submission channels attract persistent bot-spam that is hard to stop without identifiers (which is why OSM disallows anonymous edits); your no-PII stance is a deliberate, defensible choice but it front-loads moderation effort onto the single operator.
- Some sources cited for tea-trade figures (e.g., counterfeiting/volume claims about Darjeeling and Longjing) come from a commercial blog and are illustrative, not authoritative; the GI *registrations* themselves are confirmed via eAmbrosia and Rospatent.

## High-quality reference links
- Wikidata "tea" (Q6097) and Camellia sinensis (Q101815): wikidata.org/wiki/Q6097 ; wikidata.org/wiki/Q101815 (CC0).
- Open Food Facts terms of use & data (ODbL): world.openfoodfacts.org/terms-of-use ; world.openfoodfacts.org/data.
- AGROVOC access & license (CC-BY 4.0 for FAO-language content): fao.org/agrovoc/access ; SPARQL at agrovoc.fao.org/sparql.
- FoodOn (CC-BY 4.0): foodon.org ; obofoundry.org/ontology/foodon.html.
- USDA FoodData Central (CC0) & download/API: fdc.nal.usda.gov ; fdc.nal.usda.gov/download-datasets.
- Rospatent open GI/NMPT register (Russian open licence): rospatent.gov.ru/opendata/7730176088-nmpt ; EU eAmbrosia GI register: ec.europa.eu/agriculture/eambrosia.
- Comparable precedent — BoardGameGeek submission/approval queues: boardgamegeek.com/wiki/page/BGG_Pending_Queues_for_Users ; Goodreads merge/combine manual: help.goodreads.com/s/article/Librarian-Manual-How-to-combine-editions.
- OpenStreetMap Foundation Privacy Policy (anonymous-edit / spam stance): osmfoundation.org/wiki/Privacy_Policy.