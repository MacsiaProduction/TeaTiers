This guide details the integration of Infrastructure-as-Code via Terraform on Yandex Cloud for the TeaTiers project, transitioning from a general host-agnostic design to a target-specific yet containerized configuration.

---

### 1. Provider & Authentication

The official Yandex Cloud Terraform provider is hosted in the Terraform Registry under the namespace `yandex-cloud/yandex`. 

#### Configuration Block
To avoid unexpected behavior from provider API drift, the version is pinned using the `~>` operator to the verified stable release `0.206.0`:

```hcl
# providers.tf
terraform {
  required_version = ">= 1.5.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "~> 0.206.0"
    }
  }
}

provider "yandex" {
  # Authentication configuration
  # For local development, credentials can be set via env variables.
  # For CI, service_account_key_file is recommended.
  service_account_key_file = var.yc_service_account_key_file != "" ? var.yc_service_account_key_file : null
  
  cloud_id                 = var.yc_cloud_id
  folder_id                = var.yc_folder_id
  zone                     = var.yc_zone
}
```

#### Authentication Options & Best Practices
*   **Local Development:** Use the Yandex Cloud CLI (`yc`) authentication. Set the environment variable `YC_TOKEN` by running `export YC_TOKEN=$(yc iam create-token)`. Avoid hardcoding passwords or tokens in files.
*   **CI/CD (e.g., GitHub Actions):** Use a dedicated **Service Account (SA)** key. Export a JSON key for the SA using the Yandex CLI and store it in GitHub Secrets. Inject it using the environment variable `YC_SERVICE_ACCOUNT_KEY_FILE` or by rendering it into a transient file referenced by `service_account_key_file` in the provider block.
*   **Variable Injection:** Pass the structural parameters (`cloud_id`, `folder_id`, `zone`) via a standard `.tfvars` file (not committed to VCS) or as matching environment variables (`YC_CLOUD_ID`, `YC_FOLDER_ID`, `YC_ZONE`).

---

### 2. Compute + Network

Below is the network topology and compute instance setup. It provisions a single VM running Ubuntu 22.04 LTS, bound to a custom VPC network and constrained by a strict security group.

```hcl
# network.tf
resource "yandex_vpc_network" "teatiers_net" {
  name        = "teatiers-network"
  description = "VPC for TeaTiers containerized platform"
}

resource "yandex_vpc_subnet" "teatiers_subnet" {
  name           = "teatiers-subnet-a"
  zone           = var.yc_zone
  network_id     = yandex_vpc_network.teatiers_net.id
  v4_cidr_blocks = ["10.10.10.0/24"]
}

resource "yandex_vpc_security_group" "teatiers_sg" {
  name        = "teatiers-security-group"
  description = "Security rules for TeaTiers VM"
  network_id  = yandex_vpc_network.teatiers_net.id

  ingress {
    protocol       = "TCP"
    description    = "Allow secure incoming HTTPS traffic"
    v4_cidr_blocks = ["0.0.0.0/0"]
    port           = 443
  }

  ingress {
    protocol       = "TCP"
    description    = "Allow HTTP traffic"
    v4_cidr_blocks = ["0.0.0.0/0"]
    port           = 80
  }

  ingress {
    protocol       = "TCP"
    description    = "Allow SSH management from limited IP"
    v4_cidr_blocks = [var.my_management_ip] # Set to your personal IP CIDR, e.g., "198.51.100.50/32"
    port           = 22
  }

  egress {
    protocol       = "ANY"
    description    = "Allow outbound communication to the internet"
    v4_cidr_blocks = ["0.0.0.0/0"]
    # If from_port and to_port are omitted, any port is assumed
  }
}
```

