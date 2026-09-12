# Deployment — one documented path

A single VPS, both applications in containers, Caddy terminating TLS for one domain. This is the
path [09-phase-plan.md](./09-phase-plan.md) phase 11 asks for and the one
[06-security.md](./06-security.md) §13 and [.env.example](../.env.example) point at when they say
"see docs/deployment.md".

> **No host has ever run this.** Every other claim in this project's documentation is a reading off
> a CI log or a measurement someone took. This one is not: it is written from the compose files,
> the Caddyfile and the application configuration, and **no VPS has been provisioned, no
> certificate has been issued, and no deployment has been walked end to end.** §7 says exactly which
> lines here are verified and which are reasoned. Treat the unverified ones as a plan to be checked
> on first contact, not as a report.

The MVP contract is local compose ([07-mvp-scope.md](./07-mvp-scope.md)); operating an
internet-reachable instance is explicitly **out** of phase 11's scope. This document exists so that
the first person to try it starts from the real list of obstacles rather than discovering them one
`curl` at a time.

---

## 1. The topology

Five containers, the shape [02-product-architecture.md](./02-product-architecture.md) §1 describes
and the one `make up-all` already builds:

```
                    ┌──────── the internet ────────┐
                            :443  (TLS)
                              │
                        ┌─────▼─────┐
                        │   caddy   │  one origin, terminates TLS,
                        │           │  sets the security headers
                        └──┬─────┬──┘
                 /api/*    │     │    everything else
                        ┌──▼──┐ ┌▼─────────┐
                        │backend│ │ frontend │   neither publishes a port
                        └──┬──┘ └──────────┘
                    ┌──────┴───────┐
                ┌───▼────┐   ┌─────▼─────┐
                │postgres│   │   SMTP    │  ← not Mailpit. See §3.3
                └────────┘   └───────────┘
```

**The single origin is not a convenience, it is the security model.** Everything the browser talks
to arrives from one host, which is what lets authentication use `httpOnly` cookies with no CORS
anywhere in the system ([ADR-0001](./adr/0001-self-issued-jwt-over-keycloak.md),
[06-security.md](./06-security.md) §2, §13). A deployment that puts the API on `api.example.com` and
the app on `app.example.com` is not this system with different DNS — it is a different system, and
every cookie in it stops working.

---

## 2. What you need first

| | |
|---|---|
| A host | 2 vCPU / 4 GB is the smallest sane size. The backend runs with `-XX:MaxRAMPercentage=75` and Postgres is in the same box |
| Docker Engine + Compose v2 | The Compose files use `name:`, `depends_on.condition` and profile overlays |
| A domain | An `A` record pointing at the host **before** the first start — Caddy's certificate issuance is a live HTTP challenge and fails against DNS that does not resolve yet |
| Ports 80 and 443 open | 80 is not optional: it carries the ACME challenge and the redirect |
| An SMTP account | §3.3. There is no mail without one |
| Somewhere to keep secrets | Four of them, §4 |

---

## 3. What has to change before any of this works

These are not hardening suggestions. Each is a file that, as committed, either refuses to serve TLS
or exposes something to the internet that was written for a laptop.

### 3.1 The Caddyfile cannot serve TLS as written

[infra/caddy/Caddyfile](../infra/caddy/Caddyfile) opens with:

```
{
	admin off
	auto_https off
}

:8080 {
```

`auto_https off` **disables certificate issuance**, and `:8080` is a port-only site address, which
gives Caddy no domain to request a certificate for. Both are right for local: the origin is
`http://localhost:9080` and there is nothing to encrypt. Both are wrong here, and neither fails
loudly — you get a working plain-HTTP site on the wrong port and no certificate.

A TLS deployment needs the site address to be the **domain**, and `auto_https` left at its default:

```
{
	admin off
}

booking.example.com {
	handle /api/* {
		reverse_proxy {$BACKEND_UPSTREAM:backend:8081}
	}
	handle {
		reverse_proxy {$FRONTEND_UPSTREAM:frontend:3000}
	}
	# ... the header block unchanged ...
}
```

and the published port becomes `443:443` plus `80:80` rather than `${APP_PORT}:8080`.

**Keep the `header` block byte-for-byte.** It is the subject of a test (`make check-headers`) and of
[06-security.md](./06-security.md) §13, and the note above `Strict-Transport-Security` explains why
the local value is `max-age=0` — a browser ignores HSTS on a response that did not arrive over
HTTPS, so locally the header is inert whatever it says. **Here it stops being inert**, which is the
one header value this document changes: see §4.

