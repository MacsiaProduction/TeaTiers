variable "cloud_id" {
  type        = string
  description = "Yandex Cloud id."
  default     = "b1gd4m74sk3279ieum3k"
}

variable "folder_id" {
  type        = string
  description = "Yandex Cloud folder id."
  default     = "b1g9o3v21bogpvduaj1l"
}

variable "zone" {
  type        = string
  description = "Default compute zone."
  default     = "ru-central1-a"
}

variable "domain" {
  type        = string
  description = "Public domain the API is served on (Caddy obtains a Let's Encrypt cert for it)."
  default     = "tea.macsia.fun"
}

variable "acme_email" {
  type        = string
  description = "Contact email for Let's Encrypt / ACME registration."
  default     = "MacsiaProduction@yandex.ru"
}

variable "server_image" {
  type        = string
  description = "Fully-qualified server image ref, e.g. ghcr.io/macsiaproduction/teatiers-server:latest (decision #76). Empty until the first image is published to ghcr."
  default     = ""
}

variable "ocr_sidecar_image" {
  type        = string
  description = "Fully-qualified OCR sidecar image ref (decision #106/#108). docker-compose.prod.yml reads it as OCR_SIDECAR_IMAGE; without this, cloud-init would render an empty value and the ocr service would fail to start. Empty until the sidecar image is published."
  default     = ""
}

variable "ssh_public_key" {
  type        = string
  description = "SSH public key authorized on the VM (contents, not a path). Empty keeps the existing OS Login access."
  default     = ""
}

variable "backup_bucket" {
  type        = string
  description = "Globally-unique Object Storage bucket name for off-box DB backups (decision #77)."
  default     = "teatiers-backups"
}

variable "backup_retention_days" {
  type        = number
  description = "Days to keep off-box dumps before the bucket lifecycle rule expires them."
  default     = 30
}

variable "postgres_db" {
  type        = string
  description = "Catalog database name."
  default     = "teatiers"
}

variable "postgres_user" {
  type        = string
  description = "Catalog database user."
  default     = "teatiers"
}
