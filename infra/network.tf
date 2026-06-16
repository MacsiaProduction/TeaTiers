# The default network + zone subnet pre-exist (created with the folder). We only read them; the
# VM's interface attaches to this subnet.
data "yandex_vpc_network" "default" {
  network_id = "enpsmc85p8ladckg91on"
}

data "yandex_vpc_subnet" "default" {
  subnet_id = "e9bo6q4hk9vu1ndsta4c"
}
