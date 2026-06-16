# The Postgres password is generated here (never authored by hand) and stored in Lockbox as the
# record of truth. It is also rendered into the VM's cloud-init .env so the self-hosted DB and the
# server agree on it. The DB port is never published, so this secret only guards the in-host
# compose network; still, it stays out of VCS (only in private state + Lockbox).
resource "random_password" "postgres" {
  length  = 32
  special = false
}

resource "yandex_lockbox_secret" "db" {
  name        = "teatiers-db"
  description = "Catalog Postgres credentials for the teatiers VM."
}

resource "yandex_lockbox_secret_version" "db" {
  secret_id = yandex_lockbox_secret.db.id

  entries {
    key        = "postgres-password"
    text_value = random_password.postgres.result
  }
}
