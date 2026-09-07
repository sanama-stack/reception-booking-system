# Reception — developer entry points.
# `make up` is the one command a clean checkout needs.

SHELL := /bin/bash
COMPOSE := docker compose
# Layers the two applications back in as containers (deployment topology, and what CI smoke-tests).
APPS := -f docker-compose.yml -f docker-compose.apps.yml

.DEFAULT_GOAL := help
.PHONY: help up up-all down logs test migrate seed rebuild ps psql check-ports

help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

.env:
	@cp .env.example .env
	@echo "Created .env from .env.example"

# Next.js ignores PORT from env files, so the frontend's port is pinned in package.json while
# Caddy's upstream comes from .env. They must agree; a mismatch is a 502 that is tedious to
# diagnose, so it is caught here instead.
check-ports: .env
	@env_port=$$(grep -E '^FRONTEND_PORT=' .env | cut -d= -f2); \
	 pkg_port=$$(grep -oE 'next dev --port [0-9]+' frontend/package.json | grep -oE '[0-9]+'); \
	 if [ "$$env_port" != "$$pkg_port" ]; then \
	   echo "FRONTEND_PORT=$$env_port in .env but frontend/package.json runs on $$pkg_port."; \
	   echo "Caddy would proxy to a port nothing is listening on. Make them agree."; \
	   exit 1; \
	 fi

up: .env check-ports ## Start Postgres, Mailpit and Caddy — run the apps from your IDE
	$(COMPOSE) up -d
	@app=$$(grep -E '^APP_PORT=' .env | cut -d= -f2); \
	 mail=$$(grep -E '^MAILPIT_UI_PORT=' .env | cut -d= -f2); \
	 echo ""; \
	 echo "  Infrastructure is up. Now run the two applications:"; \
	 echo "    VS Code   F5 → \"Full stack\""; \
	 echo "    Terminal  cd backend && ./gradlew bootRun"; \
	 echo "              cd frontend && pnpm dev"; \
	 echo ""; \
	 echo "  App      http://localhost:$$app          ← always use this origin"; \
	 echo "  API      http://localhost:$$app/api/health"; \
	 echo "  Docs     http://localhost:$$app/api/docs"; \
	 echo "  Mailpit  http://localhost:$$mail"

up-all: .env ## Start everything in containers, including both applications
	$(COMPOSE) $(APPS) up -d --build
	@app=$$(grep -E '^APP_PORT=' .env | cut -d= -f2); \
	 mail=$$(grep -E '^MAILPIT_UI_PORT=' .env | cut -d= -f2); \
	 echo ""; \
	 echo "  App      http://localhost:$$app"; \
	 echo "  Mailpit  http://localhost:$$mail"

down: ## Stop everything (keeps the database volume)
	$(COMPOSE) $(APPS) down

rebuild: .env ## Rebuild the application images from scratch and restart
	$(COMPOSE) $(APPS) build --no-cache
	$(COMPOSE) $(APPS) up -d

ps: ## Show container status
	$(COMPOSE) $(APPS) ps

logs: ## Tail logs from all containers
	$(COMPOSE) $(APPS) logs -f --tail=100

test: ## Run backend and frontend test suites
	cd backend && ./gradlew build
	cd frontend && pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm build

migrate: .env ## Apply Flyway migrations against the running database
	$(COMPOSE) up -d postgres
	cd backend && ./gradlew flywayMigrate

seed: ## Load demo data (implemented in phase 11)
	@echo "Seed data ships in phase 11 (docs/phases/phase-11-hardening-and-deployment.md)."

psql: ## Open a psql shell on the running database
	$(COMPOSE) exec postgres psql -U $${POSTGRES_USER:-reception} -d $${POSTGRES_DB:-reception}
