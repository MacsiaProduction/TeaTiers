# Typo-Tolerant Catalog Search for TeaTiers: Implementation Path Comparison

## TL;DR
- **Use PostgreSQL pg_trgm (already installed) as the MVP.** For a catalog of 1,200–200,000 name rows on a single small VM, in-database trigram fuzzy search with a normalized generated column satisfies the "several wrong symbols" requirement for Russian, English, and pinyin without adding any always-on service. This honors the explicit "operational simplicity matters" constraint.
- **The one real gap is Chinese Hanzi.** Trigrams are statistically weak on ideographic text because each Han character is a 3-byte multibyte unit; handle Hanzi separately (exact/prefix substring matching plus a pinyin alias row), not via trigram typo tolerance.
- **Fallback if quality fails: Meilisearch (MIT Community Edition) as a single Rust binary** synced by periodic full reindex. It gives better out-of-the-box typo tolerance and built-in CJK segmentation, at the cost of one more container (well under 1 GB RAM at this catalog size). OpenSearch is over-built for this project (default JVM heap of 1 GB, practical floor ~2 GB system RAM) and should be avoided on a 1–2 GB VM.

## Key Findings

1. **pg_trgm handles ru/en/pinyin typo tolerance well.** It provides `similarity()`, `word_similarity()`, `strict_word_similarity()`, the `%`/`<%`/`<<%` operators, tunable thresholds, and GIN/GiST index support. Defaults, confirmed verbatim in the PostgreSQL 16 documentation (F.35 pg_trgm, GUC Parameters): the `%` operator's `similarity_threshold` default is 0.3; the `<%`/`%>` operators' `word_similarity_threshold` default is 0.6; the `<<%`/`%>>` operators' `strict_word_similarity_threshold` default is 0.5.
2. **pg_trgm is "fairly useless" for Hanzi.** PostgreSQL core maintainer Tom Lane stated on the pgsql-general mailing list (Tue 10 Sep 2019, replying to Jimmy Huang regarding pg_cjk_parser): *"pg_trgm is going to be fairly useless for indexing text that's mostly multibyte characters, since its unit of indexable data is just 3 bytes (not characters)."* pg_trgm also requires a non-`C` collation/ctype to process multibyte characters at all.
3. **Short strings (1–3 chars) degrade trigram search**; the community consensus and Alibaba Cloud's analysis recommend ≥3 characters for trigram queries (at least 1 char for prefix `^a`, 2 for suffix `ab$`).
4. **postgres:16-alpine ships pg_trgm and unaccent** out of the box (the official docker-library alpine Dockerfile compiles PostgreSQL from source and runs `make -C contrib install`) and is built `--with-icu`. Alpine uses musl libc, so libc locales beyond C/POSIX are unavailable — use the ICU locale provider. zhparser/pg_jieba/pg_cjk_parser do NOT ship and must be compiled from source (a C/C++ build), which conflicts with operational simplicity.
5. **Engine licensing/footprint:** Meilisearch CE = MIT (single Rust binary, memory-mapped LMDB); Typesense = GPL-3.0 ("The core of Typesense is distributed under the GPL-3.0 license," per its GitHub README) and is an in-memory engine that holds the **entire index in RAM**; OpenSearch = Apache 2.0 (JVM; its `jvm.options` ships `-Xms1g -Xmx1g` and docs recommend setting heap to half of system RAM, implying a ~2 GB system-RAM floor).

## Details

### Deliverable 1 — Comparison Table

