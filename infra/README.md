# infra/ — Yandex Cloud infrastructure (OpenTofu)

Infrastructure-as-code for the TeaTiers backend, per `context/decisions.md` #18 (Yandex Cloud,
no VPN) and run 05. **Tool = OpenTofu (`tofu`), not Terraform** (#55): HashiCorp's Terraform CLI
binary is region-blocked from RU, the project is VPN-free (#18), and OpenTofu is the drop-in,
Yandex-supported CLI. All `.tf` files are identical for either tool.

A first VM + security group + static IP were **provisioned manually with the `yc` CLI** to unblock
DNS; this config **adopts them via `tofu import`** (config-driven import blocks in `imports.tf`).

## Layout

| Path | Purpose |
|------|---------|
| `bootstrap/` | One-time: creates the remote-state SA + static key + Object Storage bucket (uses local state). |
| `*.tf` (root) | The stack: imports VM/SG/IP, adds Container Registry + puller SA + Lockbox, renders the VM's cloud-init. |
| `deploy/docker-compose.prod.yml`, `deploy/Caddyfile` | The on-VM stack (Caddy TLS -> server -> Postgres), placed by cloud-init. |
| `templates/cloud-init.yaml.tftpl` | First-boot provisioning (install Docker, CR login, compose up). |
| `tofurc` | OpenTofu CLI config pointing provider installation at the Yandex mirror. |

## Live resources (created 2026-06-15, `yc` CLI; adopted by this config)

| Resource | Name | ID | Notes |
|----------|------|----|-------|
| Cloud | — | `b1gd4m74sk3279ieum3k` | |
| Folder | default | `b1g9o3v21bogpvduaj1l` | |
| Compute VM | `teatiers` | `fhm7ai7c90647397qa43` | standard-v3, 2 vCPU @ 50%, 4 GB, 30 GB network-hdd, Ubuntu 24.04, `ru-central1-a`; no SA / no metadata yet |
| Boot disk | — | `fhmnbgc7pmf62q6lqq2p` | image `fd8d509adap8j23buglo` |
| Static IP | `teatiers-ip` | `e9brrf2n7tuau8536vpv` | **93.77.185.62** — reserved + deletion-protected |
| Security group | `teatiers-sg` | `enp7rrdpr5ur1rra2gqa` | ingress 22/80/443, egress all |
| Network / subnet | default / default-ru-central1-a | `enpsmc85p8ladckg91on` / `e9bo6q4hk9vu1ndsta4c` | pre-existing (read as data sources) |

Internal IP `10.128.0.10`. Cost ≈ 1,000–1,400 ₽/mo (always-on, #19); covered by the trial grant.

## Access

```
ssh -i ~/.ssh/teatiers yc-user@93.77.185.62
```

The private key `~/.ssh/teatiers` is **local only — never committed** (rule 10-style). DNS A record
`tea.macsia.fun → 93.77.185.62` already resolves (static IP).

## Apply runbook

Prereqs: `yc` configured (`yc config list`), `tofu` installed, and the provider mirror wired:

```
cd infra
export TF_CLI_CONFIG_FILE="$PWD/tofurc"     # provider installation via the Yandex mirror
export YC_TOKEN="$(yc iam create-token)"     # short-lived auth for the provider
```

### 1. Bootstrap remote state (once)

```
cd bootstrap
tofu init && tofu apply           # creates teatiers-tf-state SA + key + the tfstate bucket
export AWS_ACCESS_KEY_ID="$(tofu output -raw access_key)"
export AWS_SECRET_ACCESS_KEY="$(tofu output -raw secret_key)"
cd ..
```

Keep those keys (e.g. in a local, gitignored `infra/.env`); they are the s3-backend credentials.

### 2. Adopt existing resources + create the registry

```
tofu init                          # configures the s3 backend (uses AWS_* above)
tofu plan                          # MUST show only imports + additions — NEVER a destroy/replace
                                   # of the VM/SG/IP. prevent_destroy guards the VM and IP.
tofu apply                         # imports VM/SG/IP; creates Container Registry + puller SA + Lockbox
```

If `plan` proposes to replace the VM, stop and reconcile `compute.tf` to the live instance first.

### 3. Build + push the server image

The bootJar is JVM bytecode (architecture-independent), so build it once natively, then wrap it in
an amd64 runtime image. `REG="$(tofu output -raw registry_id)"`.

```
cd ../server && ./gradlew bootJar          # build/libs/teatiers-server-*.jar
```

- **With buildx (Docker Desktop / a docker-container builder):**
  ```
  yc container registry configure-docker
  docker buildx build --platform linux/amd64 -t "cr.yandex/$REG/teatiers-server:latest" --push ../server
  ```
- **Without buildx (e.g. a podman machine, where cross-arch builds fail on overlay mounts):** copy
  the jar to the amd64 VM and build the runtime image there (native, no Gradle compile), then push
  with an IAM token:
  ```
  scp -i ~/.ssh/teatiers server/build/libs/teatiers-server-*.jar yc-user@93.77.185.62:/tmp/app.jar
  # on the VM: a 4-line Dockerfile (FROM eclipse-temurin:21-jre-jammy / COPY app.jar / USER 10001 /
  # ENTRYPOINT java -jar) -> docker build -t cr.yandex/$REG/teatiers-server:latest .
  yc iam create-token | ssh ... 'docker login --username iam --password-stdin cr.yandex && docker push cr.yandex/'$REG'/teatiers-server:latest'
  ```

### 4. Deploy on the VM

cloud-init only runs on first boot and the VM predates this config, so provision the adopted VM over
SSH (the puller SA is attached, Docker installed via `apt install docker.io docker-compose-v2`):

```
scp -i ~/.ssh/teatiers infra/deploy/docker-compose.prod.yml yc-user@93.77.185.62:/opt/teatiers/docker-compose.yml
scp -i ~/.ssh/teatiers infra/deploy/Caddyfile               yc-user@93.77.185.62:/opt/teatiers/Caddyfile
# write /opt/teatiers/.env with DOMAIN, ACME_EMAIL, SERVER_IMAGE, POSTGRES_DB/USER and the
# password from Lockbox:  yc lockbox payload get --name teatiers-db
ssh -i ~/.ssh/teatiers yc-user@93.77.185.62 'cd /opt/teatiers && sudo docker compose up -d'
```

Verify: `curl https://tea.macsia.fun/actuator/health` → `{"status":"UP"}`. **Live since 2026-06-16**
(Let's Encrypt cert via Caddy; `restart: unless-stopped` survives reboots).

## Notes

- **State locking** uses the native S3 lock object (`use_lockfile`), fine for a single operator. If
  Yandex Object Storage rejects it, drop locking or add a YDB lock table.
- The VM's `metadata.user-data` is intentionally left unset (cloud-init can't re-run on a live VM and
  it would make metadata "known after apply", obscuring whether `ssh-keys` is preserved). The
  rendered provisioning script is still available as `tofu output -raw vm_cloud_init` and wired back
  into metadata for a from-scratch recreate.
- `import` blocks are no-ops once state holds the resources; they can be deleted after step 2.
- Secrets (`AWS_*`, the Lockbox DB password, SA keys) never enter VCS — see `.gitignore`.
