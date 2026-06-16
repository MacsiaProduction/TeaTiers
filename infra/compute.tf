locals {
  cloud_init = templatefile("${path.module}/templates/cloud-init.yaml.tftpl", {
    compose_yml       = file("${path.module}/deploy/docker-compose.prod.yml")
    caddyfile         = file("${path.module}/deploy/Caddyfile")
    domain            = var.domain
    acme_email        = var.acme_email
    server_image      = var.server_image
    postgres_db       = var.postgres_db
    postgres_user     = var.postgres_user
    postgres_password = random_password.postgres.result
  })
}

# The VM was hand-created (infra/README) and is adopted via `tofu import`. The config must match
# the live instance closely enough that `tofu plan` shows no destroy/replace before any apply
# (prevent_destroy is the backstop). allow_stopping_for_update lets the provider stop/start the VM
# to attach the puller SA and refresh metadata.
resource "yandex_compute_instance" "teatiers" {
  name                      = "teatiers"
  platform_id               = "standard-v3"
  zone                      = var.zone
  service_account_id        = yandex_iam_service_account.puller.id
  allow_stopping_for_update = true

  resources {
    cores         = 2
    core_fraction = 50
    memory        = 4
  }

  boot_disk {
    initialize_params {
      image_id = "fd8d509adap8j23buglo"
      size     = 30
      type     = "network-hdd"
    }
  }

  network_interface {
    subnet_id          = data.yandex_vpc_subnet.default.subnet_id
    nat                = true
    nat_ip_address     = "93.77.185.62"
    security_group_ids = [yandex_vpc_security_group.teatiers.id]
  }

  # ssh-keys preserved verbatim (public key; this is how `ssh yc-user@<ip>` works). user-data is
  # intentionally NOT set on this adopted, already-running VM: changing it cannot re-run cloud-init,
  # and keeping metadata fully-known guarantees this in-place update only attaches the puller SA
  # without ever touching SSH access. Provisioning is done over SSH (see README); local.cloud_init
  # is kept rendered for a future from-scratch recreate (wire it back into metadata then).
  metadata = {
    ssh-keys           = "yc-user:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIObyheFWpEq0Q3AiPx4jHP8Gbxf/ZluhZUFYy9neDmcy teatiers\n"
    serial-port-enable = "1"
  }

  lifecycle {
    prevent_destroy = true
  }
}
