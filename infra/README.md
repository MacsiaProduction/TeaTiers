# infra/ — Yandex Cloud infrastructure

Per `context/decisions.md` #18, infra is **Terraform** (to be written in milestone **M2**).
A first VM was **provisioned manually with the `yc` CLI** to unblock DNS; **M2 must
`terraform import` these live resources** so state matches reality (no drift).

## Live resources (created 2026-06-15, `yc` CLI)

| Resource | Name | ID | Notes |
|----------|------|----|-------|
| Cloud | — | `b1gd4m74sk3279ieum3k` | |
| Folder | default | `b1g9o3v21bogpvduaj1l` | |
| Compute VM | `teatiers` | (see `yc compute instance get teatiers`) | standard-v3, 2 vCPU @ 50%, 4 GB, 30 GB network-hdd, Ubuntu 24.04 LTS, `ru-central1-a` |
| Static IP | `teatiers-ip` | `e9brrf2n7tuau8536vpv` | **93.77.185.62** — reserved + deletion-protected |
| Security group | `teatiers-sg` | `enp7rrdpr5ur1rra2gqa` | ingress 22/80/443, egress all; on the `default` network |
| Network / subnet | default / default-ru-central1-a | `enpsmc85p8ladckg91on` / `e9bo6q4hk9vu1ndsta4c` | pre-existing |

Internal IP `10.128.0.10`. Estimated cost ≈ 1,000–1,400 ₽/mo (always-on, #19); covered by
the trial grant initially.

## Access

```
ssh -i ~/.ssh/teatiers yc-user@93.77.185.62
```

The private key `~/.ssh/teatiers` is **local only — never committed** (rule 10-style).

## DNS

Point an **A record** `tea.macsia.fun → 93.77.185.62`. The IP is static, so it won't
change on stop/start.

## State

Bare Ubuntu 24.04 — **nothing installed yet**. Next (M2): install Docker + compose,
deploy the backend `docker-compose` (backend + self-hosted Postgres), and obtain a
**Let's Encrypt** cert once `tea.macsia.fun` resolves (for HTTPS / cert-pinning later).

## TODO (M2)

- Write the Terraform (`yandex-cloud/yandex`, pin `~0.206.0` — verify; use the
  `terraform-mirror.yandexcloud.net` mirror from RU) for VM + SG + static IP + Lockbox +
  Container Registry + S3/YDB remote state (run 05).
- `terraform import` the resources above so IaC owns them.
- cloud-init / provisioning to install Docker and run the compose stack.
