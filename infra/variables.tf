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
  description = "Fully-qualified server image ref in Container Registry, e.g. cr.yandex/<reg-id>/teatiers-server:<tag>. Empty until the registry exists and the first image is pushed."
  default     = ""
}

variable "ssh_public_key" {
  type        = string
  description = "SSH public key authorized on the VM (contents, not a path). Empty keeps the existing OS Login access."
  default     = ""
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
