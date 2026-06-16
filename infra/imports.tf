# Config-driven import of the hand-created resources (infra/README). Run `tofu plan` and confirm
# zero destroy/replace before `tofu apply`. These blocks are no-ops once state holds the resources
# and may be deleted afterwards.
import {
  to = yandex_compute_instance.teatiers
  id = "fhm7ai7c90647397qa43"
}

import {
  to = yandex_vpc_security_group.teatiers
  id = "enp7rrdpr5ur1rra2gqa"
}

import {
  to = yandex_vpc_address.teatiers
  id = "e9brrf2n7tuau8536vpv"
}
