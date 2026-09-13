# Reception — developer entry points.
# `make up` is the one command a clean checkout needs.

SHELL := /bin/bash
COMPOSE := docker compose
# Layers the two applications back in as containers (deployment topology, and what CI smoke-tests).
APPS := -f docker-compose.yml -f docker-compose.apps.yml
# The same system with the provider replaced by a fake one, for the E2E flow (ADR-0011).
E2E := $(APPS) -f docker-compose.e2e.yml
# ...in its own compose project, on its own ports, with its own database volume. An E2E that shared
# a database with your development work would register businesses and book appointments into it,
# and `down -v` would then take your work with it. Named volumes are project-scoped, so the project
# name is what buys the isolation; the ports only keep the two stacks from colliding on the host.
# Internal ports (SERVER_PORT, FRONTEND_PORT) are deliberately NOT overridden — they are the ports
# inside the network, and Caddy's upstreams are written to them.
E2E_ENV := COMPOSE_PROJECT_NAME=reception-e2e \
           APP_PORT=9180 MAILPIT_UI_PORT=9183 MAILPIT_SMTP_PORT=9184 POSTGRES_PORT=9185 \
           APP_PUBLIC_URL=http://localhost:9180

.DEFAULT_GOAL := help
.PHONY: help up up-all up-e2e down down-e2e logs logs-e2e test e2e migrate seed rebuild ps psql \
        check-ports check-bindings check-docs \
        check-headers check-access-log check-fake-provider

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
# Four ports in .env are written down twice, and the duplicates have to agree.
#
# The block's own comment invites changing them — "deliberately away from the 3000/8080/5432 range
# so this project never collides with another one" is an instruction to move them when it does —
# and each one moves in two places or the stack comes up broken in a way that names the wrong
# cause. Until the .env.example audit on 2026-09-12 this target guarded one of the four.
#
# The three .env-internal pairs are checked only when the corresponding host is local, because
# that is the only topology in which they are coupled: `make up-all` overrides DB_* and MAIL_*
# with the compose service names, and a DB_HOST pointing at a real server has every right to a
# port that is nothing to do with what Postgres publishes here.
#
# Both `up` and `up-all` depend on this, and for `up-all` that is deliberately one check wider
# than that topology strictly needs. APP_PORT against APP_PUBLIC_URL matters there and matters
# most — it is the shape that deploys, and a drifted pair sends every Manage Link to a dead port,
# which a customer finds rather than a developer. The DB_* and MAIL_* pairs are inert under
# `up-all`, since compose overrides both, and are still enforced because .env describes one
# machine rather than one topology: letting it go internally inconsistent under `up-all` only
# moves the failure to the next `make up`. Agreement costs a line; the asymmetry would cost an
# afternoon.
check-ports: .env
	@fail=0; \
	 val() { grep -E "^$$1=" .env | head -1 | cut -d= -f2- | tr -d '"'; }; \
	 env_port=$$(val FRONTEND_PORT); \
	 pkg_port=$$(grep -oE 'next dev --port [0-9]+' frontend/package.json | grep -oE '[0-9]+'); \
	 if [ "$$env_port" != "$$pkg_port" ]; then \
	   echo "FRONTEND_PORT=$$env_port in .env but frontend/package.json runs on $$pkg_port."; \
	   echo "Caddy would proxy to a port nothing is listening on. Make them agree."; \
	   fail=1; \
	 fi; \
	 case "$$(val DB_HOST)" in localhost|127.0.0.1) \
	   if [ "$$(val DB_PORT)" != "$$(val POSTGRES_PORT)" ]; then \
	     echo "DB_PORT=$$(val DB_PORT) but POSTGRES_PORT=$$(val POSTGRES_PORT)."; \
	     echo "The backend would dial a port Postgres is not published on. Make them agree."; \
	     fail=1; \
	   fi;; \
	 esac; \
	 case "$$(val MAIL_HOST)" in localhost|127.0.0.1) \
	   if [ "$$(val MAIL_PORT)" != "$$(val MAILPIT_SMTP_PORT)" ]; then \
	     echo "MAIL_PORT=$$(val MAIL_PORT) but MAILPIT_SMTP_PORT=$$(val MAILPIT_SMTP_PORT)."; \
	     echo "Every email would fail to send, and only the outbox would say so. Make them agree."; \
	     fail=1; \
	   fi;; \
	 esac; \
	 pub=$$(val APP_PUBLIC_URL); \
	 case "$$pub" in *localhost*|*127.0.0.1*) \
	   pub_port=$$(echo "$$pub" | grep -oE ':[0-9]+' | tr -d ':'); \
	   if [ "$$pub_port" != "$$(val APP_PORT)" ]; then \
	     echo "APP_PUBLIC_URL is $$pub but APP_PORT=$$(val APP_PORT)."; \
	     echo "Manage Links and email links would point at a dead port. Make them agree."; \
	     fail=1; \
	   fi;; \
	 esac; \
	 exit $$fail

