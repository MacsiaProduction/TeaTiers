# Rating — 09-typo-search

Prompt: ./prompt.md   ·   Date judged: 2026-06-17

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). The **rank + winner** is the real output; the Score is only a tiebreaker.

| Model  | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|--------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus   |    5     |   5   |       5       |    1     |    5    |  4.9  |  1   |
| gemini |    5     |   5   |       4       |    1     |    4    |  4.6  |  2   |
| gpt    |    4     |   4   |       4       |    2     |    4    |  3.8  |  3   |
| alice  |    4     |   4   |       3       |    2     |    4    |  3.5  |  4   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**Winner:** opus. Picked **pg_trgm-in-existing-Postgres for the MVP** and backed it with the exact
schema (IMMUTABLE `f_unaccent` wrapper + a `name_norm` = `lower(f_unaccent(name))` STORED generated
column + a trigram GIN index on it), the ranked threshold-gated query (`word_similarity` / `<%`),
the *verbatim-cited* PG16 default thresholds (`similarity` 0.3, `word_similarity` 0.6, `strict` 0.5),
the Hanzi limitation (Tom Lane: pg_trgm "fairly useless" for multibyte) and the **non-`C` / ICU
collation prerequisite**, and Meilisearch (MIT) as the single-binary fallback. gemini reached the
same pg_trgm-first verdict with strong depth but less copy-paste-ready SQL.

**Reuse:** opus's design *verbatim* — `f_unaccent` + `name_norm` generated column + `gin_trgm_ops`
index + `word_similarity`-ranked query; tune the threshold (~0.3–0.45) on a ru/en/pinyin gold set;
ensure the DB is created with an ICU/non-`C` collation so trigrams see Cyrillic. Keep **Meilisearch
CE** as the documented fallback *only if* the gold set fails (especially for Hanzi).

**Discard:** alice's **Meilisearch-first** for the MVP — it ignores the project's explicit "no extra
always-on service / operational simplicity" constraint and the small catalog size; and **zhparser**
(community CJK build, must compile from source — conflicts with simplicity). OpenSearch (≥2 GB RAM
floor) is too heavy for the 1–2 GB VM (all four agree).

> Then update ../LEADERBOARD.md: **+1 Wins** for opus, **+1 Runs judged** for each of the 4 models.
