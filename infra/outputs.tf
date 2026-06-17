output "server_image_repo" {
  description = "Image repository the server is published to (ghcr.io, decision #76; append :<tag>)."
  value       = "ghcr.io/macsiaproduction/teatiers-server"
}

output "puller_sa_id" {
  description = "Service account id attached to the VM (Container Registry puller)."
  value       = yandex_iam_service_account.puller.id
}

output "vm_external_ip" {
  description = "Public IP of the VM (DNS A record target for var.domain)."
  value       = "93.77.185.62"
}

output "vm_cloud_init" {
  description = "Rendered provisioning script (cloud-init) for the VM; contains the DB password. Used to provision the adopted VM over SSH and for a future from-scratch recreate."
  value       = local.cloud_init
  sensitive   = true
}