up: .env check-ports check-bindings ## Start Postgres, Mailpit and Caddy — run the apps from your IDE
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

up-all: .env check-ports check-bindings ## Start everything in containers, including both applications
	$(COMPOSE) $(APPS) up -d --build
	@app=$$(grep -E '^APP_PORT=' .env | cut -d= -f2); \
	 mail=$$(grep -E '^MAILPIT_UI_PORT=' .env | cut -d= -f2); \
	 echo ""; \
	 echo "  App      http://localhost:$$app"; \
	 echo "  Mailpit  http://localhost:$$mail"

# Everything up-all starts, plus the fake provider, with the backend pointed at it. The
# application images are the ones that ship; only app.ai.base-url differs.
up-e2e: .env check-bindings ## Start the E2E topology — isolated project, own database, fake AI provider
	$(E2E_ENV) $(COMPOSE) $(E2E) up -d --build
	@echo ""; \
	 echo "  App           http://localhost:9180   ← the E2E origin, not 9080"; \
	 echo "  Mailpit       http://localhost:9183"; \
	 echo "  AI provider   fake-provider:8090 (in-network; see ADR-0011)"; \
	 echo ""; \
	 echo "  Its own database. Your 'make up' stack and its data are untouched."

down-e2e: ## Stop the E2E topology and delete its database
	$(E2E_ENV) $(COMPOSE) $(E2E) down -v

logs-e2e: ## Dump the E2E topology's container logs (what a CI failure needs)
	$(E2E_ENV) $(COMPOSE) $(E2E) logs --no-color

# The double's own check. It plants nothing, but it asserts the property that makes the double
# usable — that it answers from the transcript rather than counting turns — and that property
# has been shown to fail when the policy is replaced by a counter.
check-fake-provider: ## Run the fake AI provider's self-test (no containers needed)
	node infra/fake-provider/selftest.js

# Expects `make up-e2e` to be running. It is deliberately not a dependency of this target: bringing
# the stack up takes minutes and rebuilds images, and a test target that silently does that is a
# test target nobody runs twice.
e2e: ## Run the end-to-end flow against the E2E topology (needs `make up-e2e`)
	@curl -fsS -o /dev/null --max-time 3 http://localhost:9180/api/health \
	  || { echo "Nothing is answering on http://localhost:9180 — run 'make up-e2e' first."; exit 1; }
# `--with-deps` is deliberately absent: it installs OS packages, which is a Linux runner's
# problem and not something a developer's machine should be asked to do by `make`. CI
# installs the browser in its own step.
	cd e2e && pnpm install --frozen-lockfile && pnpm exec playwright install chromium && pnpm test

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

# docs/06-security.md §6, and the only way to check it is to make a request and read what was
# written about it. A Manage Link token is a bearer capability for one appointment, and it travels
# in a URL: a path segment on the page the Customer opens, a query parameter on the two API calls
# that page makes. Caddy's log is the only access log in the system, and until phase 11 it wrote
# all three verbatim — measured against the running container, not inferred from the Caddyfile.
#
# THE CONTROL IS THE POINT, AND IT HAS TO BE UNIQUE PER RUN. "The sentinel is not in the log"
# passes just as well when the log is empty, when `docker compose logs` names the wrong service,
# and when a filter redacts the entire uri field — so a control request must be shown to arrive
# WITH its uri intact. The first version of this check grepped for a fixed `/api/health`, and a
# redact-everything filter passed it: `docker compose logs --tail` spans container restarts, so a
# line from a previous run satisfied the control while nothing from this one did. Both sentinels
# now carry $$ and neither can be answered by a stale line.
check-access-log: ## Assert Manage Link tokens are redacted from the access log (needs Caddy running)
	@app=$$(grep -E '^APP_PORT=' .env | cut -d= -f2); \
	 origin="http://localhost:$${app}"; \
	 secret="ManageTokenSentinel-$$$$-must-not-survive"; \
	 control="ControlSentinel-$$$$-must-survive"; \
	 echo "Probing $${origin} for a token the access log must not keep"; \
	 curl -sS -o /dev/null "$${origin}/manage/$${secret}"; \
	 curl -sS -o /dev/null "$${origin}/api/public/appointments/manage?token=$${secret}"; \
	 curl -sS -o /dev/null "$${origin}/api/health?probe=$${control}"; \
	 sleep 1; \
	 log=$$($(COMPOSE) logs caddy --tail 200 --no-color 2>/dev/null); \
	 [ -n "$$log" ] \
	   || { echo "no log to read — is Caddy running under this compose project?"; exit 1; }; \
	 echo "$$log" | grep -qF "\"uri\":\"/api/health?probe=$${control}\"" \
	   || { echo "this run's control request is not in the log with its uri intact — the check cannot see anything, so its silence about the token means nothing"; exit 1; }; \
	 echo "  control ok — this run's own request is in the log, uri and query preserved"; \
	 ! echo "$$log" | grep -qF "$$secret" \
	   || { echo "a Manage Link token reached the log — see the log filters in infra/caddy/Caddyfile"; exit 1; }; \
	 echo "  neither the path segment nor the query parameter kept its token"; \
	 echo "Manage Link tokens are excluded from the log, as docs/06-security.md §6 says."

