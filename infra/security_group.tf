# Adopted via `tofu import` (hand-created teatiers-sg). Ingress: SSH + HTTP/HTTPS only; the API is
# reached over 443 (Caddy). 8080 stays closed — only Caddy on the same host talks to the server.
resource "yandex_vpc_security_group" "teatiers" {
  name       = "teatiers-sg"
  network_id = data.yandex_vpc_network.default.id

  ingress {
    protocol       = "TCP"
    description    = "SSH"
    port           = 22
    v4_cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    protocol       = "TCP"
    description    = "HTTP (ACME challenge + redirect to HTTPS)"
    port           = 80
    v4_cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    protocol       = "TCP"
    description    = "HTTPS"
    port           = 443
    v4_cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    protocol       = "ANY"
    description    = "Allow all egress"
    from_port      = 0
    to_port        = 65535
    v4_cidr_blocks = ["0.0.0.0/0"]
  }
}
