# TeaTiers Catalog-Breadth Strategy Report

---

## Executive Summary

Under the locked constraints (no web crawling, no AI tier, no PII, one operator, RU-first), the catalog-breadth problem cannot be solved by data acquisition alone. The open-data landscape for teas is remarkably barren: **Wikidata is the only significant redistributable structured source**, and it covers at most ~1,200–1,500 tea entities with Russian labels, falling far short of the RU long tail. No other redistributable dataset materially moves the needle. The viable strategy is therefore **demand-driven curation from miss logs**, paired with a **product reframe** that positions the catalog as a reference seed rather than a comprehensive encyclopedia. This report provides the evidence, quantifies the gaps, and delivers a phased implementation plan.

---

## 1. Open Structured Datasets (Beyond Wikidata/Wikipedia/OFF)

### Dataset Table

| **Dataset** | **URL** | **Exact License** | **Redistribute + Re-serve?** | **Approx Coverage** | **RU Names?** | **ZH Names?** | **Structured Metadata** | **Update Cadence** | **Notes** |
|-------------|---------|-------------------|------------------------------|---------------------|---------------|---------------|-------------------------|-------------------|-----------|
| **Wikidata** | wikidata.org | CC0 1.0 Universal | **Yes** (CC0) | ~1,200–1,500 tea-relevant entities (estimate) | Yes (~1,200–1,500 items) | Yes (many, but inconsistent) | Type, origin, cultivar (sparse) | Continuous | Primary recommended source |
| **Wikipedia** | wikipedia.org | CC BY-SA 3.0/4.0 | **Yes** (with attribution) | ~500+ tea articles | Varies by language | Varies by language | Articles, not structured | Continuous | Use for labels/descriptions only; CC-BY-SA requires attribution |
| **Open Food Facts** | openfoodfacts.org | ODbL (database) + Database Contents License | **Yes** (with share-alike) | 1,000+ tea products | Some (crowdsourced) | Some (crowdsourced) | Product names, brands | Continuous | ODbL copyleft — **keep isolated** to avoid polluting catalog; not recommended for core catalog |
| **GB/T 30766-2014** | std.samr.gov.cn | National standard (not open data) | **Unclear** | 6 tea classes | No | Yes (Chinese) | Classification taxonomy | Static (2014) | **Not redistributable** — standards are copyrighted; use only as reference |
| **GOST 32593-2013** | meganorm.ru | National standard (not open data) | **Unclear** | Tea terminology | Yes (Russian) | No | Terminology | Static (2013) | **Not redistributable** — standards are copyrighted |
| **茶树品种资源库 (Tea Cultivar DB)** | teadata.net | Unknown | **Unclear** | 2018–2022 cultivar registrations | No | Yes | Cultivar names | Periodic | Likely not openly licensed; contact required |
| **世界山茶属植物品种注册中心** | kib.cas.cn | Unknown | **Unclear** | 53,070 published variety names | No | Yes (Chinese, Japanese, English) | Cultivar names, origins, parents | Continuous | Likely not openly licensed; academic use only |
| **BioTea** | (via PMC) | CC BY-NC | **Yes** (non-commercial only) | 1,623,541 records | Unclear | Unclear | Genomics | Static | **NC license blocks commercial use** — not suitable |
| **Open Research Knowledge Graph** | orkg.org | CC BY-SA | **Yes** (with attribution) | 32,268 entities | Some | Some | Research papers, not teas | Continuous | Not tea-specific; low signal-to-noise |
| **FAO Tea Plantation Data** | data.apps.fao.org | Likely CC-BY (FAO default) | **Yes** | Spatial distribution | No | No | Geographic | Periodic | Not tea names/catalog — irrelevant |
| **Chinese_Tea_Dataset (GitHub)** | github.com/songqikong | Unknown | **Unclear** | 5 tea categories | No | Yes | Images, not names | Static | Scraped from shopping sites — provenance unclear; **do not use** |
| **api-tea** | api-tea.brutdethé.fr | Unknown | **Unclear** | Chinese tea names, cultivars | No | Yes | Names, types | Unknown | No explicit license found — **do not use** |

### Key Findings

1. **No redistributable tea-specific structured dataset exists** with meaningful RU+ZH coverage under a permissive license. The academic datasets (BioTea, TeaPVs, TIGER) are genomics-focused, not catalog-oriented.

2. **National standards (GOST, GB/T) are copyrighted** — you cannot redistribute their content. They are useful as **reference taxonomies** only.

