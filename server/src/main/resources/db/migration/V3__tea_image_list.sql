-- V3: per-tea reference image LIST (decision #70.2), replacing the single image triple on `tea`.
-- A tea can have several ordered images: the first is the card thumbnail, the rest the detail
-- gallery. Only curated CC/Wikimedia images land here (web fetching stays banned, #24); `license`
-- + `source_url` carry the share-alike attribution, `source` is provenance (e.g. 'wikimedia').

CREATE TABLE tea_image (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tea_id      BIGINT   NOT NULL REFERENCES tea (id) ON DELETE CASCADE,
    position    SMALLINT NOT NULL,
    url         TEXT     NOT NULL,
    license     TEXT,
    source_url  TEXT,
    source      TEXT,
    CONSTRAINT tea_image_position_uk UNIQUE (tea_id, position)
);

CREATE INDEX tea_image_tea_id_idx ON tea_image (tea_id);

-- Carry forward any existing single reference image as position 0; reuse the tea's provenance.
INSERT INTO tea_image (tea_id, position, url, license, source_url, source)
SELECT id, 0, image_url, image_license, image_source_url, source
FROM tea
WHERE image_url IS NOT NULL;

ALTER TABLE tea DROP COLUMN image_url;
ALTER TABLE tea DROP COLUMN image_license;
ALTER TABLE tea DROP COLUMN image_source_url;
