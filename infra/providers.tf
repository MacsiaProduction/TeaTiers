# Authentication is taken from the environment so no token ever lands in VCS or state input:
#   export YC_TOKEN="$(yc iam create-token)"   # short-lived IAM token
# cloud/folder/zone are passed explicitly for clarity (they also have YC_* env fallbacks).
provider "yandex" {
  cloud_id  = var.cloud_id
  folder_id = var.folder_id
  zone      = var.zone
}