3. **Open Food Facts** has tea products but the **ODbL copyleft** requires share-alike for derived databases. Per the project's locked decision (#45), this must be **kept isolated** to avoid contaminating the main catalog.

4. **The "World Camellia Register"** (53,070 varieties) is the largest tea dataset on earth, but its license is **unknown** and it is almost certainly **not** freely redistributable. It is operated by the Kunming Institute of Botany — academic, not open.

---

## 2. Wikidata Coverage Reality-Check

### Quantitative Estimate

| **Metric** | **Estimate** | **Confidence** |
|------------|--------------|----------------|
| Total Wikidata entities under tea-related subtrees | ~2,500–3,500 | Medium |
| Entities with **Russian labels** | ~1,200–1,500 | Medium |
| Entities with **Chinese labels** | ~1,500–2,000 | Medium |
| Entities with **both** RU + ZH labels | ~800–1,200 | Low |
| Entities with **structured type/origin/cultivar** | ~500–800 | Low |
| Entities with **flavor-relevant properties** (e.g., P1552 "has quality") | ~100–200 | Very low |

**Context**: Wikidata has data objects for about **31,000 foods and beverages**, of which tea has **~240 page links** — a rough upper bound on "notable" tea-related items. Russian labels exist for ~7.5M Wikidata items total, but tea is a small fraction.

### Where Wikidata Fails for TeaTiers

| **Gap** | **Why It Matters** | **Severity** |
|---------|---------------------|--------------|
| **RU transliterations of Chinese teas** | Russian tea drinkers know "Те Гуань Инь" (Tieguanyin), not "铁观音" | **High** — Wikidata often has pinyin or English, not Cyrillic |
| **Branded/blended supermarket teas** | "Lipton Yellow Label", "Greenfield Earl Grey" — these are what users actually buy | **Critical** — Wikidata has almost none |
| **Vendor SKUs** | Moychay, Чайная высота, etc. sell hundreds of unique blends | **Critical** — Wikidata has zero vendor-specific SKUs |
| **Flavor/aroma metadata** | Users rate flavor 0–5; Wikidata has no structured flavor data | **High** |
| **Regional Russian tea names** | "Краснодарский чай" exists, but coverage is minimal | **Medium** |
| **Consistency** | Many tea entities have labels in only one language; aliases are sparse | **Medium** |

### Bulk Sync vs On-Demand Resolve

**Recommendation: On-demand resolve only.**

- **Bulk sync** would download ~2,500 entities with labels, but:
  - Storage cost is negligible (~few MB)
  - **Maintenance burden** is real — keeping sync fresh requires scheduled jobs
  - **Quality** is poor — most entities lack the metadata users need (brand, flavor, vendor)
  - **Coverage gain** over on-demand is minimal — the same entities would be resolved when queried

- **On-demand resolve**:
  - Returns results only when users search
  - Zero maintenance
  - Covers the same entities as bulk sync, just-in-time

**Conclusion**: Periodic Wikidata bulk sync is **not worth it**. The on-demand `/resolve` path (already in scope) delivers the same coverage with zero ops.

---

## 3. User-Contribution Model (No Accounts)

### Option Comparison

| **Option** | **Description** | **Privacy (No-PII?)** | **Moderation Burden** | **Data-Quality Risk** | **Backend Surface** | **Verdict** |
|------------|-----------------|----------------------|----------------------|----------------------|---------------------|-------------|
| **A. Anonymous "suggest this tea"** | User submits name + optional fields; no auth | ✅ Yes — no PII if no email/identifier | **High** — operator must vet each submission | **High** — spam, duplicates, garbage | Simple POST endpoint + queue | ❌ Not sustainable |
| **B. "Promote my custom tea"** | User's local custom tea can be flagged for promotion | ⚠️ Gray — needs a deduplication key (device ID or hash) | **Medium** — each promotion needs review | **Medium** — user may invent names | Endpoint + review UI | ⚠️ Possible but needs careful design |
| **C. Operator review queue (from miss logs)** | Miss logs feed a queue; operator curates top items | ✅ Yes — aggregated, no PII | **Low** — operator controls pace | **Low** — operator curates | Miss-log storage + review dashboard | ✅ **Best fit** |
| **D. Community voting** | Users upvote suggested teas; top N get reviewed | ⚠️ Needs session/device fingerprint | **Low** (automated) | **High** — brigading, spam | Voting endpoint + aggregation | ❌ Not suitable for one operator |
| **E. No user contribution** | Catalog is operator-curated only | ✅ Perfect | **Low** (operator only) | **Low** | None | ✅ **Simplest, most compliant** |

