# Container Registry holds the server image; the VM pulls from it (decision: image delivery = YCR).
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
