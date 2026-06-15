# 05-yandex-terraform — provisioning the TeaTiers backend on Yandex Cloud with Terraform

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, …). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

## Context

Project: **TeaTiers** — a personal, local-first tier-list Android app for teas. The
backend is a **single, low-traffic** Kotlin + Spring Boot 3.x (JDK 21) service plus
**PostgreSQL**, shipped as a **multi-stage Docker image**, currently planned as
**host-agnostic `docker-compose`** (backend + Postgres) with a systemd unit / Makefile
(`context/decisions.md` #11). It only serves a shared tea catalog and does on-demand
enrichment (calls **YandexGPT** directly + **Groq via a Germany VPN** booster). It holds
**no user data / no PII**.

What's new: we want **Infrastructure-as-Code via Terraform on Yandex Cloud** (we already
use Yandex Cloud for AI). This run decides how — and whether it refines decision #11
from "host-agnostic" to "Yandex-Cloud-targeted, still containerized/portable."

## Objective

Produce a concrete, minimal, current Terraform setup for standing up the TeaTiers
backend on Yandex Cloud, and recommend the simplest topology for a single low-traffic
service — covering provider/auth, compute, database, secrets, state, CI, and cost.

## Questions

1. **Provider & auth.** The Yandex Cloud Terraform provider: registry source
   (`yandex-cloud/yandex`), current version to pin, and the `required_providers` +
   `provider` blocks. Auth options (service-account key file, IAM token, OAuth,
   `YC_TOKEN`/`YC_CLOUD_ID`/`YC_FOLDER_ID` env) and which is best for CI vs local. How
   `cloud_id`, `folder_id`, `zone` are set.
2. **Compute + network.** Minimal Terraform to run our `docker-compose` on a Compute
   Cloud VM: `yandex_compute_instance` (Ubuntu image via `yandex_compute_image`/family,
   smallest sane preset, boot disk, SSH key, public IP), `yandex_vpc_network`,
   `yandex_vpc_subnet`, and a `yandex_vpc_security_group` (ingress 443/80 + SSH-from-my-
   IP only, egress all). Show how `metadata`/`user-data` (cloud-init) installs Docker and
   launches the compose stack on boot.
3. **Database: managed vs self-hosted.** `yandex_mdb_postgresql_cluster` (Managed Service
   for PostgreSQL) vs just running Postgres in the compose stack on the VM. Give the
   trade-offs (backups, cost, ops) for a **low-traffic catalog** and a recommendation;
   include a minimal managed-PG resource if recommended.
4. **Secrets.** How to inject the **YandexGPT API key**, DB password, and Groq key:
   **Yandex Lockbox** (`yandex_lockbox_secret` + version) and how the VM/app reads them
   at runtime; keeping secrets out of Terraform **state** (`sensitive`, no plaintext in
   `.tfvars` committed), and a **remote state** backend in Yandex **Object Storage**
   (S3-compatible) with locking. Show the `backend "s3"` config for Yandex.
5. **Deploy flow & alternatives.** How Terraform fits with our Docker workflow: VM +
   cloud-init pulling the image from **Yandex Container Registry** (`yandex_container_
   registry`) and running compose, vs **Yandex Serverless Containers**
   (`yandex_serverless_container`) as a no-VM alternative. Recommend the simplest for one
   low-traffic service and note when to prefer each.
6. **CI/CD.** Running Terraform from **GitHub Actions** against Yandex Cloud (SA key in
   `secrets`, `terraform plan` on PR / `apply` on main) and pushing the image to Yandex
   Container Registry. Note any auth/region gotchas.
7. **Cost & free tier.** Yandex Cloud trial grant + any always-free allowances; a rough
   **monthly RUB cost** for the minimal setup (smallest VM + small managed PG, vs VM with
   self-hosted PG). Flag the cheapest viable option.
8. **Gotchas.** RU-region specifics, common provider pitfalls, state-locking, security-
   group must-haves, and anything that bites first-time Yandex Cloud + Terraform users.

## Evidence standards

- Prefer the official Yandex Cloud Terraform provider docs / Yandex Cloud docs and the
  Terraform Registry over blog posts.
- **Pin the exact provider version** and resource/attribute names; explicitly flag any
  resource name or attribute you are not certain exists in the current provider.
- Cite every claim with a link and its publication/last-checked date; prefer recent
  sources. Be explicit about anything RU-region-specific or recently changed.

## Return

1. A **minimal but complete Terraform skeleton** (provider + `required_providers` with a
   pinned version + VM + VPC/subnet + security group + Lockbox + Container Registry, and
   an optional managed-PG block), with cloud-init that runs our compose stack.
2. A **recommended topology** (managed PG vs self-hosted; VM+compose vs Serverless
   Containers) with the one-line reason.
3. The **remote-state `backend "s3"`** config for Yandex Object Storage.
4. A **rough monthly cost** for the recommended setup.
5. 5–8 dated reference links and an explicit **"uncertain / could not verify"** list
   (provider version, resource names, free-tier/cost figures).

---

Models run: <opus, gpt, gemini, kimi>   ·   Date: 2026-06-15

## Adaptations (if any)

- <model>: <what you changed for this tool, and why>