| Dimension | **Postgres pg_trgm** | **Meilisearch** | **Typesense** | **OpenSearch** |
|---|---|---|---|---|
| Typo-tolerance model | Trigram set similarity (Jaccard-like); threshold + ORDER BY similarity. Not edit-distance. | Edit-distance typo budget; default `minWordSizeForTypos` oneTypo=5, twoTypos=9; a typo on the first character counts as **two** typos | Edit-distance; `num_typos` default up to 2 for longer words, `typo_tokens_threshold` | Damerau-Levenshtein `fuzziness:AUTO` (0–2 chars exact, 3–5 →1 edit, >5 →2 edits) |
| CJK / multilingual | ru/en/pinyin good; Hanzi weak (3-byte trigram unit; Tom Lane: "fairly useless"); needs non-C collation | Built-in Charabia tokenizer: Jieba (Chinese), Lindera (Japanese); pinyin normalizer; auto language detection + `localizedAttributes`/`locales` | Latin good; CJK relies on ICU tokenization, needs per-field `locale`; team recommends disabling typos and pre-segmenting CJK; weaker than Meilisearch | smartcn/icu analyzers for Chinese; mature analyzers but heavy |
| RAM / footprint | **Zero extra** (uses existing Postgres). | Memory-mapped LMDB; flexible; multi-GB only at millions of docs; small catalog << 1 GB | **Entire index in RAM**; ~20–30 MB idle; ~2–3× dataset size (e.g., 1M Hacker News titles ≈ 165 MB); small catalog tens of MB | JVM heap default 1 GB (`-Xms1g -Xmx1g`); recommend ≥2 GB system RAM; **too heavy for 1–2 GB VM** |
| Sync from Postgres | None — data already in Postgres | App dual-write or periodic full reindex (cheap at this scale) | App dual-write or periodic full reindex | CDC/Debezium/dual-write; heavier ops |
| Kotlin/Spring integration | Native (JPA/JDBC, native query) | Official `meilisearch-java` SDK 0.20.1 (Maven Central); community Kotlin wrappers (e.g., nemoengineering/meilisearch-kotlin) | Official `typesense-java`/`typesense-js`; less Spring-native | Official `opensearch-java` 3.9.0 client |
| License | PostgreSQL License (permissive) | **MIT** (Community Edition); Enterprise features BUSL 1.1 — only sharding is EE-exclusive | **GPL-3.0** (server) | **Apache 2.0** |
| Ops complexity | Lowest (already running) | Low (single binary) | Low (single binary) but RAM-bound | High (JVM tuning, heap, circuit breakers, JDK 21 min) |
| Latest version (Jun 2026) | pg_trgm 1.6 on PG16 | v1.45.2 (2026-06-02) | v30.2 (2026-04-19) | 3.7.0 (2026-06-09) |

### Deliverable 2 — Recommended MVP (pg_trgm in existing Postgres)

**Why:** The catalog is tiny (≤200k name rows). A separate search service contradicts the single-VM, operational-simplicity constraint and adds RAM/sync cost for no proportional benefit on Latin/Cyrillic/pinyin. The extensions are already installed.

**Schema additions — normalized generated column + IMMUTABLE unaccent wrapper:**

```sql
-- 1. IMMUTABLE wrapper so unaccent can be used in index expressions / generated columns.
--    (unaccent() itself is only STABLE, so it cannot be used directly in an index.)
CREATE OR REPLACE FUNCTION f_unaccent(text)
RETURNS text AS
$$ SELECT public.unaccent('public.unaccent', $1) $$
LANGUAGE sql IMMUTABLE PARALLEL SAFE SET search_path = public, pg_temp;

-- 2. STORED generated column holding lower(unaccent(name)) — normalizes diacritics,
--    including pinyin tone marks (lǜ -> lu) and Cyrillic case.
ALTER TABLE tea_name
  ADD COLUMN name_norm text
  GENERATED ALWAYS AS (lower(f_unaccent(name))) STORED;

-- 3. Trigram GIN index on the normalized column
CREATE INDEX tea_name_norm_trgm_idx
  ON tea_name USING gin (name_norm gin_trgm_ops);
```

**Query pattern (ranked, threshold-gated):**

```sql
-- Set per-session word-similarity threshold (tune on gold set)
SET pg_trgm.word_similarity_threshold = 0.45;

SELECT tn.tea_id, tn.name, tn.locale,
       similarity(tn.name_norm, lower(f_unaccent(:q)))      AS sim,
       word_similarity(lower(f_unaccent(:q)), tn.name_norm) AS wsim
FROM   tea_name tn
WHERE  lower(f_unaccent(:q)) <% tn.name_norm      -- index-backed word-similarity filter
ORDER  BY wsim DESC, sim DESC
LIMIT  20;
```

