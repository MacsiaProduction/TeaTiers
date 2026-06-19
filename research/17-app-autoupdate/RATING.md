# Rating — 17-app-autoupdate

Prompt: ./prompt.md   ·   Date judged: 2026-06-19

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus     |    5     |   5   |       5       |    2     |    5    |  4.30 |  1   |
| gpt      |    5     |   5   |       5       |    2     |    4    |  4.20 |  2   |
| deepseek |    3     |   4   |       4       |    3     |    4    |  2.95 |  3   |
| gemini   |    3     |   4   |       3       |    3     |    4    |  2.70 |  4   |
| alice    |    3     |   3   |       3       |    3     |    4    |  2.50 |  5   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**The architecture is unanimous; the split is on security correctness.** All five land on the same
design — a **custom updater over `PackageInstaller.Session`** (not the deprecated install intent; no
FileProvider, stream via `openWrite`), **GMS-free** (Play In-App Updates is correctly rejected as
GMS-only), with the **version manifest served from the first-party `tea.macsia.fun` backend** (always
RU-reachable) and the **APK on Yandex Object Storage, GitHub as fallback/mirror** (never primary —
GitHub's asset CDN is RU-throttled). Integrity is layered: **Android enforces same-signer on *update***
(not first install), and the app must enforce **sha256-pin + signer-cert-pin + downgrade protection**,
with an **offline-signed manifest** (Ed25519, pubkey embedded in the app) as the belt-and-suspenders
that survives a compromised backend/bucket/TLS-CA. The manifest is anonymous + no-PII + ETag-cached;
forced update = `installed < minSupportedVersionCode` OR `mandatory`. The one **unverified-everywhere**
weakness: every answer leans on *future-dated, thinly-sourced RU-reachability statistics* (specific
GitHub failure percentages) — the **direction** (don't make GitHub primary) is right; the **numbers**
are not citable and must be ignored.

**Winner: opus** — the most technically correct, with no security mistakes found. It nails the layered
model, the signer-verify API (`getPackageArchiveInfo` + `GET_SIGNING_CERTIFICATES` →
`apkContentsSigners`, pin the **certificate** SHA-256 not the APK hash), correctly flags first-install
is *not* same-signer-protected, and lands the run's **standout insight**: an offline-signed manifest
with an embedded pubkey makes APK authenticity *independent of the host/TLS/GitHub*, so sha256-pinning
dissolves the RU-reachability-vs-security tension — the download mirror becomes irrelevant to integrity.
Cleanest phased plan (testers: sha256+signer+downgrade → public: + offline manifest sig), correct
"Session API needs no FileProvider" catch, honest self-flagging of the Ed25519 pre-API-28 JCA gap. Only
soft spot: unverifiable version/date pins (Ackpine `0.21.1`/Feb-2026) it flags itself. **gpt is a very
close #2** — equally security-literate (pins the cert not the APK hash; `hasSigningCertificate()`;
the sharp idea of a detached signature in an `X-…-Signature` *header* to dodge JSON-canonicalization
fragility), honest lib-hedging — dinged only on a minor `FLAG_MUTABLE` "API 35+" overstatement and
`chatgpt.com`-tagged citation artifacts.

**Reuse (this is the autoupdate architecture → decision #119; user choices folded in):**
- **Install:** custom updater on `PackageInstaller.Session`, via **Ackpine** (`ru.solrudev.ackpine`,
  Apache-2.0, GMS-free, RU-authored — *verify the latest version + maintenance before pinning*) for the
  fiddly session + `STATUS_PENDING_USER_ACTION` confirm + OEM (MIUI/EMUI/Samsung) handling; we keep the
  verification ours. `REQUEST_INSTALL_PACKAGES` + route to `canRequestPackageInstalls()` /
  `ACTION_MANAGE_UNKNOWN_APP_SOURCES` when not granted. No silent install (unprivileged).
- **Security (must-get-right):** sha256-pin the APK from the manifest; signer-cert-pin to our release
  cert via `apkContentsSigners` (**not** `signingCertificateHistory` — it accepts rotated-away certs);
  downgrade-reject `versionCode ≤ installed`; rely on Android same-signer on update. **Phased manifest
  integrity (user):** pin-only now over the TLS-trusted first-party manifest; add the **offline Ed25519
  manifest signature before public** (key off-CI, pubkey embedded, serve the byte-identical stored blob,
  detached signature to avoid canonicalization footguns).
- **Host (user):** manifest from the first-party Spring endpoint (`/api/v1/app/latest`, anonymous,
  no-PII, ETag/304) — **this IS review #12's `minSupportedAppVersion`/`upgradeAvailable` endpoint**;
  APK from the **GitHub Release now**, add the **Yandex Object Storage mirror + manifest pointer before
  public**.
- **UX:** optional dismissable card vs forced full-screen (forced below `minSupportedVersionCode` or
  `mandatory`); unmetered-Wi-Fi default; grant-flow explainer; manual GitHub fallback link; RU copy + a
  "проверено/verified" badge.

**Discard (do not let these into code):**
- **All the specific RU-reachability percentages** (every answer's OONI/"16%"/"10–16%" figures are
  future-dated to a single Meduza/Verstka/4pda/news link — uncited and likely fabricated). Keep the
  conclusion, drop the numbers.
- **alice's load-bearing error:** that a self-initiated `commit()` installs with **no** confirmation
  dialog — false; the pipeline must handle `STATUS_PENDING_USER_ACTION` by launching the returned intent
  (its sample omits this → would stall).
- **gemini's cert-fingerprint bug** (`signatures[0].toByteArray()` SHA-256 ≠ the standard signing-cert
  fingerprint + `[0]`-only indexing breaks multi-signer/v3) and its manifest signing that omits
  payload-canonicalization / signature-field exclusion.
- **deepseek's** `session.fsync(output)` *after* the stream closes (scope bug) and its signer-pin via
  `signingCertificateHistory` (accepts rotated-away certs).
- **Every library version pin** (Ackpine `0.21.1`/`0.22.8`/`0.23.0`, fdroidserver/Obtainium dates) — all
  future-dated/unverifiable; verify the real latest before adding the dependency. App IDs
  (`dev.imranr.obtainium`, `org.fdroid.fdroid`) are NOT Maven coordinates.

> Updated ../LEADERBOARD.md: **+1 Win** for opus; **+1 Run judged** for opus, gpt, deepseek, gemini, alice.
