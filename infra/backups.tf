# Off-box DB backup bucket (decision #77, resolves #70.3). The on-VM `deploy/backup.sh` already does a
# daily pg_dump and can upload off-box when BACKUP_S3_URI + AWS_* are set; this provisions the bucket +
# a dedicated backup SA so that upload can actually be turned on. Matters because /resolve now writes
# catalog rows that aren't in the committed seed, so the DB is no longer reproducible from VCS alone.

# Dedicated SA whose static key the VM uses to upload dumps. Scoped to storage.uploader (OPS-P1-5):
# the static key lives on the VM in backup.env, so a leak must NOT be able to delete buckets — uploader
# can only PUT objects (the `aws s3 cp` the backup script does), never create/delete a bucket, so a leaked
# key can't wipe this bucket OR the Terraform state bucket. The bucket itself is created/managed by the
# provider's operator token (YC_TOKEN, folder storage.admin) below, not by this SA's key.
resource "yandex_iam_service_account" "backup" {
  name        = "teatiers-backup"
  description = "Uploads VM Postgres dumps to the off-box backups bucket."
}

resource "yandex_resourcemanager_folder_iam_member" "backup_storage" {
  folder_id = var.folder_id
  role      = "storage.uploader"
  member    = "serviceAccount:${yandex_iam_service_account.backup.id}"
}

resource "yandex_iam_service_account_static_access_key" "backup" {
  service_account_id = yandex_iam_service_account.backup.id
  description        = "S3 static key for off-box DB backups (BACKUP_S3 creds on the VM); upload-only."
}

resource "yandex_storage_bucket" "backups" {
  # No access_key/secret_key here (OPS-P1-5): the bucket is created + configured via the provider's
  # operator IAM token (YC_TOKEN). The upload-only backup SA can't manage buckets, by design.
  bucket = var.backup_bucket

  anonymous_access_flags {
    read = false
    list = false
  }

  # Off-box retention: drop dumps older than the window so the bucket doesn't grow unbounded
  # (the on-VM script prunes local copies separately via RETENTION_DAYS).
  lifecycle_rule {
    id      = "expire-old-dumps"
    enabled = true
    expiration {
      days = var.backup_retention_days
    }
  }
}

output "backup_bucket_uri" {
  description = "Set as BACKUP_S3_URI in the VM's /opt/teatiers/backup.env."
  value       = "s3://${yandex_storage_bucket.backups.bucket}/teatiers"
}

output "backup_access_key" {
  description = "Set as AWS_ACCESS_KEY_ID in the VM's /opt/teatiers/backup.env."
  value       = yandex_iam_service_account_static_access_key.backup.access_key
  sensitive   = true
}

output "backup_secret_key" {
  description = "Set as AWS_SECRET_ACCESS_KEY in the VM's /opt/teatiers/backup.env."
  value       = yandex_iam_service_account_static_access_key.backup.secret_key
  sensitive   = true
}