**Function/operator choices:**
- Use `word_similarity` / `<%` (substring-aware) so a short query like "пуэр" matches inside "Шу Пуэр Менхай" — `similarity()` alone penalizes length mismatch.
- Use `strict_word_similarity` / `<<%` when you want whole-word boundaries (fewer false positives). Per the PostgreSQL docs, `strict_word_similarity` finds similarity to whole words, while `word_similarity` is suited to parts of words.
- GiST supports `<->`/`<->>` KNN distance ordering for top-N nearest, which **GIN does not**; but **GIN is preferred here** because it gives faster lookups for the `%`/`<%`/`<<%` filters and the existing index is already GIN.

**GIN vs GiST:** GIN = faster lookups, smaller, supports `%`,`<%`,`<<%`. GiST = supports KNN distance ordering (`<->`) and cheaper updates, but slower lookups. For a read-heavy small catalog, keep GIN.

**Pinyin handling (tone marks, tone numbers, the ü/v problem):**
- `unaccent` strips tone-mark diacritics character-by-character via its rules file: `lǜ → lu`, `chá → cha`.
- It does NOT convert `ü → v` or handle tone *numbers* (`lu4`). Store extra alias rows in `tea_name` (locale `pinyin`) for common input forms: `"lü cha"`, `"lv cha"`, `"lvcha"`, `"lucha"`, `"lu cha"`. Optionally extend `unaccent.rules` or add a `translate()` step to fold `v→u`/`ü→u` into `name_norm`.
- Tone numbers: strip trailing digits in app-side query normalization (`lu4 cha2 → lu cha`) before sending to SQL.

**Hanzi handling (do NOT rely on trigram typo tolerance):**
- For `locale='zh-Hans'`, serve **exact substring / prefix** matching (the current `LIKE` approach is fine for Hanzi) plus the trigram index as a weak fallback.
- Ensure the database is NOT created with `C`/`POSIX` collation, or pg_trgm ignores multibyte chars entirely. On alpine, use the ICU locale provider (`--locale-provider=icu`).
- Provide a `pinyin` alias row for each Hanzi tea so a user typing pinyin still finds the Chinese-named tea via the trigram path.

### Deliverable 3 — Fallback (if pg_trgm quality fails the gold set)

**Adopt Meilisearch Community Edition (MIT), single container.**
- Latest stable **v1.45.2 (released 2026-06-02)**; Docker image `getmeili/meilisearch`.
- Typo tolerance is on by default with sensible word-size thresholds (default oneTypo=5, twoTypos=9 characters; a first-character typo counts as two typos); the Charabia tokenizer gives real Chinese segmentation (Jieba) and a pinyin normalizer — directly addressing the Hanzi gap pg_trgm cannot.
- **Sync:** periodic full reindex from Postgres (catalog is tiny; a full dump→index takes seconds) OR application dual-write on catalog change. No Debezium/CDC needed at this scale.
- **Integration:** official `meilisearch-java` SDK 0.20.1 (Maven Central, published 2026-04-22), usable from Kotlin/Spring; or a community Kotlin wrapper.
- **Index design:** one index; documents = flattened tea + its name rows; set `localizedAttributes` mapping Chinese fields to a Chinese locale (`cmn`/`zho`) and use `locales` at search time; configure `searchableAttributes` to the name fields; keep `rankingRules` default (words, typo, proximity, attribute, sort, exactness).
- **RAM:** memory-mapped LMDB; for a ≤200k-row catalog the resident set is well under 1 GB. **Set a Docker `--memory` limit and `MEILI_MAX_INDEXING_MEMORY`** — Meilisearch otherwise sizes itself to host RAM (default 2/3 of available) and can OOM a small VM during indexing.

**Why not Typesense as fallback:** GPL-3.0 (server use is fine, but more restrictive than MIT); it holds the **entire index in RAM**; and its CJK support is weaker — the Typesense team itself recommends disabling typo tolerance and pre-segmenting CJK fields into separate locale-tagged fields.

