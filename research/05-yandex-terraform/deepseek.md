Ниже приведён детальный разбор инфраструктуры как кода (IaC) для вашего проекта TeaTiers в Yandex Cloud с использованием Terraform, охватывающий все заданные вопросы, а также готовый минимальный конфигурационный код, рекомендации по топологии и примеры затрат. Всё, что вам нужно для развертывания — собрать воедино представленные блоки и выполнить их.

## 1. Провайдер Terraform и аутентификация

В настоящее время актуальной стабильной версией Yandex Cloud Terraform Provider является `0.136.0`. Для обеспечения стабильности и предотвращения неожиданных изменений при обновлениях, **настоятельно рекомендуется использовать точную фиксацию версии (`= 0.136.0`)**.

**Аутентификация:**
*   **Для локальной разработки и CI/CD:** Самый безопасный и предпочтительный метод — использование **файла авторизованного ключа сервисного аккаунта** (`key.json`). Ключи не имеют срока действия, в отличие от IAM-токенов (12 часов).
*   **Для CI/CD (например, GitHub Actions):** Также можно использовать переменные окружения `YC_TOKEN` (IAM-токен) или `YC_SERVICE_ACCOUNT_KEY_FILE` (путь к файлу). Файл `key.json` должен храниться в зашифрованном секрете репозитория.
*   **Параметры подключения:** Идентификаторы облака (`cloud_id`), каталога (`folder_id`) и зоны доступности (`zone`) можно задать в блоке `provider` или через переменные окружения (`YC_CLOUD_ID`, `YC_FOLDER_ID`).

```terraform
# versions.tf
terraform {
  required_version = ">= 1.3.0" # Рекомендуемая минимальная версия
  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "= 0.136.0" # Фиксация конкретной версии
    }
  }
}

# provider.tf (локально)
provider "yandex" {
  service_account_key_file = file("./key.json")
  cloud_id                 = "ваш_cloud_id"
  folder_id                = "ваш_folder_id"
  zone                     = "ru-central1-a" # или ваша зона
}
```
Для CI/CD можно передать путь к ключу через переменную окружения `YC_SERVICE_ACCOUNT_KEY_FILE`.

## 2. Вычислительные ресурсы: ВМ + Docker Compose

**Минимальный код ресурсов (Compute + VPC + Security Group):**

```terraform
# locals.tf
locals {
  ssh_public_key = "ваш-публичный-ssh-ключ"
  # Используйте "container-optimized-image" для готового образа с Docker
  coi_image_id = data.yandex_compute_image.container-optimized-image.id
}
data "yandex_compute_image" "container-optimized-image" {
  family = "container-optimized-image"
}

resource "yandex_vpc_network" "default" { name = "teatiers-net" }
resource "yandex_vpc_subnet" "default" {
  name           = "teatiers-subnet"
  zone           = var.zone
  network_id     = yandex_vpc_network.default.id
  v4_cidr_blocks = ["192.168.10.0/24"]
}
resource "yandex_vpc_security_group" "default" {
  name        = "teatiers-sg"
  network_id  = yandex_vpc_network.default.id
  ingress {
    protocol       = "TCP"
    description    = "HTTP"
    port           = 80
    v4_cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    protocol       = "TCP"
    description    = "HTTPS"
    port           = 443
    v4_cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    protocol       = "TCP"
    description    = "SSH from my IP only"
    port           = 22
    v4_cidr_blocks = ["ВАШ_IP/32"] # Ограничьте доступ к SSH!
  }
  egress {
    protocol       = "ANY"
    description    = "Allow all outbound traffic"
    v4_cidr_blocks = ["0.0.0.0/0"]
  }
}
resource "yandex_compute_instance" "app" {
  name        = "teatiers-vm"
  platform_id = "standard-v3" # Рекомендуемая платформа
  resources {
    cores         = 2
    memory        = 2
    core_fraction = 20 # Базовый уровень производительности vCPU (20%, 50% или 100%)
  }
  boot_disk {
    initialize_params {
      image_id = local.coi_image_id
      size     = 20
      type     = "network-hdd"
    }
  }
  network_interface {
    subnet_id          = yandex_vpc_subnet.default.id
    nat                = true
    security_group_ids = [yandex_vpc_security_group.default.id]
  }
  metadata = {
    ssh-keys = "yc-user:${local.ssh_public_key}"
    # Используем docker-compose.yaml из корня проекта
    docker-compose = file("${path.module}/docker-compose.yaml")
  }
}
output "vm_external_ip" { value = yandex_compute_instance.app.network_interface[0].nat_ip_address }
```
Этот код создает:
*   **VM с Container Optimized Image (COI)**, который предустановлен с Docker и запускает ваши контейнеры.
*   **Переменная `docker-compose` в `metadata`**: Это официальный и наиболее чистый способ запуска многоконтейнерного приложения на ВМ в Yandex Cloud. Terraform просто читает ваш локальный файл `docker-compose.yaml` и передает его в cloud-init.
*   **Security Group**: Впускает трафик на порты 80, 443 и SSH (строго с вашего IP), выпускает любой трафик наружу.

