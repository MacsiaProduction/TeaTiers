variable "cloud_id" {
  type    = string
  default = "b1gd4m74sk3279ieum3k"
}

variable "folder_id" {
  type    = string
  default = "b1g9o3v21bogpvduaj1l"
}

variable "zone" {
  type    = string
  default = "ru-central1-a"
}

variable "state_bucket" {
  type        = string
  description = "Object Storage bucket for the root config's remote state. Must match the bucket in ../versions.tf."
  default     = "teatiers-tfstate"
}

# Dedicated service account whose static key the s3 backend uses. Least privilege: storage only.
resource "yandex_iam_service_account" "tfstate" {
  name        = "teatiers-tf-state"
  description = "OpenTofu remote-state access to Object Storage."
}

resource "yandex_resourcemanager_folder_iam_member" "tfstate_storage" {
  folder_id = var.folder_id
  role      = "storage.admin"
  member    = "serviceAccount:${yandex_iam_service_account.tfstate.id}"
}

resource "yandex_iam_service_account_static_access_key" "tfstate" {
  service_account_id = yandex_iam_service_account.tfstate.id
  description        = "S3 static key for OpenTofu remote state."
}

resource "yandex_storage_bucket" "tfstate" {
  access_key = yandex_iam_service_account_static_access_key.tfstate.access_key
  secret_key = yandex_iam_service_account_static_access_key.tfstate.secret_key
  bucket     = var.state_bucket

  anonymous_access_flags {
    read = false
    list = false
  }

  # Keep prior state versions so a bad apply can be rolled back.
  versioning {
    enabled = true
  }
}

output "access_key" {
  description = "Set as AWS_ACCESS_KEY_ID for `tofu init` of the root config."
  value       = yandex_iam_service_account_static_access_key.tfstate.access_key
  sensitive   = true
}

output "secret_key" {
  description = "Set as AWS_SECRET_ACCESS_KEY for `tofu init` of the root config."
  value       = yandex_iam_service_account_static_access_key.tfstate.secret_key
  sensitive   = true
}
