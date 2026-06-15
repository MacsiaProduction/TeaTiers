# Rating — 05-yandex-terraform

Prompt: ./prompt.md   ·   Date judged: 2026-06-15

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| alice    |    5     |   5   |       5       |    2     |    5    | 4.30  |  1   |
| gemini   |    4     |   5   |       5       |    2     |    5    | 3.95  |  2   |
| deepseek |    3     |   4   |       4       |    3     |    4    | 2.95  |  3   |
| kimi     |    -     |   -   |       -       |    -     |    -    |   -   |  –   |

<!-- Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**Winner:** alice — the most RU-accurate and complete. It alone flagged the **critical
RU gotcha** (the provider may be unreachable from `registry.terraform.io` in Russia →
use the `terraform-mirror.yandexcloud.net` mirror with `terraform providers lock
-net-mirror=...`), used the current `yandex_vpc_security_group_rule` resources, fully
defined the VM service account + IAM role bindings, caught Managed-PG's port **6432**,
the async IAM-role-propagation delay, and had honest cost caveats — all with real
`yandex.cloud` doc links. gemini is a very close 2nd (cleanest end-to-end cloud-init +
CI). deepseek brought a nice idea (Container-Optimized Image + `docker-compose` metadata
key) but with the most errors.

**Reuse (consensus across all three):** → feeds `decisions.md` (#11 refinement) + plan §8:
- **Topology:** single Compute VM (`standard-v3`, burstable `core_fraction` 20–50%,
  2 vCPU, 2–4 GB, `network-hdd`, `nat=true`) + `yandex_vpc_network`/`subnet`/
  `security_group` (443/80 open, SSH from your IP only, egress all) + service account +
  `yandex_container_registry` + `yandex_lockbox_secret` + cloud-init that logs into the
  registry via the VM's metadata IAM token and runs `docker compose up`.
- **Database:** **self-hosted Postgres in the compose stack = recommended** (≈0 extra
  cost); Managed PostgreSQL (`yandex_mdb_postgresql_cluster`) only if you need automated
  backups/HA (+~2,000–5,400 ₽/mo). All three agree.
- **State:** remote `backend "s3"` on Yandex Object Storage + **YDB (DynamoDB-compatible)
  locking** (table PK must be `LockID`); S3 creds via `AWS_ACCESS_KEY_ID/SECRET` env in
  CI, not in code.
- **Secrets:** Lockbox; app reads via its own SA (`lockbox.payloadViewer`); keep values
  out of TF state (manage versions out-of-band, or restrict the state bucket).
- **CI:** GitHub Actions, SA-key JSON in secrets, `yc`/`yc-actions`, build+push to
  Container Registry, `terraform plan` on PR / `apply` on main.
- **Cost:** VM + self-hosted PG ≈ **700–1,700 ₽/mo**; trial grant 4,000 ₽ (individuals).
- **RU mirror gotcha** (alice) — bake into the Terraform README from day one.

**Discard / verify:**
- **Provider version conflicts:** alice + gemini say **`0.206.0`**; deepseek says
  `0.136.0`. Verify the current version on the Terraform Registry before pinning (lean
  to 0.206.0 — two-vs-one + more recent).
- **deepseek's "Object Storage has no locking"** — contradicts the official YDB-locking
  method (alice/gemini); YDB locking is documented. Don't skip locking.
- **deepseek's Serverless-Containers-as-primary** — weak for an always-on JVM backend
  with outbound AI calls (cold starts); VM+compose is the right default (alice/gemini).
- **Exact RUB figures** (VM ~550 vs ~1,224; IP ~50 vs ~245) conflict — confirm in the
  Yandex pricing calculator at deploy time.
