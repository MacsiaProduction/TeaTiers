# Architecture review (2026-06-17) — disposition

Tracks how each finding in [`2026-06-17-architecture-review.md`](./2026-06-17-architecture-review.md)
is being handled. Statuses: **✅ done**, **🛠 in progress / planned (autonomous)**,
**⏳ queued**, **❓ needs your decision** (not actioned until you decide — see §"Open decisions").

| # | Finding | Disposition |
|---|---------|-------------|
| P0 | Search lacks typo tolerance (live `LIKE`) | ⏳ **research run 09 first** (decision #67) — pg_trgm vs engine; then implement. Not started in code by design. |
| P0 | Backup drops v5 enrichment fields | ✅ **done** — `BackupTea` + both mappers + round-trip tests (folded into #69 / PR #35). |
| P0 | Destructive Room migrations are a release blocker | ✅ **decided (#70.1): leave as-is until M5** — cutover (export schema + drop destructive + real migrations) is an M5 release-gate task; internal builds wipe on upgrade until then. |
| P1 | Queued enrichment isn't durable (app-scope only) | partly ✅ (#73): resume now runs on **app-open** (home), de-duped in-flight. Full **background** WorkManager (post-process-death) is ❓ **open #70.6** — adds a dependency + non-device-verifiable runtime wiring. |
| P1 | `/resolve` contract drift (sourceText ignored; async status) | partly ✅: the async `ENRICHING` status + server stub shipped in **#66**; `sourceText` is now consumed by the LLM tier. Remaining: the **"paste a description" UI field** (#25) and a **global daily LLM ceiling** (below). |
| P1 | Catalog image model behind app photo list | ✅ **done** (#75) — backend `tea_image` list (Flyway V3); detail exposes `images` + `image` (first) for back-compat. |
| P1 | Local dedup should prefer `catalogTeaId` | ✅ **done** (#72) — `addTea` matches by `catalogTeaId` first (then name); a catalog pick carries the id + skips re-resolve. |
| P1 | Release gate not explicit | ✅ **done** — added **plan.md §7.1 "MVP release gate"** (this PR). |
| P1 | Missing global daily LLM ceiling | ✅ **done** (#71) — `LlmDailyBudget` global daily cap; a miss fails closed to `UNRESOLVED` when exhausted. |
| P2 | GHCR migration | ✅ **done** (#76) — `publish-image.yml` pushes to `ghcr.io` via `GITHUB_TOKEN`; cloud-init pulls public. YCR kept provisioned-but-deprecated; you do the cutover steps (make package public, point VM, verify, retire YCR). |
| P2 | Backend backup is local-only | ❓ **needs decision** — enable off-box Object Storage backups now (resolve writes non-seed rows) vs accept local-only in writing. See Open decisions. |
| P2 | Catalog seed too small (13 vs ~300) | ❓ **needs decision / effort** — curated-seed expansion is real content work; sequence vs fuzzy search. See Open decisions. |
| P2 | Keep custom back stack / drag / self-hosted PG | ✅ **no action** — agreed, keep as-is until a concrete trigger appears. |

## Open decisions (recorded, not actioned — your call)

These change product scope, the dev workflow, or ops posture, so they wait for your decision
rather than being done autonomously. Mirrored as **decision #70** in `decisions.md`.

1. **Room public-schema cutover.** Today every schema bump is destructive
   (`fallbackToDestructiveMigration` + `exportSchema=false`) — fine while iterating internally, fatal
   once a real user has data. *Decision needed:* declare "public schema starts at vN" (likely the
   current v5), turn on `room.schemaLocation` + commit the baseline, and require real migrations from
   then on. Until you pick the line, internal builds keep wiping on upgrade.
2. **Catalog image list (backend).** ✅ **RESOLVED 2026-06-17: in MVP scope — done (#75).** Backend
   `tea_image` list (Flyway V3); detail exposes `images` + `image` (first) for back-compat. Web image
   fetching stays banned (CC/Wikimedia or user photos only).
3. **Off-box DB backup.** `/resolve` now writes catalog rows that aren't in the committed seed, so the
   DB is no longer fully reproducible from VCS. *Decision needed:* enable Object Storage `pg_dump`
   off-box backups before relying on user-driven enrichment, or accept local-only backup in writing.
4. **Curated seed expansion.** Live seed is 13 teas; plan #10 wants ~300. *Decision needed:* prioritize
   the curated-seed effort (and use it to build the search-gold set) vs. lean on fuzzy search + Wikidata
   resolve first. This is content effort, not a code change.
5. **en/zh UI timing.** MVP ships ru-only. *Decision needed:* ship `values-en`/`values-zh` for the
   release, or keep ru-only with explicit "Russian-only" copy in the language picker. (M5 item.)

## Autonomous follow-ups in flight (no decision needed)

Being implemented as separate verified PRs off `main`, in this order:
1. Global daily LLM ceiling (backend cost protection). ✅ #71
2. `catalogTeaId`-first local dedup linking. ✅ #72
3. Queued-enrichment durability: in-app resume-on-open + in-flight de-dupe ✅ #73; true
   **background** WorkManager reclassified to **open decision #70.6** (dependency + non-device-verifiable).