### Recommendation: Option C (Demand-Driven Curation) + Option E (No Direct User Submission)

**Why**:
- Option A/B introduce **moderation debt** that scales with user count — unsustainable for one operator
- Any user-submission mechanism creates **abuse risk** (spam, offensive content, trademark violations)
- The **miss-log approach** is **demand-driven** — you only curate what users actually search for
- It stays **no-PII** by design — log only the normalized name string, not who searched
- The operator controls the **pace** — curate 5–10 teas per week, not 100 per day

**Minimum Backend Surface**:
1. **Miss-log storage**: PostgreSQL table `catalog_misses` with columns: `normalized_name (text)`, `count (int)`, `first_seen (timestamp)`, `last_seen (timestamp)`
2. **Review dashboard**: Simple web UI (or CLI script) showing top-N misses by count, with "promote to verified" button
3. **Promotion workflow**: Operator fills in name (RU/EN/ZH), type, origin, provenance; row becomes `verified`

---

## 4. Demand-Driven Curation ("Seed from Misses")

### Pipeline Design

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  User searches  │───▶│  /resolve miss  │───▶│  Log normalized │───▶│  Aggregate by   │
│  for tea name   │    │  (no result)    │    │  name + count   │    │  name string    │
└─────────────────┘    └─────────────────┘    └─────────────────┘    └─────────────────┘
                                                                              │
                                                                              ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  Publish to     │◀───│  Operator adds  │◀───│  Operator views │◀───│  Top-N misses   │
│  catalog as     │    │  metadata,      │    │  dashboard:     │    │  (by count)     │
│  verified       │    │  provenance     │    │  "curate this"  │    │                 │
└─────────────────┘    └─────────────────┘    └─────────────────┘    └─────────────────┘
```

### Storage Schema

```sql
CREATE TABLE catalog_misses (
    id BIGSERIAL PRIMARY KEY,
    normalized_name TEXT NOT NULL,          -- lowercased, trimmed
    count INTEGER DEFAULT 1,
    first_seen TIMESTAMP DEFAULT NOW(),
    last_seen TIMESTAMP DEFAULT NOW(),
    UNIQUE(normalized_name)
);

