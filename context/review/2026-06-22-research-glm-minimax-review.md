# TeaTiers research review — new GLM + MiniMax answers (2026-06-22)

Independent re-analysis (parallel per-run agents, adversarially checked against the locked decisions) of
the newly-added **GLM** and **MiniMax** model answers in the `researches` repo
(`../researches/projects/TeaTiers/`). Complements `2026-06-22-full-architecture-design-review.md` §"New
research review", which reached the same conclusion.

## 1. Executive verdict

**No locked decision changes. No new research run is warranted.** This confirms the 2026-06-22 architecture
review.

Of 21 runs, only **6** actually contain new GLM and/or MiniMax answers (01, 02, 18, 19, 20, 21); the other
15 have no such files. Across the 6 substantive runs:

- **Zero** answers overturn a locked decision. Where the new models diverge from a locked decision they are
  **wrong**, not insightful: MiniMax mislabels Wikidata as CC-BY-SA 3.0 (run 01, contradicts decision #10's
  CC0 premise), recommends Yandex-as-MVP and assumes map-in-MVP scope (run 02, contradicts #9/#20), and GLM
  moves confusable-normalize+match into Spring (run 18, contradicts #123's Python-sidecar architecture).
- The genuinely correct content (runs 01, 18, 21, much of 02/19/20) **reaffirms the existing opus/gpt
  winners** rather than challenging them. GLM "wins" run 19 on VM sizing only by agreeing with the prior
  review's "no measured ROI for 16 GB" finding.
- Both models repeatedly **fabricate version pins, benchmarks, licenses, and citations** (§4), which is
  disqualifying for any answer that would otherwise move a decision.

## 2. Per-run signal / discard

| Run | Useful signal | Discard / verify |
|---|---|---|
| **01-tea-databases** | Wikidata+Wikipedia+OFF as redistributable open core; 200–500 hand-curated seed; Steepster/RateTea non-redistributable. MiniMax: JSONB `source_metadata` provenance column. | MiniMax: Wikidata = CC-BY-SA 3.0 (**wrong — CC0**, contradicts #10); OFF "4M" conflated with the tea subset (~5–15k). |
| **02-maps-geo-android** | MiniMax: accurate provider table; MapLibre keyless/no-GMS; Nominatim 1 req/s; Compose interop; Hilt provider scaffolding. | Yandex-as-MVP + map-in-MVP scope (contradicts #9/#20); conflicting SDK pins; speculative API names. |
| **18-ocr-name-capture** | confusable map + script-majority guards; margin-guarded thresholds (≥88 auto, 70–88 confirm); reject local handwriting model; MiniMax: Python-sidecar normalize+match (matches #123). | GLM: normalize+match in Spring (**contradicts #123**); Yandex Vision pricing unconfirmed. |
| **19-ocr-description-extraction** | no server-tier Cyrillic rec exists; PP-OCRv5 server det = highest-ROI; dict-gated SymSpell+pymorphy3; text-only respects #96. GLM: cost-optimal 8 GB sizing. | MiniMax: 16 GB VM with no measured ROI; fabricated arXiv id; wrong SymSpell license. Neither addresses #137-C1. |
| **20-catalog-scraping** | GLM: httpx 0.28.1 / selectolax 0.4.10 pins; facts-vs-expression staging. MiniMax: RU legal framing (ГК РФ 1225). | MiniMax: Moychay "~10,000 SKUs" (**wrong — ~700–800**); stale pins. Both miss pinyin-canonical dedup. |
| **21-catalog-scraping-foundation** | source-record-keyed idempotency `(source, external_id)`; 3-tier match; provenance; facts-only CI guard; ToS gate; soft-rollback. MiniMax: a `scrape_upsert_canonical()` plpgsql sketch. | MiniMax: `unidecode` for cross-script bridging (**wrong — needs the curated Palladius↔pinyin table**); stdlib robotparser over Protego; stale pins. |
| 03–17 (no 14) except above | — | No GLM/MiniMax files present; not assessable. |

## 3. Genuinely NEW actionable ideas (strict)

Only three items are both new (not already a locked decision or prior winner) and worth carrying forward:

| Idea | Touches | Disposition |
|---|---|---|
| Curated ~300-term tea glossary (Хун, Гунфу, Да Хун Пао, Те Гуань Инь…) to guard the dict-gated OCR corrector from mangling domain terms (run 19). | #123 / OCR correction design | **Adopt** — pre-commit the glossary before OCR-correction Phase 1 ships; low cost. |
| MiniMax `scrape_upsert_canonical()` plpgsql as a concrete reference for the source-record-keyed upsert (run 21). | scrape foundation (#136/#137) | **Spike only** — reconcile with the locked dedup architecture (pypinyin canonical keys + pg_trgm @0.3) before any use. |
| MiniMax Hilt provider scaffolding for map providers (`@Provides` singleton + named provider list, configurable style URL) (run 02). | #9 (map provider abstraction) | **Adopt as reference** if/when map work resumes post-MVP (M6). Not in current scope. |

Explicitly **rejected** as non-novel: Python-sidecar normalization (already #123), PP-OCRv5 server det
(already consensus), source-record idempotency + provenance table (already in the opus winner / #136).

## 4. Recurring failure modes → process implications

1. **Fabricated / stale version pins (both, pervasive)** — httpx, selectolax, pypinyin, MapLibre, Yandex
   MapKit pins conflict run-to-run and lag the verified opus pins. → Never lift a dependency pin from a
   GLM/MiniMax answer; pin at integration time against Maven Central / PyPI.
2. **Fabricated benchmarks, citations, licenses (MiniMax-heavy)** — invented arXiv id, wrong SymSpell +
   Wikidata licenses, "16 GB VM" with no ROI, Moychay SKU hallucination. → Treat any quantitative claim
   (CER, RAM/latency, SKU counts, pricing) or citation as unverified by default.
3. **Architecture drift against locked decisions (model-specific)** — GLM relocates logic to its preferred
   tier (Spring-side); MiniMax over-provisions/over-scopes (Yandex-as-MVP, 16 GB, map-in-MVP). → Inject the
   locked-decision corpus as hard constraints; absent that, both confidently re-litigate settled design.

**Net:** GLM and MiniMax currently act as *consensus amplifiers* — useful when they echo the opus/gpt
winner, unreliable and occasionally decision-contradicting when they diverge. Keep them in the panel for
triangulation, but do not let an unreplicated GLM/MiniMax claim move a decision without independent
verification. The "no new run warranted" conclusion stands.
