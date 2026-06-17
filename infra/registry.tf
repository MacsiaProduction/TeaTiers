# DEPRECATED (decision #76): image delivery moved to ghcr.io. This Yandex Container Registry + the
# puller SA below are no longer used by the publish workflow or the VM. They are kept provisioned to
# avoid destroying the registry holding the currently-running image; REMOVE this whole file (and the
# `registry_id`/`puller_sa_id` outputs) once the VM is confirmed pulling from ghcr (infra/README.md).
resource "yandex_container_registry" "teatiers" {
  name      = "teatiers"
  folder_id = var.folder_id
}

# Least-privilege service account the VM runs as: it may only pull images from this registry.
resource "yandex_iam_service_account" "puller" {
  name        = "teatiers-puller"
  description = "Attached to the teatiers VM; pulls server images from Container Registry."
}

resource "yandex_container_registry_iam_binding" "puller" {
  registry_id = yandex_container_registry.teatiers.id
  role        = "container-registry.images.puller"
  members     = ["serviceAccount:${yandex_iam_service_account.puller.id}"]
}
