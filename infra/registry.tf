# YCR RETIRED (decision #82): image delivery is ghcr.io (#76) and the VM now pulls from there, so the
# Container Registry + its puller IAM binding are removed. The service account below is KEPT — it is the
# VM's `service_account_id` (compute.tf), so destroying it would force a VM update, and (per the README)
# it can stay attached harmlessly now that it no longer pulls from any registry.
resource "yandex_iam_service_account" "puller" {
  name        = "teatiers-puller"
  description = "The teatiers VM's service account (formerly the Container Registry puller; YCR retired #82)."
}
