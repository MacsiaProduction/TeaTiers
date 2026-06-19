-- Opt-in, GMS-free client diagnostics (decision #111, research run 15 winner gpt). The sideloaded
-- app's ACRA reporter POSTs a STRICTLY ALLOWLISTED crash / silent-wipe report; the server re-enforces
-- the allowlist (defense in depth) before storing. The whole point is to catch a destructive Room
-- migration that silently wipes local-first data WITHOUT crashing — local-first means no backend
-- session would otherwise ever reveal it.
--
-- NO PII by construction AND by enforcement: app/build/device metadata, an exception stack trace, and
-- numeric row counts only. NEVER tea names, notes, photo URIs/coords, board names, OCR text, account
-- ids, or the client IP (the controller never reads or stores the remote address). Rows auto-expire
-- via a scheduled retention purge (default 30 days) so nothing lingers.
CREATE TABLE client_diagnostic (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    received_at         timestamptz NOT NULL DEFAULT now(),
    -- 'crash' = an ACRA-reported exception; 'room_migration_signal' = the out-of-Room row-count
    -- sentinel saw a non-zero -> zero transition across a version/schema change (a silent wipe).
    report_kind         text        NOT NULL CHECK (report_kind IN ('crash', 'room_migration_signal')),
    app_version_code    integer,
    app_version_name    text,
    android_sdk         integer,
    device_manufacturer text,
    device_model        text,
    build_type          text,
    -- crash only: Android stack traces are class/method/line strings — no user data by nature.
    stack_trace         text,
    -- room_migration_signal only: a small JSON object of NUMERIC-only before/after counts
    -- (e.g. {"boards_before":3,"boards_after":0,...}); the sanitizer guarantees integer values.
    row_counts          text
);

-- The only write-side job is the age-based retention purge; index the sweep predicate.
CREATE INDEX client_diagnostic_received_idx ON client_diagnostic (received_at);
