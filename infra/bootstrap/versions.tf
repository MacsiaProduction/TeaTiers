# Bootstrap uses LOCAL state (it creates the bucket that the root config's remote state lives in —
# chicken-and-egg). Keep terraform.tfstate here out of VCS (see infra/.gitignore).
terraform {
  required_version = ">= 1.9"

  required_providers {
    yandex = {
      source  = "registry.terraform.io/yandex-cloud/yandex"
      version = "~> 0.206"
    }
  }
}

provider "yandex" {
  cloud_id  = var.cloud_id
  folder_id = var.folder_id
  zone      = var.zone
}