### 3.2 Postgres and Mailpit publish themselves to the world

[docker-compose.yml](../docker-compose.yml) publishes three ports unconditionally:

```yaml
postgres:  ports: ["${POSTGRES_PORT:-9085}:5432"]
mailpit:   ports: ["${MAILPIT_SMTP_PORT:-9084}:1025", "${MAILPIT_UI_PORT:-9083}:8025"]
```

On a laptop that is correct and deliberate — the comment on the Postgres line says so, and the
backend running from an IDE needs the published port to reach the database at all. On a public host
it publishes **the database and a web UI holding every email the system has ever sent** on
`0.0.0.0`.

**A host firewall is not enough on its own.** Docker writes its own `iptables` rules in the
`DOCKER-USER` chain, and a published port is reachable through them even when `ufw` says the port is
denied. This surprises people regularly and it is not a Docker bug — publishing a port is a request
to make it reachable.

Two changes, and the second is the one that actually holds:

1. **Bind to loopback** rather than every interface — `127.0.0.1:9085:5432` — so a published port is
   reachable only from the host itself.
2. **Do not publish them at all.** Nothing in the deployed topology needs either: the backend
   reaches `postgres:5432` and `mailpit:1025` over the compose network by service name, and
   `docker-compose.apps.yml` already points it there. Reach the database with
   `docker compose exec postgres psql` (which is what `make psql` does) or over an SSH tunnel.

This is also where [06-security.md](./06-security.md) §12 and the compose file disagree, and the
document is the one that is wrong. §12 says *"the database port is exposed to the host only in the
`local` compose profile"*. **There is no such conditionality in the file** — `docker-compose.yml`
publishes it in every topology that includes it, and `docker-compose.apps.yml` does not take it
away. The control §12 describes does not exist; the sentence describes an intention. This is the
same shape as the finding §13 of that document records about the security headers, which were
described for a phase before any file set them.

### 3.3 Mailpit is not a mail server

Mailpit **captures** mail and delivers none of it. Every confirmation, cancellation and Manage Link
this system sends would stop at a web UI nobody is watching. It is the right tool for a demo — the
README's demo script reads mail there on purpose — and it must not be in this topology.

Point the backend at a real relay:

```
MAIL_HOST=smtp.your-provider.example
MAIL_PORT=587
MAIL_USERNAME=…
MAIL_PASSWORD=…
MAIL_FROM=no-reply@booking.example.com
```

and drop the `mailpit` service, with its `depends_on` entry in
[docker-compose.apps.yml](../docker-compose.apps.yml), from the deployed stack.

Two consequences worth knowing before the first real booking:

- **`MAIL_FROM` must be a domain you can authenticate for.** `no-reply@reception.local` is the local
  default and will be rejected or silently spam-filed by any real recipient. SPF and DKIM on the
  sending domain are the difference between "the email was sent" and "the customer got it", and
  nothing in this system can tell the two apart.
- **The health endpoint will tell you if the relay is wrong.** `/api/health` calls
  `JavaMailSenderImpl.testConnection()` and reports `{"mail":"DOWN"}` with a `503`
  ([HealthController](../backend/src/main/java/dev/reception/common/web/HealthController.java)) —
  and because that endpoint is the backend container's healthcheck, **a bad SMTP configuration
  keeps the backend unhealthy and therefore keeps Caddy from starting at all.** That is a good
  failure — loud, at boot, before a customer is waiting on an email — but it is a confusing one if
  you do not expect it and are staring at a proxy that will not come up.

### 3.4 The API documentation is public, in every profile

`/api/docs`, `/api/openapi` and `/api/swagger-ui` are permitted to everyone, `prod` included
([SecurityConfig](../backend/src/main/java/dev/reception/common/config/SecurityConfig.java)).
[06-security.md](./06-security.md) §15 records this as an accepted MVP risk **and names it the first
thing to change on an internet-reachable host**, because it publishes the whole endpoint surface to
anyone who asks.

The cheapest fix that does not touch Java is to refuse the paths at the proxy, above the `/api/*`
handler:

```
handle /api/docs* /api/openapi* /api/swagger-ui* {
	respond 404
}
```

`404` rather than `403`, for the reason [06-security.md](./06-security.md) §3 gives everywhere else:
a `403` confirms the thing is there.

---

## 4. Configuration

Start from [.env.example](../.env.example) — it is complete, every variable the compose files read
is in it — and change these. Everything not listed keeps its committed value.

