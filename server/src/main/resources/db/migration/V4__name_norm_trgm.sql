-- Typo-tolerant catalog search (decision #79, research run 09 — winner opus, verbatim design).
-- Replaces the literal-substring search with in-Postgres pg_trgm fuzzy matching for ru/en/pinyin.
-- No new always-on service (honors the operational-simplicity lock #19).
--
-- Collation note: pg_trgm needs a non-C/POSIX locale to case-fold and char-split multibyte text.
-- Verified on the production image (postgres:16-alpine): its default cluster locale is en_US.utf8
-- (libc provider, UTF8 encoding), under which musl libc folds Cyrillic correctly
-- (lower('УЛУН')='улун') and trigram-classifies it as characters — so NO explicit ICU collation
-- and NO cluster re-init are required. The accompanying IT asserts this prerequisite.

-- IMMUTABLE wrapper so unaccent() is usable in a generated-column / index expression
-- (plain unaccent() is only STABLE). Strips Latin/pinyin diacritics incl. tone marks
-- (f_unaccent('Lǜ Chá')='Lu Cha'); Cyrillic and CJK pass through unchanged.
CREATE OR REPLACE FUNCTION f_unaccent(text)
RETURNS text AS
$$ SELECT public.unaccent('public.unaccent', $1) $$
LANGUAGE sql IMMUTABLE PARALLEL SAFE SET search_path = public, pg_temp;

-- Normalized name for fuzzy matching: lower(unaccent(name)), maintained by Postgres.
ALTER TABLE tea_name
    ADD COLUMN name_norm text GENERATED ALWAYS AS (lower(f_unaccent(name))) STORED;

-- Trigram GIN on the normalized column; replaces the V1 raw-name index (decision #79).
DROP INDEX IF EXISTS tea_name_trgm_idx;
CREATE INDEX tea_name_norm_trgm_idx
    ON tea_name USING gin (name_norm gin_trgm_ops);
