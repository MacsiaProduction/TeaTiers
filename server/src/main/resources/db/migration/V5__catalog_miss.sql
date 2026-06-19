-- Demand-driven catalog growth (decision #116, research run 16 winner gpt): log the aggregate,
-- name-string-only set of /resolve misses so the operator can curate the most-wanted teas into
-- `verified` rows. This is the MVP growth engine now that crawling is banned (#45) and the AI tier
-- ships OFF (#88/#100).
--
-- NO PII by construction: a row holds only the normalized query string + a count + first/last DATE.
-- There is deliberately no IP, session id, device id, user id, or time-of-day — nothing that can
-- re-identify who searched. Operator review is `ORDER BY miss_count DESC` via SQL on the VM (no
-- public endpoint; /actuator and the API are proxied by Caddy, so a miss-list endpoint would leak
-- publicly — a guarded surface can come later).
CREATE TABLE catalog_miss (
    query_norm  text PRIMARY KEY,
    miss_count  bigint NOT NULL DEFAULT 1 CHECK (miss_count > 0),
    first_seen  date   NOT NULL DEFAULT CURRENT_DATE,
    last_seen   date   NOT NULL DEFAULT CURRENT_DATE
);

-- The only read pattern is "top wanted misses": ORDER BY miss_count DESC.
CREATE INDEX catalog_miss_count_idx ON catalog_miss (miss_count DESC);
