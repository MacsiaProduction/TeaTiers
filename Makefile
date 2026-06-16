# TeaTiers entry point. Two independent Gradle builds: server/ (JDK 21) and app/ (JDK 17).
# Run `make` or `make help` to list targets.
SHELL := /bin/bash
.DEFAULT_GOAL := help

.PHONY: help build build-server build-app check check-server check-app test run-server clean docker-build docker-up docker-down docker-logs docker-ps

help: ## List available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "} {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

build: build-server build-app ## Build both modules

build-server: ## Assemble the backend (bootJar)
	cd server && ./gradlew build

build-app: ## Assemble the Android debug APK
	cd app && ./gradlew assembleDebug

check: check-server check-app ## Run all checks (tests + lint) for both modules

check-server: ## Backend tests + verification
	cd server && ./gradlew check

check-app: ## Android unit tests + lint + assemble
	cd app && ./gradlew check assembleDebug

test: ## Run unit tests for both modules
	cd server && ./gradlew test
	cd app && ./gradlew testDebugUnitTest

run-server: ## Run the backend at http://localhost:8080 (health: /actuator/health)
	cd server && ./gradlew bootRun

clean: ## Remove build outputs in both modules
	cd server && ./gradlew clean
	cd app && ./gradlew clean

docker-build: ## Build the backend container image
	docker compose build

docker-up: ## Start the full stack (server + Postgres) via docker compose
	docker compose up -d

docker-down: ## Stop and remove compose services
	docker compose down

docker-logs: ## Tail logs for the compose stack
	docker compose logs -f --tail=100

docker-ps: ## Show compose service status
	docker compose ps