```hcl
# compute.tf
data "yandex_compute_image" "ubuntu" {
  family = "ubuntu-2204-lts" # Standard Ubuntu LTS family
}

resource "yandex_compute_instance" "teatiers_vm" {
  name        = "teatiers-backend-vm"
  platform_id = "standard-v3" # Intel Ice Lake
  zone        = var.yc_zone

  resources {
    cores         = 2
    memory        = 4   # Sane minimum for JVM + local Postgres + Docker
    core_fraction = 50  # Burstable core fraction for lower cost
  }

  boot_disk {
    initialize_params {
      image_id = data.yandex_compute_image.ubuntu.id
      type     = "network-hdd" # Budget option; swap with network-ssd for performance
      size     = 15 # GB
    }
  }

  network_interface {
    subnet_id          = yandex_vpc_subnet.teatiers_subnet.id
    nat                = true # Assigns a public IP to pull registry images and serve traffic
    security_group_ids = [yandex_vpc_security_group.teatiers_sg.id]
  }

  metadata = {
    # Render cloud-init config with Terraform template engine
    user-data = templatefile("${path.module}/templates/cloud-init.yaml", {
      registry_id     = yandex_container_registry.teatiers_registry.id
      repository_name = "teatiers-backend"
      image_tag       = var.image_tag
      secret_id       = yandex_lockbox_secret.teatiers_secrets.id
    })
    ssh-keys = "ubuntu:${var.ssh_public_key}"
  }

  service_account_id = yandex_iam_service_account.teatiers_vm_sa.id
}
```

#### The Cloud-Init Template (`templates/cloud-init.yaml`)
This script executes sequentially at system startup, installing Docker, fetching secrets securely from Yandex Lockbox using the machine's active Service Account credentials, generating the environment variables in a transient `.env` file, and executing the stack.

```yaml
#cloud-config
package_update: true
packages:
  - apt-transport-https
  - ca-certificates
  - curl
  - gnupg
  - lsb-release
  - jq

write_files:
  - path: /opt/teatiers/docker-compose.yml
    content: |
      version: '3.8'
      services:
        app:
          image: cr.yandex/${registry_id}/${repository_name}:${image_tag}
          ports:
            - "80:8080"
          env_file:
            - /opt/teatiers/.env
          restart: always
          depends_on:
            - db

        db:
          image: postgres:15-alpine
          ports:
            - "5432:5432"
          environment:
            POSTGRES_DB: teatiers
            POSTGRES_USER: teatiers_admin
            POSTGRES_PASSWORD: ${db_password_placeholder}
          volumes:
            - pgdata:/var/lib/postgresql/data
          restart: always

      volumes:
        pgdata:

runcmd:
  # Install Docker CE via the official repository
  - mkdir -p /etc/apt/keyrings
  - curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  - echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
  - apt-get update
  - apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

  # Fetch production configuration values from Lockbox using the attached Service Account
  - IAM_TOKEN=$(curl -s -H "Metadata-Flavor: Google" http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token | jq -r .access_token)
  - PAYLOAD=$(curl -s -H "Authorization: Bearer $IAM_TOKEN" https://payload.lockbox.api.cloud.yandex.net/lockbox/v1/secrets/${secret_id}/payload)
  
  # Process and render local configuration
  - mkdir -p /opt/teatiers
  - echo "SPRING_PROFILES_ACTIVE=prod" > /opt/teatiers/.env
  - echo "YANDEX_GPT_API_KEY=$(echo $PAYLOAD | jq -r '.entries[] | select(.key=="yandex_gpt_api_key") | .textValue')" >> /opt/teatiers/.env
  - echo "GROQ_API_KEY=$(echo $PAYLOAD | jq -r '.entries[] | select(.key=="groq_api_key") | .textValue')" >> /opt/teatiers/.env
  - DB_PASS=$(echo $PAYLOAD | jq -r '.entries[] | select(.key=="db_password") | .textValue')
  - echo "DB_PASSWORD=$DB_PASS" >> /opt/teatiers/.env

  # Update the docker-compose template with DB password
  - sed -i "s/\${db_password_placeholder}/$DB_PASS/g" /opt/teatiers/docker-compose.yml

  # Log in to private Container Registry using transient IAM Token
  - echo "$IAM_TOKEN" | docker login --username iam --password-stdin cr.yandex

  # Deploy the Docker Compose service stack
  - cd /opt/teatiers && docker compose up -d
```

---

### 3. Database: Managed vs. Self-Hosted

For a **single-service, low-traffic catalog containing no user data / no PII**, selecting the correct database model is a trade-off between baseline cost and automated system management.