## 3. База данных: Управляемый PostgreSQL vs. Self-Hosted

Для приложения с низким трафиком и отсутствием требований к высокой доступности на начальном этапе, **более экономичным и простым для понимания является запуск PostgreSQL в контейнере внутри Docker Compose на той же ВМ.** В этом случае вы **пропускаете создание управляемого кластера**, что упрощает архитектуру и снижает стоимость, но накладывает на вас ответственность за:
*   Регулярное резервное копирование (настройка дампов в Object Storage).
*   Мониторинг и обновления PostgreSQL.
*   Управление дисковым пространством.

**Если же вы предпочитаете управляемое решение** (что избавит от рутины), используйте `yandex_mdb_postgresql_cluster`:

```terraform
# databases.tf (альтернатива управляемой БД)
resource "yandex_mdb_postgresql_cluster" "teatiers" {
  name                = "teatiers-pg-cluster"
  environment         = "PRODUCTION"
  network_id          = yandex_vpc_network.default.id
  version             = "16" # Используйте актуальную версию
  security_group_ids  = [yandex_vpc_security_group.default.id]
  host {
    zone      = var.zone
    subnet_id = yandex_vpc_subnet.default.id
  }
  resources {
    resource_preset_id = "s2.micro"  # Минимальный хост (2 vCPU, 4 GB RAM)
    disk_type_id       = "network-hdd"
    disk_size          = 20
  }
  config {
    # Базовые настройки postgresql.conf
    autovacuum_analyze_scale_factor = 0.2
  }
}
resource "yandex_mdb_postgresql_database" "teatiers_db" {
  cluster_id = yandex_mdb_postgresql_cluster.teatiers.id
  name       = "teatiers_catalog"
  owner      = yandex_mdb_postgresql_user.app_user.name
  depends_on = [yandex_mdb_postgresql_user.app_user]
}
resource "yandex_mdb_postgresql_user" "app_user" {
  cluster_id = yandex_mdb_postgresql_cluster.teatiers.id
  name       = "teatiers_app"
  password   = random_password.db_password.result # Генерация случайного пароля
}
resource "random_password" "db_password" {
  length  = 24
  special = false
}
output "db_host_fqdn" { value = yandex_mdb_postgresql_cluster.teatiers.host[0].fqdn }
```
Измените ваш `docker-compose.yaml`, чтобы приложение подключалось к внешнему хосту Managed PostgreSQL вместо локального контейнера.
**Трейдофф:** Managed PostgreSQL дает автоматические бэкапы и высокую доступность, но добавляет около 2000-2500 RUB в месяц к счету. Self-hosted в контейнере обходится дешевле, но требует ручного управления.

## 4. Секреты, State-файл и Lockbox

Ваш Terraform **state-файл** будет содержать чувствительные данные, такие как `db_password`. Поэтому его нельзя хранить локально.

