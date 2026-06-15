# Развёртывание TeaTiers на Yandex Cloud через Terraform: рекомендации и скелет конфигурации

## Главные выводы

Для проекта TeaTiers (одиночный low-traffic сервис, Kotlin + Spring Boot, PostgreSQL) на Yandex Cloud оптимальной минимальной конфигурацией является **одна ВМ Compute Cloud + self-hosted PostgreSQL в Docker Compose**, что обойдётся ~1 200–1 700 ₽/мес. Managed PostgreSQL добавляет ~4 000–5 400 ₽/мес и оправдан только при необходимости автоматических бекапов и managed-фейловера. Для минимальных затрат на старте можно рассмотреть Yandex Serverless Containers (бесплатно до 1 млн вызовов/мес), но он менее удобен для постоянного фонового соединения с внешними AI-сервисами (YandexGPT, Groq через VPN). Terraform-провайдер рекомендуется зафиксировать на версии **0.206.0** [github.com](https://github.com/yandex-cloud/terraform-provider-yandex/releases)[yandex.cloud](https://yandex.cloud/ru/docs/terraform/release-notes). Все секреты хранятся в Yandex Lockbox [yandex.cloud](https://yandex.cloud/ru/docs/terraform/resources/lockbox_secret).

---

## 1. Провайдер и аутентификация

Официальный Terraform-провайдер для Yandex Cloud:

- Source: `yandex-cloud/yandex`
- Рекомендуемая версия для фиксации: **0.206.0** (выпущен 28.05.2026) [github.com](https://github.com/yandex-cloud/terraform-provider-yandex/releases)[yandex.cloud](https://yandex.cloud/ru/docs/terraform/release-notes)
- `required_providers`:

```hcl
terraform {
  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "=0.206.0"
    }
  }
}
```

**Аутентификация**:

- **Рекомендованный способ** (в т.ч. для CI): сервисный аккаунт + авторизованный ключ. На локальной машине используется `YC_SERVICE_ACCOUNT_KEY_FILE`, в CI — содержимое файла ключа в `secrets` [github.com](https://github.com/yandex-cloud/docs/blob/master/ru/terraform/authentication.md)[yandex.cloud](https://yandex.cloud/ru/docs/terraform/authentication).
- **Переменные окружения**: `YC_CLOUD_ID`, `YC_FOLDER_ID`, `YC_TOKEN` (IAM-токен, менее удобен для долгих сессий).
- **Имперсонация** (для локальной разработки): `yc iam create-token --impersonate-service-account-id <SA_ID>` [yandex.cloud](https://yandex.cloud/ru/docs/terraform/authentication).

```hcl
provider "yandex" {
  cloud_id  = var.cloud_id
  folder_id = var.folder_id
  zone      = var.zone
}
```

---

## 2. Скелет Terraform-конфигурации (VM + Docker Compose + self-hosted PG)

Это полный минимальный набор ресурсов для запуска стека на одной ВМ. Всё, кроме секретов, можно разместить в одном `.tf`-файле.

```hcl
# providers.tf – указан выше, добавить блок provider

# ---------- Locals ----------
locals {
  zone        = "ru-central1-a"
  username    = "teatiers"
  ssh_pub_key = file("~/.ssh/id_ed25519.pub")
  vm_name     = "teatiers-vm"
}

# ---------- VPC ----------
resource "yandex_vpc_network" "this" {
  name = "teatiers-network"
}

resource "yandex_vpc_subnet" "this" {
  name           = "teatiers-subnet"
  zone           = local.zone
  v4_cidr_blocks = ["10.0.1.0/24"]
  network_id     = yandex_vpc_network.this.id
}

# ---------- Security Group ----------
resource "yandex_vpc_security_group" "this" {
  name        = "teatiers-sg"
  description = "SSH + HTTP/HTTPS + egress all"
  network_id  = yandex_vpc_network.this.id
}

resource "yandex_vpc_security_group_rule" "ssh" {
  security_group_binding = yandex_vpc_security_group.this.id
  direction              = "ingress"
  protocol               = "TCP"
  port                   = 22
  v4_cidr_blocks         = ["<ВАШ_IP_АДРЕС>/32"]  # заменить на свой IP
}

resource "yandex_vpc_security_group_rule" "http" {
  security_group_binding = yandex_vpc_security_group.this.id
  direction              = "ingress"
  protocol               = "TCP"
  port                   = 80
  v4_cidr_blocks         = ["0.0.0.0/0"]
}

resource "yandex_vpc_security_group_rule" "https" {
  security_group_binding = yandex_vpc_security_group.this.id
  direction              = "ingress"
  protocol               = "TCP"
  port                   = 443
  v4_cidr_blocks         = ["0.0.0.0/0"]
}

resource "yandex_vpc_security_group_rule" "egress" {
  security_group_binding = yandex_vpc_security_group.this.id
  direction              = "egress"
  protocol               = "ANY"
  v4_cidr_blocks         = ["0.0.0.0/0"]
}

# ---------- Compute Instance ----------
resource "yandex_compute_instance" "this" {
  name        = local.vm_name
  platform_id = "standard-v3"          # Intel Ice Lake
  zone        = local.zone
  # Сервисный аккаунт для доступа к Lockbox и Container Registry
  service_account_id = yandex_iam_service_account.vm_sa.id

  resources {
    cores  = 2
    memory = 2
    # core_fraction = 20  # для экономии (20% vCPU) – см. раздел "Стоимость"
  }

  boot_disk {
    initialize_params {
      image_id = data.yandex_compute_image.ubuntu.image_id
      size     = 15
      type     = "network-hdd"
    }
  }

  network_interface {
    subnet_id = yandex_vpc_subnet.this.id
    nat       = true   # публичный IP для доступа из интернета
  }

  metadata = {
    user-data = templatefile("cloud-init.yaml", {
      username    = local.username
      ssh_pub_key = local.ssh_pub_key
    })
  }
}

# ---------- Data Source: Ubuntu 24.04 ----------
data "yandex_compute_image" "ubuntu" {
  family = "ubuntu-2404-lts-oslogin"
}

# ---------- Container Registry ----------
resource "yandex_container_registry" "this" {
  name      = "teatiers-registry"
  folder_id = var.folder_id
}

# ---------- Service Account for VM ----------
resource "yandex_iam_service_account" "vm_sa" {
  name        = "teatiers-vm-sa"
  description = "Service account for TeaTiers VM"
}

# Роль: читать из Container Registry
resource "yandex_resourcemanager_folder_iam_member" "cr_puller" {
  folder_id = var.folder_id
  role      = "container-registry.images.puller"
  member    = "serviceAccount:${yandex_iam_service_account.vm_sa.id}"
}

# Роль: читать секреты Lockbox
resource "yandex_resourcemanager_folder_iam_member" "lockbox_viewer" {
  folder_id = var.folder_id
  role      = "lockbox.payloadViewer"
  member    = "serviceAccount:${yandex_iam_service_account.vm_sa.id}"
}

# ---------- Lockbox Secrets ----------
resource "yandex_lockbox_secret" "teatiers" {
  name        = "teatiers-secrets"
  description = "Secrets for TeaTiers backend"
}

resource "yandex_lockbox_secret_version" "teatiers" {
  secret_id = yandex_lockbox_secret.teatiers.id
  entries {
    key   = "YANDEXGPT_API_KEY"
    value = var.yandexgpt_api_key  # sensitive variable, не хранить в репозитории
  }
  entries {
    key   = "GROQ_API_KEY"
    value = var.groq_api_key
  }
  entries {
    key   = "DB_PASSWORD"
    value = var.db_password
  }
}
```

### cloud-init.yaml (user-data)

Этот файл устанавливает Docker, docker-compose, логинится в Container Registry через сервисный аккаунт и запускает `docker compose up`. Идентификатор секрета из Lockbox [yandex.cloud](https://yandex.cloud/ru/docs/compute/operations/vm-create/create-with-lockbox-secret) необходимо передать:

```yaml
#cloud-config
datasource:
  Ec2:
    strict_id: false
    secrets:
      teatiers_secret: <ИДЕНТИФИКАТОР_СЕКРЕТА>

users:
  - name: ${username}
    groups: sudo, docker
    shell: /bin/bash
    sudo: ALL=(ALL) NOPASSWD:ALL
    ssh_authorized_keys:
      - ${ssh_pub_key}

packages:
  - docker.io
  - docker-compose-v2

runcmd:
  - systemctl enable docker
  - systemctl start docker
  # Аутентификация в Container Registry через IAM-токен ВМ
  - |
    IAM_TOKEN=$(curl -H "Metadata-Flavor: Google" http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token | cut -d'"' -f4)
    echo "$IAM_TOKEN" | docker login --username iam --password-stdin cr.yandex
  # Создать docker-compose.yml из секретов или скачать готовый
  - |
    cat > /home/${username}/docker-compose.yml << EOF
    version: '3.8'
    services:
      app:
        image: cr.yandex/<REGISTRY_ID>/teatiers:latest
        ports:
          - "80:8080"
          - "443:8443"
        environment:
          - DB_HOST=db
          - DB_PORT=5432
          - DB_NAME=teatiers
          - DB_USER=teatiers
          - DB_PASSWORD=\${teatiers_secret["DB_PASSWORD"]}
          - YANDEXGPT_API_KEY=\${teatiers_secret["YANDEXGPT_API_KEY"]}
          - GROQ_API_KEY=\${teatiers_secret["GROQ_API_KEY"]}
        depends_on:
          - db
      db:
        image: postgres:16
        environment:
          - POSTGRES_PASSWORD=\${teatiers_secret["DB_PASSWORD"]}
          - POSTGRES_USER=teatiers
          - POSTGRES_DB=teatiers
        volumes:
          - pgdata:/var/lib/postgresql/data
    volumes:
      pgdata:
    EOF
  - docker compose -f /home/${username}/docker-compose.yml up -d
```

**Важно**: секреты из Lockbox становятся доступны среде выполнения только через API. В cloud-init они подставляются через `datasource.Ec2.secrets` [yandex.cloud](https://yandex.cloud/ru/docs/compute/operations/vm-create/create-with-lockbox-secret). Для production-нагрузки рекомендуется использовать более безопасный механизм (например, инициализацию через скрипт с вызовом Lockbox API).

---

## 3. Рекомендуемая топология: VM + self-hosted PG vs Managed PG vs Serverless Containers

| Критерий | VM + self-hosted PG (в Docker) | Managed PostgreSQL (1 хост s3-c2-m8) | Serverless Containers |
|---|---|---|---|
| **Стоимость в месяц** (RUB) | ~1 200–1 700 [yandex.cloud](https://yandex.cloud/ru/docs/compute/pricing) | ~5 400–6 600 [yandex.cloud](https://yandex.cloud/ru/docs/managed-postgresql/pricing) | 0–500 ₽ при низкой нагрузке [yandex.cloud](https://yandex.cloud/ru/docs/billing/concepts/serverless-free-tier)[yandex.cloud](https://yandex.cloud/ru/docs/serverless-containers/pricing) |
| **Автоматические бекапы** | Нет (настраиваются отдельно) | Да [yandex.cloud](https://yandex.cloud/ru/docs/managed-postgresql/pricing) | Неприменимо |
| **Fault tolerance** | Нет (один инстанс) | Один хост — тоже без failover; кластер из 2+ хостов резко дороже | Автоматическое масштабирование |
| **Операционные расходы** | Обновления ОС, Docker, PG вручную | Обновления managed-сервисом | Минимальные |
| **Подходит для** | Pet-проект, dev-среда, минимальный бюджет | Production с managed-сервисом | Очень низкая нагрузка, бессерверная модель |
| **Постоянное соединение с AI (YandexGPT, Groq через VPN)** | Поддерживается (постоянный процесс) | Поддерживается (VM всегда включена) | Сложнее: контейнеры "засыпают" при бездействии, время холодного старта |
| **Лицензионные ограничения** | Нет | Нет | Нет |

**Рекомендация для TeaTiers**: начать с **VM + self-hosted PostgreSQL** (наименьшая стоимость). Если потребуются managed-бекапы, перейти на Managed PostgreSQL — добавление кластера в Terraform не ломает существующую инфраструктуру. Serverless Containers рассматривать только для отдельного low-load эндпоинта (например, API для редких запросов), но не для постоянно работающего бэкенда с внешними AI-вызовами.

---

## 4. Managed PostgreSQL (опциональный блок)

Если выбран Managed PostgreSQL, добавляется ресурс `yandex_mdb_postgresql_cluster` [yandex.cloud](https://yandex.cloud/ru/docs/terraform/resources/mdb_postgresql_cluster):

```hcl
resource "yandex_mdb_postgresql_cluster" "this" {
  name               = "teatiers-pg"
  environment        = "PRESTABLE"  # или "PRODUCTION"
  network_id         = yandex_vpc_network.this.id
  security_group_ids = [yandex_vpc_security_group.this.id]

  config {
    version = 15
    resources {
      resource_preset_id = "s3-c2-m8"    # Intel Ice Lake, 2 vCPU, 8 GB RAM
      disk_size          = 10            # ГБ
      disk_type_id       = "network-hdd" # минимальная стоимость
    }
  }

  host {
    zone      = local.zone
    subnet_id = yandex_vpc_subnet.this.id
    # assign_public_ip = false – по умолчанию внутренний IP
  }
}
```

**Стоимость** (1 хост s3-c2-m8, 10 ГБ network-hdd):  
- Вычислительные ресурсы: 720 ч × (2×1,8792 + 8×0,5072) ≈ 5 325 ₽/мес [yandex.cloud](https://yandex.cloud/ru/docs/managed-postgresql/pricing)  
- Хранилище: 10 ГБ × 3,744 ₽/ГБ/мес ≈ 37 ₽/мес [yandex.cloud](https://yandex.cloud/ru/docs/managed-postgresql/pricing)  
- **Итого ~5 362 ₽/мес**  

---

## 5. Remote state (backend S3) и блокировка

**Minimal backend S3 (без блокировки)**:  

```hcl
terraform {
  backend "s3" {
    endpoints = {
      s3 = "storage.yandexcloud.net"
    }
    bucket                      = "teatiers-tfstate"
    region                      = "ru-central1"
    key                         = "prod/terraform.tfstate"
    skip_region_validation      = true
    skip_credentials_validation = true
    skip_requesting_account_id  = true
    skip_s3_checksum            = true
  }
}
```

**Для блокировки (state locking)** необходимо дополнительно создать базу YDB с таблицей `LockID` и добавить параметры DynamoDB в backend [yandex.cloud](https://yandex.cloud/ru/docs/tutorials/infrastructure-management/terraform-state-lock):

```hcl
terraform {
  backend "s3" {
    # ... (те же, что выше)
    dynamodb_endpoint = "docapi.serverless.yandexcloud.net/ru-central1/<YDB_DATABASE_ID>"
    dynamodb_table    = "state-lock-table"
  }
}
```

Блокировка предотвращает одновременные изменения состояния из разных сессий. Без неё при параллельных `terraform apply` возможна потеря обновлений.

---

## 6. Поток CI/CD (GitHub Actions + Yandex Cloud)

Рекомендуемая схема [habr.com](https://habr.com/ru/sandbox/279222/):

1. **Push в main** → запускается GitHub Actions workflow.
2. **Сборка Docker-образа** → тегирование (например, `sha-${GITHUB_SHA}`) → push в Yandex Container Registry.
3. **Terraform**: `terraform plan` на PR, `terraform apply` на main.
4. **Обновление ВМ**: после apply можно выполнить `ssh docker compose pull && docker compose up -d` или использовать cloud-init с перезагрузкой.

Пример фрагмента workflow:

```yaml
- name: Configure Yandex Cloud
  uses: yc-actions/yc-configure@v1
  with:
    yc-sa-key-json: ${{ secrets.YC_SA_KEY_JSON }}

- name: Login to Container Registry
  run: yc container registry configure-docker

- name: Build and push
  run: |
    docker build -t cr.yandex/${{ secrets.YC_REGISTRY_ID }}/teatiers:sha-${{ github.sha }} .
    docker push cr.yandex/${{ secrets.YC_REGISTRY_ID }}/teatiers:sha-${{ github.sha }}
```

**Важно**: сервисный аккаунт для GitHub Actions должен иметь роли:
- `container-registry.images.pusher` (на каталог);
- `compute.editor`, `vpc.admin`, `iam.serviceAccounts.user` (для Terraform);
- `lockbox.admin` (для создания/обновления секретов).

---

## 7. Стоимость и free tier

### Стартовый грант

- Физические лица: до **4 000 ₽**; юридические лица: до **10 000 ₽** [yandex.cloud](https://yandex.cloud/ru/all-offers).

### Always-Free (Serverless Containers)

- 1 000 000 вызовов в месяц;
- 10 ГБ×час RAM;
- 5 vCPU×час CPU [yandex.cloud](https://yandex.cloud/ru/docs/billing/concepts/serverless-free-tier).

### Примерный расчёт для рекомендованной конфигурации (VM + self-hosted PG)

| Ресурс | Параметры | Стоимость (RUB/мес, при 720 ч) |
|---|---|---|
| **ВМ** (2 vCPU, 2 GB RAM, 20% core fraction, Intel Ice Lake) | 2×0,52 + 2×0,33 = 1,70 ₽/ч | 1 224 [yandex.cloud](https://yandex.cloud/ru/docs/compute/pricing) |
| **Загрузочный диск** (15 ГБ network-hdd) | 15×0,0052 = 0,078 ₽/ч | ~56 [yandex.cloud](https://yandex.cloud/ru/docs/compute/pricing) |
| **Публичный IP** | ~0,34 ₽/ч | ~245 [yandex.cloud](https://yandex.cloud/ru/services/compute) |
| **Container Registry** (хранение 1 ГБ) | ~0,85 ₽/ГБ/мес | <1 [yandex.cloud](https://yandex.cloud/ru/docs/tutorials/infrastructure-management/run-docker-on-vm/terraform) |
| **Исходящий трафик** (первые 100 ГБ бесплатно) | 0 ₽ | 0 [yandex.cloud](https://yandex.cloud/ru/docs/compute/pricing) |
| **Lockbox** (хранение 3 секретов) | по тарифу, ~12 ₽/мес | ~12 |
| **Итого** | | **~1 540 ₽/мес** |

При 100% vCPU ВМ выйдет ~2 260 ₽/мес [yandex.cloud](https://yandex.cloud/ru/docs/compute/pricing). Managed PostgreSQL + ВМ = ~6 900 ₽/мес. **Самая дешёвая рабочая конфигурация** — ВМ с 20% vCPU + self-hosted PG.

---

## 8. Готовые "gotchas" (для первой настройки Yandex Cloud + Terraform)

1. **Провайдер недоступен из РФ через registry.terraform.io** [registry.terraform.io](https://registry.terraform.io/providers/yandex-cloud/yandex). Используйте зеркало `terraform-mirror.yandexcloud.net` [yandex.cloud](https://yandex.cloud/ru/docs/tutorials/infrastructure-management/terraform-modules):

   ```hcl
   terraform {
     required_providers {
       yandex = {
         source  = "yandex-cloud/yandex"
       }
     }
   }
   ```

   При инициализации:  
   `terraform providers lock -net-mirror=https://terraform-mirror.yandexcloud.net yandex-cloud/yandex`

2. **Security group по умолчанию** создаётся автоматически для каждой VPC и блокирует весь входящий трафик. Убедитесь, что ваш security group прикреплён к ВМ [yandex.cloud](https://yandex.cloud/ru/docs/terraform/resources/vpc_security_group).

3. **Lockbox secrets не видны напрямую в user-data**. Передача секретов через `datasource.Ec2.secrets` работает, но требует версии cloud-init ≥ 24.2. Для production рассмотрите передачу через скрипт инициализации [yandex.cloud](https://yandex.cloud/ru/docs/compute/operations/vm-create/create-with-lockbox-secret).

4. **Managed PostgreSQL**: порт подключения **6432**, а не 5432 [yandex.cloud](https://yandex.cloud/ru/docs/terraform/resources/mdb_postgresql_cluster). Пароль в Terraform-ресурсе `yandex_mdb_postgresql_cluster` сохраняется в state – обязательно используйте remote state и ограничьте доступ к бакету.

5. **Привязка ролей** сервисному аккаунту через `yandex_resourcemanager_folder_iam_member` применяется **асинхронно** (до 5 минут). Первый `apply` может упасть – нужно повторить.

6. **Container Registry** требует наличия сервисного аккаунта с ролью `container-registry.images.puller` на ВМ [yandex.cloud](https://yandex.cloud/ru/docs/tutorials/infrastructure-management/run-docker-on-vm/terraform).

7. **Terraform state locking**: без DynamoDB (YDB) блокировка не работает. Два одновременных `apply` вызовут повреждение state-файла [yandex.cloud](https://yandex.cloud/ru/docs/tutorials/infrastructure-management/terraform-state-lock).

8. **ВМ с 20% vCPU** не подходит для высоких нагрузок, но для low-traffic каталога – оптимальна [yandex.cloud](https://yandex.cloud/ru/docs/compute/pricing).

---

## Неподтверждённые/непроверенные факты

- **Точная ежемесячная стоимость Lockbox** не найдена; оценка ~12 ₽/мес основана на общих тарифах, точные цифры можно уточнить на странице [Yandex Cloud Billing](https://yandex.cloud/ru/prices).
- **Стоимость публичного IP** не найдена в точной спецификации; расчёт использует ~0,34 ₽/ч (оценка по данным сторонних статей [chimitdorzhi.tech](https://chimitdorzhi.tech/blog/svoy-vps-dlya-razrabotchika-2026/)).
- **Yandex Cloud Serverless Containers** для постоянно работающего AI-бэкенда не тестировался на предмет стабильности долгих соединений.
- **Возможные изменения ценообразования** после 1 мая 2026 (цены действуют с этой даты) [yandex.cloud](https://yandex.cloud/ru/docs/compute/pricing)[yandex.cloud](https://yandex.cloud/ru/docs/managed-postgresql/pricing).

---

## Ссылки (использованные в тексте)

[github.com](https://github.com/yandex-cloud/terraform-provider-yandex/releases) GitHub – yandex-cloud/terraform-provider-yandex/releases (0.206.0)  
[habr.com](https://habr.com/ru/sandbox/279222/) Хабр – "Деплой веб-приложения на Yandex Cloud: Terraform + GitHub Actions"  
[yandex.cloud](https://yandex.cloud/ru/all-offers) Yandex Cloud – "Скидки, гранты и специальные условия"  
[yandex.cloud](https://yandex.cloud/ru/docs/billing/concepts/serverless-free-tier) Yandex Cloud – "Free tier для Serverless Containers"  
[yandex.cloud](https://yandex.cloud/ru/docs/compute/operations/vm-create/create-with-lockbox-secret) Yandex Cloud – "Создание ВМ с доступом к Lockbox"  
[yandex.cloud](https://yandex.cloud/ru/docs/compute/pricing) Yandex Cloud – "Правила тарификации Compute Cloud"  
[yandex.cloud](https://yandex.cloud/ru/docs/managed-postgresql/pricing) Yandex Cloud – "Правила тарификации Managed Service for PostgreSQL"  
[yandex.cloud](https://yandex.cloud/ru/docs/serverless-containers/pricing) Yandex Cloud – "Правила тарификации Serverless Containers"  
[yandex.cloud](https://yandex.cloud/ru/docs/terraform/authentication) Yandex Cloud – "Настройка аутентификации Terraform-провайдера"  
[yandex.cloud](https://yandex.cloud/ru/docs/terraform/release-notes) Yandex Cloud – "Release notes Terraform provider"  
[yandex.cloud](https://yandex.cloud/ru/docs/terraform/resources/lockbox_secret) Yandex Cloud – "yandex_lockbox_secret (Resource)"  
[yandex.cloud](https://yandex.cloud/ru/docs/terraform/resources/mdb_postgresql_cluster) Yandex Cloud – "yandex_mdb_postgresql_cluster (Resource)"  
[yandex.cloud](https://yandex.cloud/ru/docs/terraform/resources/vpc_security_group) Yandex Cloud – "yandex_vpc_security_group (Resource)"  
[yandex.cloud](https://yandex.cloud/ru/docs/terraform/resources/vpc_security_group_rule) Yandex Cloud – "yandex_vpc_security_group_rule (Resource)"  
[yandex.cloud](https://yandex.cloud/ru/docs/tutorials/infrastructure-management/run-docker-on-vm/terraform) Yandex Cloud – "Запуск Docker-образа через Terraform"  
[yandex.cloud](https://yandex.cloud/ru/docs/tutorials/infrastructure-management/terraform-modules) Yandex Cloud – "Terraform модули и зеркало"  
[yandex.cloud](https://yandex.cloud/ru/docs/tutorials/infrastructure-management/terraform-state-lock) Yandex Cloud – "Блокировка состояний Terraform"  
[yandex.cloud](https://yandex.cloud/ru/docs/tutorials/infrastructure-management/terraform-state-storage) Yandex Cloud – "Загрузка состояний Terraform в Object Storage"  
[yandex.cloud](https://yandex.cloud/ru/services/compute) Yandex Cloud – "Тарифы Compute Cloud (страница сервиса)"