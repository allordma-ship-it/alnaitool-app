SHELL := /bin/bash
COMPOSE := docker compose
BACKEND := backend
MOBILE := mobile
API_URL ?= http://10.0.2.2:8080

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

.env:
	cp .env.example .env
	@sed -i.bak "s/^JWT_SECRET=.*/JWT_SECRET=$$(openssl rand -hex 32)/" .env && rm -f .env.bak
	@echo "Created .env with a random JWT_SECRET"

# --- Stack --------------------------------------------------------------------
.PHONY: up
up: .env ## Start the full stack (first run downloads + preprocesses OSM data)
	$(COMPOSE) up -d --build

.PHONY: up-tools
up-tools: .env ## Start the stack plus Adminer (http://localhost:8081)
	$(COMPOSE) --profile tools up -d --build

.PHONY: stripe-listen
stripe-listen: .env ## Forward Stripe test webhooks to the API (requires STRIPE_SECRET_KEY)
	$(COMPOSE) --profile stripe up stripe-cli

.PHONY: down
down: ## Stop the stack
	$(COMPOSE) down

.PHONY: clean
clean: ## Stop the stack and delete all volumes (database, redis, OSRM data)
	$(COMPOSE) down -v

.PHONY: logs
logs: ## Tail API logs
	$(COMPOSE) logs -f api

.PHONY: ps
ps: ## Show service status
	$(COMPOSE) ps

.PHONY: osrm-reset
osrm-reset: ## Re-download and re-process the OSM extract (after changing OSRM_PBF_URL)
	$(COMPOSE) stop osrm
	$(COMPOSE) run --rm --entrypoint sh osrm-prepare -c 'rm -f /data/region.*'
	$(COMPOSE) up -d osrm

.PHONY: deploy
deploy: ## Set up / update the production server (run on the VPS; optional DOMAIN=api.example.com)
	sudo ./deploy/setup-server.sh $(DOMAIN)

.PHONY: prod-logs
prod-logs: ## Follow production API and HTTPS proxy logs
	$(COMPOSE) -f docker-compose.yml -f deploy/docker-compose.prod.yml logs -f api caddy

.PHONY: psql
psql: ## Open a psql shell
	$(COMPOSE) exec postgres psql -U $${POSTGRES_USER:-wanees} $${POSTGRES_DB:-wanees}

.PHONY: redis-cli
redis-cli: ## Open a redis-cli shell
	$(COMPOSE) exec redis redis-cli

# --- Backend ------------------------------------------------------------------
.PHONY: run
run: .env ## Run the API locally against compose datastores
	set -a && source .env && set +a && ROUTING_BASE_URL=http://localhost:$${OSRM_PORT:-5000} \
		go -C $(BACKEND) run ./cmd/server

.PHONY: build
build: ## Build the API binary into backend/bin
	go -C $(BACKEND) build -trimpath -o bin/wanees-api ./cmd/server

.PHONY: fmt
fmt: ## Format Go code
	gofmt -w $(BACKEND)

.PHONY: lint
lint: ## Vet Go code and check formatting
	@test -z "$$(gofmt -l $(BACKEND))" || (gofmt -l $(BACKEND); exit 1)
	go -C $(BACKEND) vet -tags integration ./...

.PHONY: test
test: ## Run backend unit tests
	go -C $(BACKEND) test -race ./...

.PHONY: test-integration
test-integration: .env ## Run end-to-end tests against the compose PostGIS + Redis
	$(COMPOSE) up -d postgres redis
	$(COMPOSE) exec -T postgres sh -c 'until pg_isready -q; do sleep 1; done'
	$(COMPOSE) exec -T postgres psql -U $${POSTGRES_USER:-wanees} -d postgres -tc \
		"SELECT 1 FROM pg_database WHERE datname='wanees_test'" | grep -q 1 || \
		$(COMPOSE) exec -T postgres psql -U $${POSTGRES_USER:-wanees} -d postgres -c 'CREATE DATABASE wanees_test'
	set -a && source .env && set +a && \
		TEST_DATABASE_URL=postgres://$${POSTGRES_USER}:$${POSTGRES_PASSWORD}@localhost:$${POSTGRES_PORT:-5432}/wanees_test?sslmode=disable \
		TEST_REDIS_URL=redis://localhost:$${REDIS_PORT:-6379}/15 \
		go -C $(BACKEND) test -tags integration -count=1 ./internal/handler/

# --- Mobile -------------------------------------------------------------------
.PHONY: mobile-bootstrap
mobile-bootstrap: ## Generate Flutter platform folders and fetch packages
	cd $(MOBILE) && flutter create --org sy.wanees --project-name wanees --platforms=android,ios .
	cd $(MOBILE) && ./tool/configure_platforms.sh
	cd $(MOBILE) && flutter pub get
	cd $(MOBILE) && dart run flutter_launcher_icons

.PHONY: mobile-run
mobile-run: ## Run the app (API_URL defaults to the Android emulator host alias)
	cd $(MOBILE) && flutter run --dart-define=API_BASE_URL=$(API_URL)

.PHONY: mobile-analyze
mobile-analyze: ## Static analysis for the Flutter app
	cd $(MOBILE) && flutter analyze