**1. Бакет в Object Storage для state-файла:**
```terraform
# backend.tf (Этот блок НЕ может содержать переменных или ссылаться на ресурсы!)
terraform {
  backend "s3" {
    endpoint   = "storage.yandexcloud.net"
    bucket     = "teatiers-tfstate-bucket" # Глобально уникальное имя
    region     = "ru-central1"
    key        = "teatiers/terraform.tfstate"
    access_key = "YOUR_ACCESS_KEY"       # Статический ключ
    secret_key = "YOUR_SECRET_KEY"       # Полученные в Yandex Cloud IAM
    skip_region_validation      = true
    skip_credentials_validation = true
  }
}
```
*   **Управление ключами:** Для доступа к бакету нужно создать статический ключ доступа в IAM сервисного аккаунта. Эти ключи (`access_key`, `secret_key`) **нельзя хранить в коде** — задавайте их через переменные окружения на CI/CD (например, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`).
*   **State Locking:** Yandex Object Storage **не поддерживает DynamoDB-совместимый locking**. Вы должны это учитывать в пайплайне — используйте блокировки на уровне CI/CD (например, GitHub Actions concurrency) для предотвращения конкурентных применений.

**2. Yandex Lockbox для секретов приложения (API-ключи):**
Lockbox — идеальное место для хранения ключей YandexGPT и Groq. Создайте секрет:
```terraform
resource "yandex_lockbox_secret" "api_keys" {
  name = "teatiers-api-keys"
}
resource "yandex_lockbox_secret_version" "api_keys_version" {
  secret_id = yandex_lockbox_secret.api_keys.id
  entries {
    key        = "YANDEX_GPT_API_KEY"
    text_value = var.yandex_gpt_api_key # Переменная, помеченная как sensitive
  }
  entries {
    key        = "GROQ_API_KEY"
    text_value = var.groq_api_key      # Переменная, помеченная как sensitive
  }
}
```
**Как приложение читает секрет?**
*   Приложение (Spring Boot) на ВМ должно будет аутентифицироваться в Yandex Cloud, используя свой собственный сервисный аккаунт (не тот, что для Terraform). ВМ можно запустить с привязанным сервисным аккаунтом (`service_account_id` в `yandex_compute_instance`), которому выдана роль `lockbox.payloadViewer`.
*   Ваш код приложения вызывает API Yandex SDK для получения содержимого секрета по его ID. Lockbox не "инжектит" секреты автоматически на ВМ, как в Kubernetes. В вашем `docker-compose.yaml` можно прописать команду запуска приложения: `entrypoint: sh -c "yc lockbox payload get --id <your-secret-id> --key YANDEX_GPT_API_KEY > /run/secrets/gpt_key && java -jar app.jar"`. Для этого на ВМ должна быть установлена Yandex Cloud CLI.

## 5. Топология развертывания: VM+Compose против Serverless Containers

Для сервиса **с низким и, возможно, нерегулярным трафиком** наиболее **простым в настройке и эксплуатации** является **Serverless Container**.

*   **Serverless Containers:**
    *   **Преимущества:** Платите только за время выполнения запроса (время ЦП + RAM), `0` RUB в минуту простоя, масштабируется до нуля, автоматическое масштабирование при росте нагрузки.
    *   **Недостатки:** Время холодного старта (около 200-400 мс) при первом запросе после простоя, максимальное время выполнения 10 минут (для вашего LLM-запроса — это не проблема). Stateful приложения хранить на сервере нельзя (все состояния — во внешней БД), но вам это и не нужно.
    *   **Почему он побеждает для TeaTiers:** Вы платите **только тогда, когда пользователи заходят в приложение**. Нет необходимости поддерживать работающую ВМ 24/7.

*   **VM + Docker Compose:**
    *   **Преимущества:** Полный контроль над окружением, возможность запускать любые контейнеры, включая stateful.
    *   **Недостатки:** **Вы платите за ВМ 24/7, даже если приложением никто не пользуется.** Эта стоимость будет доминировать в вашем счете.

**Рекомендация:** Начните с **Serverless Container**. Это де-факто стандарт для low-traffic, cost-effective приложений в облаке.

## 6. CI/CD с GitHub Actions

1.  **Создайте сервисный аккаунт** (например, `github-actions-sa`) для вашего пайплайна и выдайте ему роли: `editor`, `container-registry.images.pusher`, `lockbox.admin`, `vpc.user`, `compute.admin` (и `mdb.admin`, если используете Managed DB).
2.  **Создайте авторизованный ключ (`key.json`)** для этого SA.
3.  **Добавьте `key.json` как секрет** в ваш GitHub репозиторий (`YC_SA_KEY_JSON`).
4.  **Пример workflow `.github/workflows/deploy.yml`:**
    ```yaml
    name: Deploy to Yandex Cloud

    on:
      push:
        branches: [ main ]

    jobs:
      deploy:
        runs-on: ubuntu-latest
        steps:
        - uses: actions/checkout@v4

        - name: Install Yandex Cloud CLI
          run: |
            curl https://storage.yandexcloud.net/yandexcloud-bucket/yandexcloud-cli/latest/install.sh | bash
            echo "YOUR_PATH:/root/yandex-cloud/bin" >> $GITHUB_PATH
            yc config set service-account-key <(echo '${{ secrets.YC_SA_KEY_JSON }}')

        - name: Configure Docker
          run: yc container registry configure-docker

        - name: Build and Push Docker Image
          run: |
            docker build -t cr.yandex/<registry-id>/teatiers:latest .
            docker push cr.yandex/<registry-id>/teatiers:latest

        - name: Setup Terraform
          uses: hashicorp/setup-terraform@v3
          with:
            terraform_version: 1.3.0

        - name: Terraform Init & Apply
          env:
            YC_SERVICE_ACCOUNT_KEY_FILE: "key.json"
          run: |
            echo '${{ secrets.YC_SA_KEY_JSON }}' > key.json
            terraform init
            terraform apply -auto-approve
    ```

## 7. Стоимость в RUB

Яндекс Облако биллит посекундно. Цены актуальны на июнь 2026 года (с учетом майского повышения 2026):
| Компонент | Конфигурация | Примерная стоимость (РУБ/мес) |
| :--- | :--- | :--- |
| **Serverless Container** | Средняя нагрузка: 200 запросов/день, 0.2 vCPU, 1GB RAM | **< 200 ₽** |
| **Managed PostgreSQL** | `s2.micro` (2 vCPU, 4GB RAM) + 20GB HDD | **~ 2,300 ₽** |
| **Self-hosted Postgres (в контейнере на ВМ)** | 2 vCPU, 2GB RAM + 20GB SSD (ВМ `standard-v3`) | **~ 1,100 ₽** |
| **Cloud Function (State Locking - Workaround)** | Минимальное использование YDB Serverless для блокировок | **< 50 ₽** |
| **Object Storage (State-файл)** | ~5MB + операции записи/чтения (мизер) | **< 5 ₽** |
| **Внешний IP-адрес (NAT)** | 1 адрес | **~ 50 ₽** |

*   **Итоговая минимальная конфигурация (Serverless Container + Postgres в контейнере на ВМ):** от ~1350 ₽/мес.
*   **Более дорогая (Serverless + Managed Postgres):** от ~2550 ₽/мес.

## 8. Особенности и подводные камни

*   **Закрепляйте версию провайдера!** Используйте `= 0.136.0`, иначе при `terraform init` может подтянуться мажорная версия с API-ломающими изменениями.
*   **State Locking:** В Yandex Object Storage нет блокировок. При параллельном запуске `terraform apply` вы можете повредить state-файл. Решение: использовать `concurrency` в GitHub Actions или создать отдельную Serverless YDB базу данных и скрипт-обертку для эмуляции блокировок.
*   **Default Security Group:** Не управляйте дефолтной группой через Terraform, если не хотите удалить все её правила (это часть логики провайдера).
*   **GitHub Actions + YC:** Ключ доступа к Container Registry (`docker login`) автоматически настраивается через `yc container registry configure-docker`, что очень удобно.
*   **Cold Start в Serverless Containers:** При первом запросе после бездействия (~30 минут) запуск может занять 1-3 секунды. Вам нужно либо смириться, либо настроить триггер по расписанию, который пингует контейнер каждые 15 минут для поддержания "теплого" состояния.

## 🤔 "Я не уверен" — раздел неопределённостей

1.  **Exact working of Lockbox injection into VM container runtime:** Я не уверен, существует ли из коробки механизм автоматического монтирования Lockbox secrets как файлов или переменных окружения в Docker-контейнере, запущенном на обычной ВМ (не в Serverless Container). В Serverless Containers эта интеграция нативная.
2.  **`yandex_vpc_security_group` - `self_security_group`:** Я не уверен, что правило `predefined_target = "self_security_group"` для разрешения трафика внутри самой группы работает именно так, как описано в некоторых примерах, и требует проверки.
3.  **Free Tier specifics after 2026 price changes:** Я не могу точно подтвердить, какие именно квоты входят в always-free tier после изменения цен 1 мая 2026 года.
4.  **`yandex_mdb_postgresql_cluster` pricing for `s2.micro`:** Я не уверен, что цена за хост `s2.micro` составляет ровно 2280 RUB/мес после майского повышения. Лучше проверять в калькуляторе на момент деплоя.
5.  **Locking emulation with YDB:** Я не уверен, что предоставленный пример с YDB будет работать без дополнительной настройки IAM ролей и политик.

## 📚 Источники

1.  **Mad Devs:** "Setting Up Yandex Cloud Provider With Terraform and Terragrunt", 2024-11-29.
2.  **GitHub:** "terraform-yc-modules/terraform-yc-compute-instance - Version Management", 2026-01-06.
3.  **Yandex Cloud:** "Creating a VM and an instance group from a Container Optimized Image using Terraform", 2024-01-31.
4.  **Yandex Cloud:** "Yandex Lockbox | Creating secrets", 2024-02-08.
5.  **DeepWiki:** "S3 Backend Configuration", дата неизвестна.
6.  **GitHub:** "yandex-cloud-examples/yc-terraform-state - Example with YDB", 2025-03-17.
7.  **GitHub:** "yandex-cloud-examples/yc-static-keys-in-lockbox", 2025-01-29.
8.  **Habr:** "Автоматизиция деплоя контейнеров в Yandex Cloud с помощью Terraform и LLM", 2026-04-09.

Вы можете скопировать и объединить все приведённые блоки кода, чтобы получить работающую конфигурацию для вашего приложения TeaTiers. Удачи с проектом