| Variable | Local | Here | Why |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | **`prod`** | Secure cookies, no seed, `flyway.clean-disabled`, and the guard in §5 |
| `APP_PUBLIC_URL` | `http://localhost:9080` | **`https://booking.example.com`** | Manage Links and email links are built from it. Wrong here means every link a customer receives is wrong |
| `HSTS` | `max-age=0` | **`max-age=31536000; includeSubDomains`** | Inert locally, load-bearing here. **Do not set this until TLS works** — see the warning below |
| `CSP_SCRIPT_EXTRA` | `"'unsafe-eval'"` | **empty** | `next dev` needs `eval`; the production build does not. `docker-compose.apps.yml` already forces `""`, so this is belt and braces |
| `POSTGRES_PASSWORD` / `DB_PASSWORD` | `local-dev-only-…` | **generated** | §5 |
| `JWT_SECRET` | `local-dev-only-…` | **generated, ≥32 bytes** | §5 |
| `MANAGE_LINK_SECRET` | `local-dev-only-…` | **generated, ≥32 bytes** | §5 |
| `MAIL_*` | Mailpit | **a real relay** | §3.3 |
| `OPENAI_API_KEY` | placeholder | a real key, or leave it | A placeholder is not a failure: the Receptionist degrades to the Classic Flow and the application still works ([05-ai-architecture.md](./05-ai-architecture.md)) |

> **`HSTS` is the one setting here that can take the domain away from you.** It instructs every
> browser that has seen it to refuse plain HTTP for this host — for a year, with
> `includeSubDomains` — and there is no way to reach out and undo it on someone else's machine. Set
> it to `max-age=0` for the first deployment, confirm TLS serves correctly, and raise it only then.
> A year-long policy pinned to a host whose certificate is broken is a year-long outage.

Generate the three secrets with something that is not a keyboard:

```bash
openssl rand -base64 48
```

---

## 5. What the `prod` profile refuses

[SecretsGuard](../backend/src/main/java/dev/reception/common/config/SecretsGuard.java) runs under
`@Profile("prod")` and throws before the context finishes starting if any of **`JWT_SECRET`**,
**`MANAGE_LINK_SECRET`** or **`DB_PASSWORD`** is blank, still carries the `local-dev-only-` prefix,
or (for the two signing secrets) is shorter than 32 characters. The local defaults are all prefixed
`local-dev-only-` precisely so this can be a string comparison rather than a guess about entropy,
and the failure message names the variable and never the value, because that message reaches the
log.

**It does not check `MAIL_PASSWORD`.** `.env.example` ships it empty, an empty value is legitimate
for a relay that wants none, and the guard therefore cannot distinguish "no auth needed" from
"forgot the password" — so a deployment with an unset SMTP password starts cleanly and fails at the
first email instead. §3.3's health-endpoint behaviour is what catches it, at boot rather than at
send time. **It also does not check `OPENAI_API_KEY`**, deliberately: a placeholder there is a
working application, not a broken one.

**Unticked in the phase checklist, and this is why.** The guard is written and reads correctly, but
there is no test that starts the `prod` context with a default secret and asserts the refusal. Until
there is, the guard is *code that should work* rather than *a control that has been shown to work* —
the distinction this project keeps paying for. Do not treat §5 as a safety net on a first
deployment; check the three values yourself.

---

## 6. The deployment

```bash
git clone … && cd reception-booking-system
cp .env.example .env
# edit .env per §4, and apply the three file changes in §3
```

Bring it up with both compose files, which is what `make up-all` does:

```bash
docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d --build
```

`depends_on.condition: service_healthy` sequences it: Postgres and the mail relay first, then the
backend (whose healthcheck is `/api/health`, so it is not "up" until the database and SMTP both
answer), then the frontend, then Caddy. **Flyway runs on backend startup**, so the schema is created
by the first boot and there is no separate migration step. `make migrate` exists for applying
migrations against an already-running database.

Then check, in this order:

```bash
curl -fsS https://booking.example.com/api/health          # {"status":"UP","components":{…}}
make check-headers                                        # see the note below
docker compose ps                                         # every service healthy
```

**`make check-headers` needs its origin changed.** It reads `APP_PORT` from `.env` and asserts
against `http://localhost:$APP_PORT`, which is not this origin. Point it at the domain, or run the
same assertions by hand — it checks the four static headers, five CSP directives, the **absence** of
`unsafe-eval`, and the absence of a `Server` header.

**There is no seed here, and that is deliberate.** `make seed` is guarded to the `local` profile so
it can never run anywhere else. A fresh production instance has an empty database; the first owner
registers through the application like any other.

---

## 7. What is verified, and what is not

