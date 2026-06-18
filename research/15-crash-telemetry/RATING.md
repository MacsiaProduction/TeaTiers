# Rating — 15-crash-telemetry

Prompt: ./prompt.md   ·   Date judged: 2026-06-18

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| gpt      |    5     |   5   |       5       |    1     |    5    |  4.40 |  1   |
| opus     |    5     |   5   |       5       |    1     |    4    |  4.30 |  2   |
| gemini   |    3     |   4   |       4       |    3     |    4    |  2.95 |  3   |
| deepseek |    3     |   4   |       3       |    3     |    4    |  2.70 |  4   |
| alice    |    3     |   2   |       3       |    3     |    4    |  2.30 |  5   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**Winner:** gpt — the only answer that fits TeaTiers' *actual* constraint (GMS-free, self-hosted,
data-minimized, near-zero ops on an already-tight 4 GB VM — not "max observability UI") and picks
accordingly: **ACRA + a first-party Spring `/crash` (client-diagnostics) endpoint** — zero new
service, zero added RAM, reuses the existing backend + Postgres. Its standout, project-specific
insight: the bare `onDestructiveMigration` callback is an unreliable wipe detector, so add a
**before/after row-count sentinel stored OUTSIDE Room** (DataStore/SharedPrefs) — on startup compare
last-known counts to current; non-zero→zero after a version/schema change ⇒ emit a
`room_migration_signal`. That directly targets the silent-data-loss risk every other answer only
half-covered. Plus: strict allowlist (not scrub-after-capture), numeric-counts-only, a backend
token-rejecting sanitizer, a CI GMS-gate (`grep releaseRuntimeClasspath` for gms/firebase), opt-in
consent copy, no-IP-persist, precise *current* pins (`sentry-android:8.44.0`, plugin `6.12.0`,
`acra-http:5.13.1`, correct **FSL→Apache-2.0** Sentry license), and the honest "can't certify
Russian-network reachability without testing." opus is a very close #2 — equally correct + deep,
honest GlitchTip-R8 caveat — but it leads with **GlitchTip self-hosted**, which is heavier on the
~3.4 GB-committed 4 GB VM (it flags the OOM risk + suggests a 2nd VM, i.e. more ops/cost for a solo
dev) and it lacks the count-sentinel. gemini/deepseek/alice trail on real errors (below).

**Reuse (lift into the planning + the eventual build):**
- **Decision: KEEP telemetry for the public MVP; adopt ACRA + a first-party `/api/v1/client-diagnostics`
  endpoint** on the existing backend (gpt). `ch.acra:acra-http:5.13.1` (+ `acra-limiter`), Apache-2.0,
  GMS-free. GlitchTip + `io.sentry:sentry-android` (MIT, GMS-free, self-hosted) is the documented
  **upgrade path** if manual triage gets painful — but NOT on the current VM (co-host OOM risk; 2nd VM).
- **The count-sentinel migration detector** (gpt) + the `onDestructiveMigration` callback as a
  secondary signal (all answers) — vendor-independent; this is the single most important instrumentation.
- **Strict allowlist** report fields (app/OS/device/version/stacktrace + numeric counts only — never
  tea names/notes/photo URIs/coords/board names), backend sanitizer rejecting `content://`/`file://`/
  `latitude`/`note`/`photoUri`… , **opt-in** consent (off by default; new disclosure copy), don't
  persist client IP, 30–90d retention, and a **CI GMS-gate** on `releaseRuntimeClasspath`.
- **Sentry self-hosted is OUT** (all agree): installer hard floor 7 GB (errors-only)/14 GB (full)
  RAM — opus's precise `_min-requirements.sh` numbers; the develop.sentry.dev "recommended" is 16 GB.
  Either way it cannot share the 4 GB VM. Sentry **SaaS** is OFAC-blocked from Russia (opus/gemini/gpt).

**Discard (do not let these leak into code):**
- gemini's **fabricated GMS-free mechanism** — the Sentry SDK does NOT "probe for GMS via reflection
  and gracefully fall back"; it simply has no GMS dependency (verify by POM, as opus/gpt did).
- gemini's **stale/inconsistent pins**: `io.sentry:sentry-android:7.22.6` + plugin `4.14.0`.
- deepseek's **license error** (Sentry "BSL → MIT after 3 years"; it's **FSL → Apache-2.0 after 2y**),
  its `sentry-android:8.12.0`/plugin `4.14.0` stale pins, its GlitchTip worker `python manage.py
  rqworker` (GlitchTip is **Celery**-based, not rq), its "4 GB VM can comfortably run GlitchTip"
  (over-optimistic vs the ~3.4 GB already committed), and its **opt-out** recommendation (use opt-in).
- alice's unverified GlitchTip license ("likely BSD-3/Apache" — it's **MIT**), its likely-hallucinated
  `com.abovevacant:epitaph` sentry-android-core dependency, and its naive PII config (filter tags whose
  key contains "pii"). alice also skipped ACRA/Bugsnag/Countly (self-admitted).
- ALL "256 MB GlitchTip" figures are *tuned floors* (Valkey off, low volume) — budget ~512 MB–1 GB if
  ever deployed; don't co-host on the current VM without measuring.

> LEADERBOARD: +1 Wins gpt; +1 Runs judged for gpt, opus, gemini, deepseek, alice.
