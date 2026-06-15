# Rating — 01-tea-databases

Prompt: ./prompt.md   ·   Date judged: 2026-06-14

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model  | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|--------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus   |    5     |   5   |       5       |    2     |    4    | 4.20  |  1   |
| gemini |    4     |   4   |       5       |    2     |    5    | 3.75  |  2   |
| gpt    |    3     |   3   |       3       |    2     |    4    | 2.60  |  3   |
| kimi   |    -     |   -   |       -       |    -     |    -    |   -   |  –   |

<!-- Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. · kimi not run -->

**Winner:** opus — same core strategy as the others (Wikidata CC0 backbone + curated
seed; never mirror Steepster/RateTea/TeaDB/Moychay/Baidu) but with the most legal
precision, an honest effort estimate (~30–50 person-hours for 300 teas vs gemini's
optimistic 12–15h), the better-normalized `tea` + `tea_name(is_primary)` + per-row
provenance schema, and — crucially — it flags that several tea QIDs in circulation
are wrong (e.g. Matcha = Q822331, not Q333103), which prevents a real import bug.

**Reuse:**
- **Strategy** → `context/decisions.md` #10 + `context/plan.md` §4a: lead with a
  hand-curated ~300-tea seed; mirror only the redistributable open core — **Wikidata
  (CC0)** for ru/zh/pinyin names, **Wikipedia (CC-BY-SA)** prose where SA is accepted,
  **Open Food Facts (ODbL)** category taxonomy kept *isolated* to avoid copyleft
  pollution. Record `source`/`source_url`/`license`/`retrieved_at` per row.
- **Schema** → opus's `tea` + `tea_name(tea_id, locale, name, is_primary)` with
  `pg_trgm` GIN for Cyrillic/CJK substring search; defer zhparser/pg_cjk_parser until
  CJK *word* search is actually needed (trigram covers substring for a tier-list app).
  Use ICU collations on PG 16.
- **From gemini (lift these specifics):** **GOST 32593-2013** (ru tea terms) and
  **Teatips.ru / Denis Shumakov** as the authoritative Russian naming references; the
  15-tea seed mockup (Лунцзин/龙井, Да Хун Пао/大红袍, …) as the seed's starting rows;
  transliterate trade names (Да Хун Пао), never literal-translate.
- **Canonical type enum** → GB/T 30766-2014 six categories
  (green/white/yellow/oolong/black/dark) + puer + herbal/blended.

**Discard (verify before any code depends on it):**
- **Every Wikidata QID** cited by any model — verify each at query.wikidata.org before
  import (opus itself warns Matcha/Pu'er/Longjing/Darjeeling/Tieguanyin QIDs need
  checking; gpt/gemini cite none or differ).
- **All record counts** — they conflict (Steepster 89,876 vs gemini's ~20,000;
  Wikidata "hundreds–low thousands" vs gemini "~500"; OFF tea ~15k vs ~160k beverages;
  gpt's "28 ru / 8 zh Wikipedia pages"). None are load-bearing; don't quote them.
- **gemini's 12–15h LLM-assisted effort estimate** — too optimistic; use opus's
  ~30–50h/300-tea figure for planning.
- **gemini's `tea_translation` UNIQUE(tea_id, locale)** — one row per locale can't hold
  aliases cleanly; prefer opus's multi-row `tea_name` with a partial-unique primary.
- **242-FZ data-localization** (opus) is correctly scoped out: the catalog holds no
  PII, and we store no user data server-side at all, so it doesn't constrain us.