The distinction this project has learned to draw the hard way — a verified fixture is not a verified
assertion.

**Measured, on a running containerised stack, this session:**

- **The per-IP rate limits survive a reverse proxy, and a spoofed header does not defeat them.**
  This is the single most load-bearing claim in the document, because
  [application.yml](../backend/src/main/resources/application.yml) sets
  `server.forward-headers-strategy: framework` — the backend *trusts* `X-Forwarded-For`, and its own
  comment says that trust is safe only because the proxy is the only ingress. Both failure modes the
  code warns about are absent, measured rather than read:

  | Probe | Result |
  |---|---|
  | 8 requests to `/public/appointments/lookup` (5/hour/IP) from one real source, each carrying a **different forged** `X-Forwarded-For` | `422, 422, 422, 422, 422, 429, 429, 429` — forging the header **does not** buy a fresh bucket |
  | 3 requests to the same endpoint from a **different real source address** | `422, 422, 422` — a genuinely different client **does** get its own bucket, so the limits have not collapsed into one global one |
  | The exhausted source again, after the above | `429`, `Retry-After: 3584` — the bucket is per-source and holds for the hour |

  The second row is the one that matters and is easy to skip: rows one and three alone are equally
  consistent with *every visitor sharing one bucket keyed on Caddy's address*, which would lock out
  real users while stopping nobody.

- **`/api/health` reports database and mail separately**, and returns `503` when either is down —
  which is what makes §3.3's boot-time failure happen.

**Read from the files, and not executed:** everything in §3 and §6. No certificate has been issued,
no Caddyfile with a domain site address has been started, and the loopback-binding change has not
been run. The `443`/`80` publishing, the ACME challenge, and the `respond 404` block in §3.4 are all
reasoning about Caddy's documented behaviour.

**Not verified at all:** §5's refusal. See the paragraph there.

---

## 8. What is still wrong after all of this

Each of these is an accepted MVP risk from [06-security.md](./06-security.md) §15 or an open item in
[phase 11](./phases/phase-11-hardening-and-deployment.md). None is fixed by deploying carefully, and
a real tenant's data behind any of them is a decision, not an oversight.

**One instance only.** Rate limiting is Bucket4j **in memory**
([06-security.md](./06-security.md) §5). Two instances behind one proxy means two independent sets of
buckets and every published limit doubled — including the 5-per-hour on Confirmation Code lookup,
which exists to make brute force expensive. Externalising to Redis is the documented first scale-out
task, and it is a prerequisite for a second instance rather than an optimisation.

**No backups.** Stated as out of scope rather than assumed. The database lives in a Docker volume on
one host. `docker compose down -v` — one character from `down`, and the flag `make down-e2e` uses —
deletes it. Nothing in this repository takes a backup, tests a restore, or would notice the volume
was gone until the application failed to start.

**No secret rotation.** `JWT_SECRET` and `MANAGE_LINK_SECRET` are single values with no versioning.
Rotating the JWT secret invalidates every access token, so every owner is logged out at once;
rotating the Manage Link secret invalidates every outstanding link, so every customer holding one is
locked out of their own appointment until the next email. Both are survivable and neither is
graceful. A versioned-key scheme is a recorded V1.1 item.

**~~Nothing deletes an `ai_message`.~~ Built, and no longer on this list.** A transcript holds the
customer's name and phone number as they typed them, and for ten phases nothing had ever deleted
one. `TranscriptPurgeJob` now runs hourly and deletes every transcript ninety days past its
conversation's last activity; the conversation row is kept, marked with `messages_purged_at`, for
its cost accounting. `AI_RETENTION_ENABLED` switches the job off and **is not a way to widen the
window** — ninety days is a constant in `ConversationLimits`, deliberately not settable from
`.env`. A host that sets that variable to `false` is back on this list and nothing will say so.

**No email verification, no account lockout, no 2FA.** All three are named and accepted in
[06-security.md](./06-security.md) §15, with the reasoning for each.

**Nothing is watching.** Phase 11's observability items — structured JSON logs carrying request id
and `business_id`, LLM call metrics, slow-query logging — are open. Logs go to stdout and the
container runtime, and nothing aggregates, alerts or retains them.

---

## 9. Related

- [06-security.md](./06-security.md) — the controls this document configures, and §15's accepted risks
- [02-product-architecture.md](./02-product-architecture.md) §1 — the five-container topology
- [.env.example](../.env.example) — every variable, with the local value and the reasoning
- [README.md](../README.md) — the local path, which is the supported one
- [phase-11](./phases/phase-11-hardening-and-deployment.md) — what remains open