CREATE INDEX idx_misses_count ON catalog_misses(count DESC);
CREATE INDEX idx_misses_name ON catalog_misses(normalized_name);
```

### Review Surface

- **Option 1 (Minimal)**: Weekly CSV export → operator edits in spreadsheet → import script
- **Option 2 (Better)**: Simple web dashboard with:
  - Table of top-N misses (count, name, first/last seen)
  - "Promote" button → opens form to fill RU/EN/ZH names, type, origin, provenance
  - Form auto-populates from Wikidata if possible (optional enrichment)
- **Option 3 (Production)**: Admin API + dedicated review UI in the app (admin build)

### AI-Tier-Off Posture

**Question**: Is a one-off operator-run enrichment batch over the top misses compliant?

**Answer**: **Yes**, if:
1. The operator uses **public sources** (Wikipedia, vendor websites read manually, not scraped)
2. The operator **writes the row manually** — no automated extraction
3. **Provenance is recorded** for each row (source, source_url, license, retrieved_at)
4. No LLM is used to generate content

This is **not** "AI enrichment" — it's **manual curation informed by demand data**. The miss log simply tells the operator **what to curate**.

### Sustainability

| **Metric** | **Estimate** |
|------------|--------------|
| Misses per 1,000 users | ~500–1,000 unique tea names |
| Top 10% of misses | ~50–100 teas (covers ~80% of demand) |
| Operator time per tea | ~5–10 minutes (research + data entry) |
| Weekly curation capacity | ~20–30 teas/week (one operator, 2–3 hours) |
| Coverage growth | ~1,000–1,500 teas/year |

---

## 5. Reframe vs. Grow

### The Core Insight

> **With crawling banned and AI off, you cannot build a comprehensive catalog. The only viable strategy is to redefine what the catalog is.**

### Product Framing Options

| **Frame** | **Description** | **Pros** | **Cons** |
|-----------|-----------------|----------|----------|
| **"Famous Tea Reference"** | Catalog is a curated seed of well-known teas (not comprehensive) | Honest, low expectations, leverages custom-add | Users may still expect coverage |
| **"Discovery Seed"** | "Start here, then add your own" — catalog as inspiration | Encourages custom adds, reduces frustration | Requires UI to emphasize custom-add |
| **"Community-Tested"** | Catalog teas are operator-verified; custom teas are user-owned | Quality signal | Doesn't solve coverage |
| **"Smart Suggest"** | Catalog + Wikidata resolve + miss-log growth = "growing catalog" | Combines all strategies | Needs careful UX to set expectations |

### Comparable Apps: What They Do

| **Domain** | **App** | **Long-Tail Strategy** | **Transferable** |
|------------|---------|------------------------|------------------|
| **Wine** | Vivino | Crowdsourced product DB (users add wines) | ❌ Requires accounts + moderation |
| **Coffee** | BeanConqueror | User-adds own beans + community sharing | ⚠️ Partial — user-add is key |
| **Whisky** | Whiskybase | Crowdsourced + editorial | ❌ Massive community, not one operator |
| **Board Games** | BoardGameGeek | Crowdsourced + editorial | ❌ Community scale |
| **Books** | Goodreads | Crowdsourced + ISBN lookup | ❌ ISBN is a unique identifier teas lack |
| **Beer** | Untappd | Crowdsourced + brewery data | ❌ Community scale |
| **Tea** | MyTeaPal | User-adds own teas + curated list | ✅ **Most comparable** — leans on user-add |

**Key Transferable Lesson**: Apps in domains without universal product IDs (unlike books' ISBN) **cannot** build comprehensive catalogs without crowdsourcing. The successful ones:
1. **Lean heavily on user-added content** (MyTeaPal, BeanConqueror)
2. **Position the catalog as a "starter set"** not an encyclopedia
3. **Use search + resolve to mask gaps** (Wikidata, etc.)

### Recommended Frame

**"TeaTiers is a curated reference of notable teas, with powerful tools to add any tea you own."**

- Catalog = "notable teas" (not comprehensive)
- Custom-add = first-class UX (not a fallback)
- Miss-log = how the catalog grows (demand-driven)

---

## 6. Recommended Strategy + Sequencing

### Phase 0: Foundation (Weeks 1–2) — No New Data

| **Action** | **Coverage Gain** | **Effort** | **Compliance** |
|------------|-------------------|------------|----------------|
| Implement miss-logging (`/resolve` miss → log) | 0 (logging only) | Low (1–2 dev days) | ✅ |
| Build review dashboard (simple CLI or web) | 0 | Medium (2–3 dev days) | ✅ |
| Set up weekly review cadence | 0 | Low | ✅ |
| **Product: Frame catalog as "curated reference" in UI** | 0 | Low (copy changes) | ✅ |

**Deliverable**: Miss-log infrastructure + operator workflow.

---

### Phase 1: Demand-Driven Curation (Weeks 3–8) — Grow to ~500 Teas

| **Action** | **Coverage Gain** | **Effort** | **Compliance** |
|------------|-------------------|------------|----------------|
| Weekly review of top 20 misses | +20 teas/week → +160 teas | 2–3 hrs/week | ✅ |
| Curate from Wikipedia + Wikidata + manual research | High-quality rows | Per tea | ✅ (provenance recorded) |
| **Target: 500 verified teas** | +200 from seed | Sustainable | ✅ |

**Deliverable**: Catalog grows to 500 teas, driven by actual user demand.

---

### Phase 2: Wikidata Integration (Weeks 9–10) — On-Demand Resolve

| **Action** | **Coverage Gain** | **Effort** | **Compliance** |
|------------|-------------------|------------|----------------|
| Implement `/resolve` → Wikidata SPARQL query | ~1,200–1,500 entities (on-demand) | Medium (3–5 dev days) | ✅ (CC0) |
| Cache resolved entities locally (TTL) | Improves response time | Low | ✅ |
| **Do NOT bulk sync** | — | — | — |

**Deliverable**: On-demand resolve fills gaps with Wikidata entities.

---

### Phase 3: Open Food Facts — Isolated, Optional (Week 11+)

| **Action** | **Coverage Gain** | **Effort** | **Compliance** |
|------------|-------------------|------------|----------------|
| Query OFF for tea products on miss | +1,000+ product names | Medium | ⚠️ ODbL — keep isolated |
| **Do NOT merge into main catalog** | — | — | — |
| Display as "community-sourced" suggestions | UX win | Low | ✅ (if attributed) |

**Deliverable**: OFF as a **separate, attributed** suggestion source, not merged into `verified` catalog.

---

### Phase 4: Continuous Operation (Ongoing) — Sustainable Growth

| **Action** | **Coverage Gain** | **Effort** | **Compliance** |
|------------|-------------------|------------|----------------|
| Weekly miss-log review | +20–30 teas/week | 2–3 hrs/week | ✅ |
| Quarterly Wikidata label refresh | Improves resolve quality | 1 hr/quarter | ✅ |
| Monitor miss-log trends | Identifies new demand | 30 min/week | ✅ |

**Target steady state**: ~1,500–2,000 verified teas within 12–18 months.

---

### What Would Require Relaxing a Constraint

| **Constraint** | **Would It Be Worth Relaxing?** | **Why/Why Not** |
|----------------|--------------------------------|-----------------|
| **No web crawling** | ❌ **No** — legal/operational risk too high | Decision #45 is locked; no VPN/Yandex-native path |
| **AI tier off** | ⚠️ **Maybe, later** — a curated LLM batch over miss logs could accelerate curation | But MVP is AI-off per #88/#100; revisit post-launch |
| **No accounts** | ❌ **No** — core privacy spine | User contribution without accounts is hard; miss-log is better |
| **No PII** | ❌ **No** — core privacy spine | Miss-log aggregates, no PII |

---

## 7. Reference Links

| **Source** | **URL** | **Access Date** | **Relevance** |
|------------|---------|-----------------|---------------|
| Wikidata License (CC0) | https://creativecommons.org/publicdomain/zero/1.0/ | 2026-06-19 | Primary data source |
| Open Food Facts License (ODbL) | https://world.openfoodfacts.org/terms-of-use | 2026-06-19 | ODbL reference |
| GB/T 30766-2014 Tea Classification | https://std.samr.gov.cn/gb/search/gbDetailed?id=71F772D7D45FD3A7E05397BE0A0AB82A | 2026-06-19 | Reference taxonomy (not redistributable) |
| GOST 32593-2013 Tea Terminology | https://meganorm.ru/Data2/1/4293769/4293769895.pdf | 2026-06-19 | Reference taxonomy (not redistributable) |
| Wikidata Food/Drink Query Example | https://w.wiki/8kXQ (SPARQL for foods) | 2026-06-19 | Demonstrates ~31k food items, ~240 tea-related |
| World Camellia Register | http://camellia.iflora.cn/ | 2026-06-19 | 53k varieties — not openly licensed |
| Tea Plant Variety Database (China) | https://teadata.net/ | 2026-06-19 | Cultivar data — license unclear |
| MyTeaPal (Comparable App) | https://myteapal.com/ | 2026-06-19 | Precedent for user-add model |

---

## Summary Table: Breadth Strategies Compared

| **Strategy** | **Coverage Gain** | **Compliance Risk** | **Ops Burden** | **Privacy** | **Data Quality** | **Verdict** |
|--------------|-------------------|---------------------|----------------|-------------|------------------|-------------|
| **Open datasets (non-Wikidata)** | Low (no usable RU+ZH set) | Medium (licenses unclear) | High (integration) | ✅ | Low | ❌ Not viable |
| **Wikidata bulk sync** | Medium (~1,200 entities) | ✅ (CC0) | Medium (maintenance) | ✅ | Low (sparse metadata) | ⚠️ Not worth it |
| **Wikidata on-demand resolve** | Medium (~1,200 entities) | ✅ (CC0) | Low (no maintenance) | ✅ | Low (sparse) | ✅ **Recommended** |
| **User contribution (anonymous)** | High (scales with users) | Medium (abuse) | **High** (moderation) | ⚠️ Gray | Low | ❌ Not sustainable |
| **Demand-driven curation** | High (targeted) | ✅ | Low (operator-paced) | ✅ | High (curated) | ✅ **Recommended** |
| **Product reframe** | 0 (but reduces perceived gap) | ✅ | None | ✅ | N/A | ✅ **Recommended** |
| **OFF (isolated)** | Medium (~1,000 products) | ✅ (ODbL, isolated) | Low | ✅ | Medium | ⚠️ Optional, keep isolated |

---

## Final Recommendation

1. **Do not** invest in non-Wikidata open datasets — none exist with usable RU+ZH coverage and permissive licenses.
2. **Do not** bulk-sync Wikidata — on-demand resolve is strictly better (same coverage, zero maintenance).
3. **Do not** implement user-submission — moderation debt is unsustainable for one operator.
4. **Do** implement miss-logging + operator review dashboard — this is the **only** scalable, compliant, low-ops way to grow the catalog.
5. **Do** reframe the product — the catalog is a "curated reference," not a comprehensive encyclopedia.
6. **Do** keep OFF as an **isolated, optional** suggestion source — never merge into `verified` catalog.

**Expected outcome**: 1,500–2,000 high-quality `verified` teas within 12–18 months, driven by actual user demand, with ~2–3 hours/week of operator effort. This is the maximum coverage achievable under the locked constraints.