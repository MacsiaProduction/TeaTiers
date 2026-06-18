# Rating — 16-catalog-breadth

Prompt: ./prompt.md   ·   Date judged: 2026-06-19

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| gpt      |    5     |   5   |       5       |    1     |    5    |  4.40 |  1   |
| opus     |    5     |   5   |       5       |    1     |    4    |  4.30 |  2   |
| gemini   |    3     |   4   |       4       |    3     |    4    |  2.95 |  3   |
| deepseek |    3     |   4   |       3       |    3     |    4    |  2.70 |  4   |
| alice    |    3     |   2   |       3       |    3     |    3    |  2.20 |  5   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**The run's signal is consensus, not contest.** All five answers independently land on the *same*
strategy — and the dataset enumeration (the prompt's headline Q1) comes back **empty of anything new**:
nobody surfaced a genuinely redistributable tea dataset beyond the already-locked **Wikidata (CC0) /
Wikipedia (CC-BY-SA) / Open Food Facts taxonomy (ODbL, isolated)** core. That negative result *is* the
finding: the open core is the ceiling for free structured tea data, so breadth has to come from
**demand-driven curation + a product reframe**, not from discovering a magic table. The Wikidata
reality-check is also unanimous (and unanimously *estimated*, not measured): only **~200–600 tea
entities**, ~40–70% with ru labels, fewer with zh — so a Wikidata bulk-sync buys a few hundred rows at
most, and on-demand `/resolve` (just un-broken in #115) already covers the famous teas.

**Winner: gpt** — the cleanest, broadest, most constraint-faithful answer with **zero fabricated
datasets or inverted licenses**. It enumerated the most real open sources with every license correct
(incl. the sharp **GBIF/WCVP CC-BY vs CC-BY-NC** caveat and the correct "copyrighted, do-not-mirror"
calls on GB/T, ISO 20715, GOST, and the live International Camellia Register), was **honest that it
could not live-query Wikidata** (hedged the counts as estimates instead of inventing them), fenced the
optional LLM step as **private operator tooling only** (compliant with AI-OFF), and articulated the
actual product answer best: *reframe the success metric from "contains every tea" to "user can add any
tea in <1 min, and repeated misses graduate to shared rows."* Its no-PII miss-log → review → promote
pipeline (concrete schema + normalization + 3-table review surface) is directly buildable.

**opus is a very close #2** — equally fabrication-free and constraint-faithful, and it contributes the
one genuinely **net-new, RU-first lead** nobody else found: the **Rospatent open GI/NMPT register** (RU
tea geographic indications) plus the **EU eAmbrosia** GI register — both small/niche and license-unconfirmed,
but worth a look for a Russia-first catalog. Its Phase-4 framing threads the AI-OFF lock precisely
(one-off, offline, fully operator-reviewed batch; non-LLM provenance on published rows). It loses the
top slot only on breadth/clarity and a couple more "confirm-the-license" hedged cells than gpt.

gemini/deepseek/alice trail on **evidence rigor**, not strategy — their contribution/miss-log/reframe
sections are all decent, but each carries a disqualifying factual defect for a project that would
*mirror data on their word* (below).

**Reuse (this is the catalog-breadth strategy → decision #116, applied to plan.md):**
- **Reframe, don't chase completeness** (all 5; Beanconqueror precedent via gemini). Position the
  catalog as a curated **famous-tea reference seed** (~300 `verified` rows); make **custom-add + OCR**
  the first-class hero path. "Tea not found" must be a one-tap *add-your-tea*, never a dead end. Highest
  leverage, ~0 ops.
- **Demand-driven seed-from-misses is the growth engine** (all 5; concrete schemas from gpt/alice/gemini).
  Log server-side the **aggregate, name-string-only, no-PII** set of resolve/search misses
  (`query_norm, miss_count, first_seen, last_seen`; DATE granularity; no IP/session/device-id). Operator
  promotes the weekly top-N to `verified`. Smallest pipeline; ~2–3 hrs/week converts real demand into
  permanent breadth.
- **Wikidata resolve stays on (now fixed, #115); bulk-sync is optional + modest.** A one-off offline
  operator import of the ru/en-labelled tea subset is a reasonable +few-hundred-row boost *if wanted* —
  **measure the real counts with our own SPARQL first** (every number in this run is an estimate). **No
  monthly sync** (unanimous).
- **Anonymous, no-account contribution = operator review queue** (all 5; best no-PII design gpt/opus;
  gemini's *GitHub-Issues-as-channel* is the zero-ops variant). "Suggest a tea / correct a row" +
  optional promote-your-own-custom, hidden until reviewed, non-PII rate-limiting. Defer until misses
  prove insufficient.
- **The optional one-off operator LLM batch stays AI-OFF-compliant** *only* fenced as private tooling
  over top misses, fully human-reviewed, non-LLM provenance on published rows (gpt/opus thread this; the
  line to hold — see Discard re gemini).
- **Leads to chase (unverified):** Rospatent open GI/NMPT register + EU eAmbrosia (opus) — confirm
  license before mirroring.

**Discard (do not let these leak into code/planning):**
- **gemini's invented licenses** — *"Chinese Standard Exception → public domain"* and the overstated
  *"GOST is public domain"* claim. GB/T and GOST standard **texts are copyrighted and sold**
  (Standartinform / SAC-now-SAMR); only individual facts/terms are reusable, never wholesale text. This
  is exactly the kind of claim that must not be trusted.
- **gemini leaning on a local-LLaMA row-generator as THE engine** without flagging it relaxes the locked
  AI-OFF posture (the prompt's Q6 asked it to call this out; it didn't). Keep LLM strictly as fenced,
  human-reviewed operator tooling.
- **deepseek's fabricated/misidentified datasets** — **"BioTea"** is a biomedical NLP corpus, *not* tea
  data (name collision); "teadata.net" and the World-Camellia-Register relevance are unverified; plus an
  internal contradiction (~240 vs ~2,500–3,500 tea entities) and no in-body citations.
- **alice's OpenAI suggestion** for the operator batch (non-Yandex-native — off the no-VPN/Yandex lock;
  use a Yandex-native model if any), its unciteable self-run Wikidata query (265/78/125 — directionally
  fine, but not reproducible), and its garbled Hanzi/pinyin output.
- **Any reliance on the specific Wikidata counts** — all five are estimates. Measure before sizing a sync.

> Updated ../LEADERBOARD.md: **+1 Win** for gpt; **+1 Run judged** for gpt, opus, gemini, deepseek, alice.
