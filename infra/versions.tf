# TeaTiers infrastructure — OpenTofu (Yandex Cloud). See README.md for the apply runbook.
#
# Tooling note (decision #55): we use OpenTofu (`tofu`), not HashiCorp Terraform. The Terraform
# CLI binary is region-blocked from RU (releases.hashicorp.com returns "not available in your
# region"), and the project is VPN-free by decision #18. OpenTofu is the drop-in, RU-reachable,
# Yandex-supported CLI; every .tf file here is identical for either tool.
terraform {
  required_version = ">= 1.9"

  required_providers {
    yandex = {
      # Fully-qualified: OpenTofu's default registry (registry.opentofu.org) lags the Yandex
      # provider; the canonical, mirror-served source is registry.terraform.io.
      source  = "registry.terraform.io/yandex-cloud/yandex"
      version = "~> 0.206" # latest verified 0.206.0 (2026-05-28); plan.md §8 / decision #18
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Remote state in Yandex Object Storage (created by ./bootstrap). Credentials come from the
  # AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY env vars (the state service account's static key) —
  # never committed (rule 50-secure). `tofu init` wires this; see README.
  backend "s3" {
    endpoints = {
      s3 = "https://storage.yandexcloud.net"
    }
    bucket = "teatiers-tfstate"
    region = "ru-central1"
    key    = "teatiers/main.tfstate"

    # Yandex Object Storage is S3-compatible but not AWS: skip the AWS-only validations and the
    # default checksum, and use the native S3 lock object (single operator, so no YDB lock table).
    skip_region_validation      = true
    skip_credentials_validation = true
    skip_requesting_account_id  = true
    skip_s3_checksum            = true
    use_lockfile                = true
  }
}