psql: ## Open a psql shell on the running database
	$(COMPOSE) exec postgres psql -U $${POSTGRES_USER:-reception} -d $${POSTGRES_DB:-reception}

# docs/06-security.md §12, asserted against the merged compose configuration rather than read off
# a file. The claim it checks — "the database port is exposed to the host only in the local IDE
# topology" — was DESCRIBED for ten phases and implemented by nothing (G26, the second instance of
# the shape T42 records about the security headers). Fixing the file without adding this check
# would have left the next such sentence exactly as unguarded.
#
# It needs no containers: `docker compose config` merges the overlays and resolves every variable
# without starting anything, so this runs in CI beside the header check and on a laptop in under a
# second.
#
# ALL THREE TOPOLOGIES, and the third is here because of T66 — `check-ports` guarded one coupling
# at `make up`, a target CI never invokes, so the shape that deploys never met the gate. A check
# that covers the topology you develop in and not the one you ship is the same mistake with the
# ports renamed.
#
# WHY host_ip IS THE WHOLE ASSERTION. A published port with no host given binds 0.0.0.0, and a host
# firewall does not cover it — Docker writes its rules in the DOCKER-USER chain, below ufw. Measured
# on 2026-09-12 against the two bindings side by side: unqualified, the Mailpit UI answered 200 and
# Postgres accepted a connection on the machine's LAN address; bound to 127.0.0.1, both refused.
check-bindings: .env ## Assert only Caddy is published on all interfaces, in both topologies
	@fail=0; \
	 check() { \
	   topology="$$1"; shift; \
	   echo "  $$topology"; \
	   published=$$("$$@" config --format json 2>/dev/null \
	     | jq -r '.services | to_entries[] | .key as $$s | (.value.ports // [])[] \
	              | "\($$s) \(.published) \(.host_ip // "0.0.0.0")"'); \
	   if [ -z "$$published" ]; then \
	     echo "    nothing published at all — the config did not resolve"; fail=1; return; fi; \
	   echo "$$published" | while read -r svc port ip; do \
	     echo "      $$svc $$ip:$$port"; done; \
	   echo "$$published" | grep -qE '^caddy [0-9]+ 0\.0\.0\.0$$' \
	     || { echo "    caddy is not published on all interfaces — it is the origin"; fail=1; }; \
	   offenders=$$(echo "$$published" | grep -vE '^caddy ' | grep -v ' 127\.0\.0\.1$$' || true); \
	   [ -z "$$offenders" ] \
	     || { echo "    published beyond loopback: $$offenders"; fail=1; }; \
	 }; \
	 echo "Asserting published bindings (docs/06-security.md §12)"; \
	 check "local IDE topology (make up)" $(COMPOSE); \
	 check "containerised topology (make up-all)" $(COMPOSE) $(APPS); \
	 check "E2E topology (make up-e2e)" env $(E2E_ENV) $(COMPOSE) $(E2E); \
	 db=$$($(COMPOSE) $(APPS) config --format json 2>/dev/null \
	   | jq -r '(.services.postgres.ports // []) | length'); \
	 [ "$$db" = "0" ] \
	   || { echo "  the database is published in the containerised topology — §12 says it is not"; \
	        fail=1; }; \
	 [ "$$fail" = "0" ] \
	   && echo "Only the origin is published beyond loopback, and the database only to the IDE." \
	   || { echo "check-bindings FAILED"; exit 1; }

# The documentation's own consistency, as four mechanical checks rather than a reading. Phase 11's
# last Documentation row is "a final consistency pass over /docs and CONTEXT.md", and a pass run
# once is stale the next time somebody renumbers a section — so it is a target instead of an event.
#
# No containers, no network, no build: a checkout and Python. It is its own CI job for that reason.
check-docs: ## Assert the documentation is internally consistent (links, §refs, inventories)
	@python3 docs/tools/consistency/check.py