**Why not OpenSearch:** JVM with default heap `-Xms1g -Xmx1g` and a practical floor of ~2 GB system RAM (docs advise heap = half of RAM); running a JVM search node (3.x requires JDK 21) alongside backend+Postgres+Caddy on a 1–2 GB VM is not viable. Apache 2.0 licensing is the only clear advantage. Reserve as an upgrade path only if the catalog and query volume grow by orders of magnitude.

### Deliverable 4 — Gold-Set Evaluation Plan (30 queries)

Build a fixed gold set of (query → expected tea(s)) pairs. Run against each candidate; measure recall@10 and MRR. Target: ≥90% of ru/en/pinyin typo cases return the intended tea in the top 5.

| # | Locale | Query (with typo) | Intended target | Expected behavior |
|---|---|---|---|---|
| 1 | ru | "улун" | Oolong (Улун) | exact base case |
| 2 | ru | "улонг" | Улун | 1-char substitution → match |
| 3 | ru | "пуэер" | Пуэр (Pu-erh) | insertion → match |
| 4 | ru | "шу пуер" | Шу Пуэр | word-similarity substring match |
| 5 | ru | "сенча" | Сентя/Сенча | spelling variant → match |
| 6 | ru | "дарджилинг" | Дарджилинг | exact |
| 7 | ru | "даржилинг" | Дарджилинг | deletion → match |
| 8 | en | "oolong" | Oolong | exact |
| 9 | en | "oolng" | Oolong | deletion → match |
| 10 | en | "puerh" | Pu-erh | separator-insensitive match |
| 11 | en | "pu erh" | Pu-erh | token split tolerance |
| 12 | en | "sencha" | Sencha | exact |
| 13 | en | "senca" | Sencha | deletion → match |
| 14 | en | "gunpowdr" | Gunpowder | deletion → match |
| 15 | en | "darjeling" | Darjeeling | deletion → match |
| 16 | pinyin | "longjing" | 龙井 / Longjing | exact pinyin (no tones) |
| 17 | pinyin | "lóngjǐng" | Longjing | tone marks stripped → match |
| 18 | pinyin | "long jing" | Longjing | space-insensitive match |
| 19 | pinyin | "lvcha" / "lücha" | 绿茶 / Lü Cha (green) | ü/v alias → match |
| 20 | pinyin | "lu cha" | Lü Cha | normalized alias → match |
| 21 | pinyin | "tieguanyin" | 铁观音 | exact pinyin |
| 22 | pinyin | "tie guan yin" | Tieguanyin | space tolerance |
| 23 | pinyin | "tieguanyn" | Tieguanyin | deletion → match (typo tolerance) |
| 24 | pinyin | "pu er" | Pu-erh | match |
| 25 | zh | "龙井" | Longjing | exact Hanzi (substring path) |
| 26 | zh | "铁观音" | Tieguanyin | exact Hanzi |
| 27 | zh | "普洱" | Pu-erh | exact Hanzi |
| 28 | zh | "绿茶" | Green tea | exact Hanzi prefix |
| 29 | zh (1 char) | "茶" | many | expect broad/low-rank; trigram weak — document behavior |
| 30 | mixed | "longjing 龙井" | Longjing | cross-locale alias resolves |

**Acceptance gate:** if pg_trgm achieves ≥90% top-5 recall on rows 1–24 (ru/en/pinyin) and exact match on 25–28 (Hanzi), ship the MVP. Hanzi typo tolerance (fuzzy Hanzi as in row 29) is explicitly out of scope for the MVP; if the product later requires it, that is the trigger to adopt the Meilisearch fallback.

### Deliverable 5 — "Do Not Do" List

