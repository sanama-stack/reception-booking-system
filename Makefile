# Reception — developer entry points.
# `make up` is the one command a clean checkout needs.

SHELL := /bin/bash
COMPOSE := docker compose
# Layers the two applications back in as containers (deployment topology, and what CI smoke-tests).
APPS := -f docker-compose.yml -f docker-compose.apps.yml
# The same system with the provider replaced by a fake one, for the E2E flow (ADR-0011).
E2E := $(APPS) -f docker-compose.e2e.yml

.DEFAULT_GOAL := help
.PHONY: help up up-all up-e2e down logs test migrate seed rebuild ps psql check-ports \
        check-headers check-fake-provider

help: ## Show this help
# [0-9] in the class, because without it a target with a digit in its name — up-e2e — is
# silently absent from this list rather than listed wrongly.
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

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

# Everything up-all starts, plus the fake provider, with the backend pointed at it. The
# application images are the ones that ship; only app.ai.base-url differs.
up-e2e: .env ## Start the E2E topology — everything, with a fake AI provider
	$(COMPOSE) $(E2E) up -d --build
	@app=$$(grep -E '^APP_PORT=' .env | cut -d= -f2); \
	 echo ""; \
	 echo "  App           http://localhost:$$app"; \
	 echo "  AI provider   fake-provider:8090 (in-network; see ADR-0011)"; \
	 echo ""; \
	 echo "  Seed it before booking by chat:  make seed"

# The double's own check. It plants nothing, but it asserts the property that makes the double
# usable — that it answers from the transcript rather than counting turns — and that property
# has been shown to fail when the policy is replaced by a counter.
check-fake-provider: ## Run the fake AI provider's self-test (no containers needed)
	node infra/fake-provider/selftest.js

down: ## Stop everything (keeps the database volume)
	$(COMPOSE) $(E2E) down

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

# The Flyway task runs on the host, not in a container, so it reads DB_* from the process
# environment rather than from compose. Without this it takes build.gradle.kts's defaults and
# targets port 5432, where nothing in this project listens (issue #9).
migrate: .env ## Apply Flyway migrations against the running database
	$(COMPOSE) up -d postgres
	set -a && . ./.env && set +a && cd backend && ./gradlew flywayMigrate

# Runs the application with no web server, so it can be seeded while the real one is up on
# SERVER_PORT — and with the notifications poller off, so nothing is sent while the fixture is
# being built. `reception.seed.enabled` is set here and nowhere else: SeedRunner is also
# @Profile("local") and checks the environment again before it deletes anything (SeedRunner).
seed: .env ## Load the two-tenant demo dataset (local profile only)
	$(COMPOSE) up -d postgres
	set -a && . ./.env && set +a && cd backend && ./gradlew bootRun --console=plain -q \
		--args='--spring.main.web-application-type=none --reception.seed.enabled=true --app.notifications.poller-enabled=false'

# The headers of docs/06-security.md §13, asserted against the running origin rather than read
# off the Caddyfile. Reading the file would prove only that the file says what the file says —
# which is how two of these headers came to be documented for ten phases without existing (T42).
#
# It asserts the DEPLOYED policy, so run it against `make up-all`. Under `make up` the frontend is
# `next dev`, which compiles through eval and therefore gets 'unsafe-eval' added to script-src on
# purpose (.env, CSP_SCRIPT_EXTRA) — this check fails there, and that failure is the check working.
check-headers: ## Assert the security headers on the running origin (expects the up-all topology)
	@app=$$(grep -E '^APP_PORT=' .env | cut -d= -f2); \
	 origin="http://localhost:$${app}"; \
	 echo "Asserting security headers against $${origin}"; \
	 for path in / /api/health; do \
	   headers=$$(curl -fsS -D - -o /dev/null "$${origin}$${path}"); \
	   for required in \
	     'X-Content-Type-Options: nosniff' \
	     'Referrer-Policy: strict-origin-when-cross-origin' \
	     'X-Frame-Options: DENY' \
	     'Strict-Transport-Security:' \
	     'Content-Security-Policy:'; do \
	     echo "$$headers" | grep -qi "$$required" \
	       || { echo "$${path}: missing $$required"; exit 1; }; \
	   done; \
	   csp=$$(echo "$$headers" | grep -i '^content-security-policy:'); \
	   for directive in "default-src 'self'" "base-uri 'self'" "form-action 'self'" \
	                    "frame-ancestors 'none'" "object-src 'none'"; do \
	     echo "$$csp" | grep -qF "$$directive" \
	       || { echo "$${path}: the policy is missing $$directive"; exit 1; }; \
	   done; \
	   ! echo "$$csp" | grep -qi 'unsafe-eval' \
	     || { echo "$${path}: the policy permits unsafe-eval — see CSP_SCRIPT_EXTRA in .env"; exit 1; }; \
	   echo "$$headers" | grep -qi '^server:' \
	     && { echo "$${path}: the Server header is still being sent"; exit 1; }; \
	   echo "  $${path} ok"; \
	 done; \
	 echo "Security headers are as docs/06-security.md §13 describes them."

psql: ## Open a psql shell on the running database
	$(COMPOSE) exec postgres psql -U $${POSTGRES_USER:-reception} -d $${POSTGRES_DB:-reception}
