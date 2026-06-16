# Reserved static IP 93.77.185.62 (tea.macsia.fun). Hand-created, adopted via `tofu import`
# (see README). prevent_destroy guards against an accidental release of the DNS-bound address.
resource "yandex_vpc_address" "teatiers" {
  name                = "teatiers-ip"
  deletion_protection = true

  external_ipv4_address {
    zone_id = var.zone
  }

  lifecycle {
    prevent_destroy = true
  }
}