- **Do NOT run OpenSearch (or Elasticsearch) on the 1–2 GB VM.** JVM heap floor (~1 GB) plus OS file-cache needs makes it unviable alongside backend+Postgres+Caddy. It is an order-of-magnitude over-build for ≤200k rows.
- **Do NOT compile zhparser / pg_jieba / pg_cjk_parser into the Postgres image for the MVP.** They require a from-source C/C++ build on musl alpine, only help `tsvector` full-text search (not trigram typo tolerance), and add maintenance burden. They solve segmentation, not fuzzy matching.
- **Do NOT expect trigram typo tolerance to work on Hanzi.** Each character is its own multibyte unit (Tom Lane: "fairly useless"); treat Hanzi as exact/prefix + pinyin alias instead.
- **Do NOT create the database with `C`/`POSIX` collation** if you want pg_trgm to process Cyrillic/multibyte text — use a UTF-8 / ICU locale.
- **Do NOT use `unaccent()` directly in an index expression** without the IMMUTABLE wrapper — `unaccent` is only STABLE and index creation will fail.
- **Do NOT add Debezium/Kafka CDC** to sync a 200k-row catalog. Full reindex is cheap and far simpler.
- **Do NOT adopt Typesense if a permissive license matters** (it is GPL-3.0) or if RAM is tight (entire index in RAM).
- **Do NOT run Meilisearch in Docker without a memory limit** — it sizes to host RAM (default 2/3 available) and can OOM the VM during indexing.

## Recommendations

1. **Stage 1 (now):** Implement the pg_trgm MVP exactly as in Deliverable 2 — IMMUTABLE `f_unaccent` wrapper, `name_norm` STORED generated column, GIN trigram index, `word_similarity` ranked query. Add app-side query normalization (lowercase, strip tone numbers, ü/v fold) and pinyin alias rows. Cost: one migration; zero new services.
2. **Stage 2 (validate):** Run the 30-query gold set. Tune `pg_trgm.word_similarity_threshold` (start 0.4–0.5). Ship if ru/en/pinyin top-5 recall ≥90% and Hanzi exact matches pass.
3. **Stage 3 (trigger fallback):** If gold-set quality fails OR the product mandates Hanzi typo tolerance, add **Meilisearch CE v1.45.2** as one container with periodic full reindex and the official `meilisearch-java` 0.20.1 SDK. Set a hard Docker memory limit and `MEILI_MAX_INDEXING_MEMORY`.
4. **Benchmarks that change the decision:** catalog grows past several hundred thousand teas with high concurrent query volume, OR Hanzi fuzzy matching becomes a hard requirement → re-evaluate Meilisearch (and only at very large scale, managed OpenSearch as an upgrade path, consistent with the stated architecture and the "Managed PostgreSQL and extra services are upgrade paths" constraint).

## Caveats / Must-Verify-Live Checklist

- **VERIFIED (Jun 2026):** Meilisearch latest stable **v1.45.2 (2026-06-02)**; Typesense **v30.2 (2026-04-19)**; OpenSearch **3.7.0 (2026-06-09)**; `meilisearch-java` **0.20.1 (2026-04-22)**; `opensearch-java` **3.9.0**. `postgres:16-alpine` bundles pg_trgm + unaccent via `make -C contrib install` and is built `--with-icu`; musl libc limits libc locales (use ICU provider, supported since PG15-alpine).
- **Verify on your actual image:** run `CREATE EXTENSION IF NOT EXISTS pg_trgm; CREATE EXTENSION IF NOT EXISTS unaccent;` as superuser and `SELECT extname, extversion FROM pg_extension;` — confirm pg_trgm 1.6. (Note: the common alpine failure is a superuser *permission* error, not missing files.)
- **Verify collation:** `SHOW lc_collate;` must not be `C`/`POSIX` for multibyte trigram behavior; confirm an ICU/UTF-8 locale on alpine.
- **Verify** that `unaccent` strips the specific pinyin tone marks in your data (`lǘ`, `nǚ`, `ǜ`, etc.) — test `SELECT f_unaccent('lǜ chá');` and confirm the ü/v fold is handled in your normalization pipeline (unaccent alone does NOT fold ü→v).
- **Verify** Meilisearch RAM ceiling with `MEILI_MAX_INDEXING_MEMORY` and a Docker `--memory` limit before deploying on the small VM.
- **Treat all third-party benchmark/RAM numbers as indicative**, not guarantees — Meilisearch and Typesense both state final index size is not reliably predictable. Run your own sizing test with the real catalog.
- **Exact release date** for opensearch-java 3.9.0 could not be pinned to the day (≈ April 2026); its version number is confirmed latest.