| Criteria | Self-Hosted (PostgreSQL inside Compose) | Managed Service for PostgreSQL (MDB) |
| :--- | :--- | :--- |
| **Backups** | Manual (setup via local pg_dump + cron to Object Storage) | Automated daily backups with Point-In-Time-Recovery (PITR) |
| **Cost** | **0 RUB extra** (runs entirely within the existing VM memory block) | **~1,800–2,200+ RUB/month** (requires dedicated billing overhead for even a single host) |
| **Operations** | Self-managed updates, disk capacity monitoring, and memory tuning | Cloud-native automated OS/DBMS patching, failover, and scaling |
| **Resiliency** | Bound directly to the single VM lifecycle | Decoupled host lifecycle, resilient against single-node service crashes |

#### Recommendation
**Self-Hosted inside the `docker-compose` stack on the VM** is the recommended design. Since the application handles only a read-heavy, publicly populated tea catalog with no user PII or volatile user data, paying for an independent database cluster represents unnecessary cost. The environment remains highly portable, fully respecting host-agnostic architecture principles (Decision #11).

#### Optional Managed-PostgreSQL Setup (If scale or high-availability becomes necessary)
If you decide to migrate to Yandex Managed Service for PostgreSQL, you would remove the database block from the local `docker-compose` template and add the following resources:

```hcl
# database-managed.tf
resource "yandex_mdb_postgresql_cluster" "teatiers_db_cluster" {
  name        = "teatiers-postgres-cluster"
  environment = "PRESTABLE" # Use PRODUCTION for high-availability constraints
  network_id  = yandex_vpc_network.teatiers_net.id

  config {
    version = 15
    resources {
      resource_preset_id = "s2.micro" # 2 vCPUs, 8 GB RAM (smallest non-burstable)
      disk_type_id       = "network-ssd"
      disk_size          = 10 # GB
    }
  }

  host {
    zone      = var.yc_zone
    subnet_id = yandex_vpc_subnet.teatiers_subnet.id
  }
}

resource "yandex_mdb_postgresql_database" "teatiers_db" {
  cluster_id = yandex_mdb_postgresql_cluster.teatiers_db_cluster.id
  name       = "teatiers"
  owner      = yandex_mdb_postgresql_user.teatiers_db_user.name
}

resource "yandex_mdb_postgresql_user" "teatiers_db_user" {
  cluster_id = yandex_mdb_postgresql_cluster.teatiers_db_cluster.id
  name       = "teatiers_admin"
  password   = var.db_password # Injected securely from sensitive variables
}
```

---

### 4. Secrets & State Management

#### Yandex Lockbox Configuration
A single secret container is provisioned in Lockbox. Plaintext values are injected via sensitive variables and mapped directly to a secure secret version.

```hcl
# secrets.tf
resource "yandex_lockbox_secret" "teatiers_secrets" {
  name        = "teatiers-secrets"
  description = "Decoupled secret payload container for TeaTiers Application"
}

resource "yandex_lockbox_secret_version" "teatiers_secrets_v1" {
  secret_id = yandex_lockbox_secret.teatiers_secrets.id

  entries {
    key        = "yandex_gpt_api_key"
    text_value = var.yandex_gpt_api_key
  }

  entries {
    key        = "groq_api_key"
    text_value = var.groq_api_key
  }

  entries {
    key        = "db_password"
    text_value = var.db_password
  }
}
```

#### Keep Secrets out of State files
While marking input variables as `sensitive = true` prevents Terraform from printing them in command-line outputs, **Terraform state files still record these values in cleartext**. 
*   **Best Practice Recommendation:** Create the `yandex_lockbox_secret` resource shell with Terraform, but manage the actual values and secret versions out-of-band via the Yandex Cloud Web Console or Yandex CLI. This prevents API keys and DB credentials from ever entering git repositories or the terraform state backend.

#### Remote State with Locking
To share state between local machines and CI/CD runners, we use the S3-compatible **Yandex Object Storage** backend. Concurrency conflicts are prevented using **Yandex Database (YDB)** in serverless mode, providing DynamoDB-compatible table locking.

```hcl
# backend-config.tf
# Note: Variables cannot be resolved inside a backend configuration block.
# These values must be declared as static literals or passed via -backend-config command line switches.
terraform {
  backend "s3" {
    endpoint = "storage.yandexcloud.net"
    bucket   = "teatiers-terraform-state"
    region   = "us-east-1" # Kept for standard S3 library compatibility
    key      = "prod/terraform.tfstate"

    # YDB locking config (DynamoDB API interface)
    dynamodb_endpoint = "https://docapi.serverless.yandexcloud.net/ru-central1/b1g.../etd..."
    dynamodb_table    = "tf-state-locks"

    skip_region_validation      = true
    skip_credentials_validation = true
    skip_requesting_account_id  = true # Essential parameter for Yandex Cloud S3
    skip_metadata_api_check     = true
  }
}
```

---

### 5. Deployment Flow & Alternatives

```
[Developer Push] -> [GitHub Actions Build] -> [Yandex Container Registry]
                                                         |
                                                 (VM Cloud-Init Pull)
                                                         |
                                              [VM running Docker Compose]
```

#### Comparison of Execution Environments
1.  **VM + Docker Compose (Recommended Setup):**
    *   **Reason:** The Spring Boot + JVM stack has a noticeable start-up overhead (10–20 seconds). Under a Serverless Container topology, cold starts degrade response times when scaling from zero. A persistent burstable VM maintains a warm JVM state while enabling multi-container co-location (running both Postgres and the Kotlin app on the same VM node for zero database cloud resource fee).
2.  **Yandex Serverless Containers:**
    *   **When to prefer:** If you migrate database storage off the container layer (e.g., using Managed Postgres or Serverless YDB) and build the application as a native binary (such as Spring Native/GraalVM) to achieve millisecond-level cold starts. This eliminates system OS maintenance entirely.

```hcl
# registry.tf
resource "yandex_container_registry" "teatiers_registry" {
  name      = "teatiers-registry"
  folder_id = var.yc_folder_id
}
```

---

### 6. CI/CD (GitHub Actions Integration)

This GitHub Actions workflow handles secure infrastructure provisioning and application containerization.

```yaml
# .github/workflows/deploy.yml
name: Continuous Integration & Deployment

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

env:
  REGISTRY_ID: "crp..." # Insert your Yandex Container Registry ID
  IMAGE_NAME: "teatiers-backend"

jobs:
  terraform:
    name: "Infrastructure Pipeline"
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Source Code
        uses: actions/checkout@v4

      - name: Configure Terraform Credentials
        run: |
          echo '${{ secrets.YC_SA_JSON_CREDENTIALS }}' > sa-key.json
          # Export standard AWS variables so the S3 backend can authenticate
          echo "AWS_ACCESS_KEY_ID=${{ secrets.YC_STORAGE_ACCESS_KEY }}" >> $GITHUB_ENV
          echo "AWS_SECRET_ACCESS_KEY=${{ secrets.YC_STORAGE_SECRET_KEY }}" >> $GITHUB_ENV
        
      - name: Setup Terraform
        uses: hashicorp/setup-terraform@v3

      - name: Terraform Init
        run: terraform init

      - name: Terraform Format Validate
        run: terraform fmt -check && terraform validate

      - name: Terraform Plan
        run: terraform plan -var="yc_service_account_key_file=sa-key.json" -out=tfplan

      - name: Terraform Apply
        if: github.ref == 'refs/heads/main' && github.event_name == 'push'
        run: terraform apply -auto-approve tfplan

  build-and-push:
    name: "Package & Publish Container"
    needs: terraform
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Source Code
        uses: actions/checkout@v4

      # Official Yandex Cloud Registry authentication action
      - name: Log in to Yandex Container Registry
        uses: yc-actions/yc-cr-login@v2
        with:
          yc-sa-json-credentials: ${{ secrets.YC_SA_JSON_CREDENTIALS }}

      - name: Build, Tag and Push Docker Image
        run: |
          docker build -t cr.yandex/${{ env.REGISTRY_ID }}/${{ env.IMAGE_NAME }}:${{ github.sha }} .
          docker push cr.yandex/${{ env.REGISTRY_ID }}/${{ env.IMAGE_NAME }}:${{ github.sha }}
```

---

### 7. Cost & Trial Optimization

Yandex Cloud offers an initial free trial grant of **4,000 RUB** (valid for 60 days). However, unlike some serverless components, standard VM compute and persistent network disks do not have a permanent "always free" tier.

#### Monthly Cost Estimate (Base: standard-v3, zone: ru-central1-a)
Calculated based on current pricing rates:

*   **Option A: VM with Self-Hosted PostgreSQL (Recommended)**
    *   **Burstable VM** (standard-v3, 2 vCPU, 50% core fraction, 4 GB RAM): **~550 RUB/month**
    *   **Network HDD** (15 GB Boot Volume): **~40 RUB/month**
    *   **Static Public IPv4 Address**: **~130 RUB/month**
    *   **Yandex Container Registry / Object Storage** (Storage < 2 GB + limited transfer): **~15 RUB/month**
    *   **Yandex Lockbox API**: **0 RUB** (free tier covers up to 10,000 API requests per month)
    *   **Total Recommended Cost: ~735 RUB/month (~$8 USD)**

*   **Option B: VM with Managed Database Service (MDB)**
    *   **Application Host VM** (2 vCPU, 50% core fraction, 2 GB RAM): **~350 RUB/month**
    *   **Managed PostgreSQL Instance** (Host Preset: s2.micro, 2 vCPU, 8 GB RAM, 10 GB SSD disk): **~2,100 RUB/month**
    *   **Network storage & static routing fees**: **~170 RUB/month**
    *   **Total Cost: ~2,620 RUB/month (~$30 USD)**

Choosing **Option A** is the most cost-effective and viable configuration for this stage of development.

---

### 8. System Gotchas & Constraints

1.  **Network NAT Requirement for VM Updates & Pulls:**
    If a compute instance network interface is not configured with a public IP (`nat = true` is omitted) and there is no NAT Gateway bound to the subnet, the instance will be completely isolated. It will fail to pull container updates from Yandex Container Registry or download packages during the cloud-init bootstrap phase.
2.  **S3 Backend Environment Context:**
    The Terraform S3 backend configuration block is processed during initialization and cannot reference variables or local values declared in the code. S3 Access Keys must be declared directly as environments (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) in the terminal execution context or the CI runner.
3.  **Strict Subnet Zone Isolation:**
    Unlike some platforms where subnets can span zones, subnets in Yandex Cloud are bound to a single availability zone. If a compute resource is provisioned in `ru-central1-a`, the referenced subnet ID must be explicitly created in `ru-central1-a`.
4.  **Security Group Association Dependencies:**
    If you attempt to destroy or significantly alter a security group attached to an active compute instance, the Yandex API will reject the operation or hang indefinitely. You must delete or stop the bound VM first before detaching the primary security group rules.
5.  **State Locking with YDB Table Names:**
    When setting up state locking using Yandex Managed Database (YDB), the lock table *must* have a primary key named exactly `LockID` of type String. Creating a YDB database with different specifications will fail during the initial `terraform init` lock verification.

---

### Reference Sources & Documentation Links

1.  [Getting started with Terraform on Yandex Cloud](https://yandex.cloud/en/docs/tutorials/infrastructure-management/terraform-quickstart) (Last Checked: May 2026)
2.  [Terraform Registry: Yandex Provider Documentation](https://registry.terraform.io/providers/yandex-cloud/yandex/latest/docs) (Version 0.206.0, May 2026)
3.  [Uploading Terraform States to Yandex Object Storage](https://yandex.cloud/en/docs/storage/operations/backends/terraform) (Last Checked: June 2026)
4.  [Locking Terraform States using Yandex Managed Service for YDB](https://yandex.cloud/en/docs/storage/tutorials/terraform-state-lock) (Last Checked: May 2026)
5.  [Container Registry Authentication Documentation](https://yandex.cloud/en/docs/container-registry/operations/authentication) (Last Checked: May 2026)
6.  [Managed Service for PostgreSQL Pricing Policy](https://yandex.cloud/en/docs/managed-postgresql/pricing) (Last Checked: May 2026)

### Uncertain / Could Not Verify List

*   **Native S3 Backend Locking Compatibility:** We could not verify with absolute certainty whether Yandex Object Storage has introduced native S3 API lockfile support (`use_lockfile` parameter) to fully bypass YDB for locking in current 2026 releases. The YDB Document API remains the officially documented method for state locking in Yandex Cloud.
*   **Availability of standard-v3 Core Fractions:** Exact configurations for the `standard-v3` platform pricing were subject to fluctuation; standard burstable fractions (`20%` vs `50%`) can vary slightly by AZ.