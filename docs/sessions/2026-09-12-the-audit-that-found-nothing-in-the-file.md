# Session handoff — 2026-09-12 — the audit that found nothing in the file

> **Purpose.** Two pull requests merged and the deployment path written at last. §2 is the merge
> that had been left as a judgement; §3 is `docs/deployment.md`, and **the three files that cannot
> deploy as committed**; §4 is the `.env.example` audit, whose finding was **not in the file** — it
> was complete in both directions, and the defect was in what it could not express; §5 is the
> measurement that **refuted my own reading of the code**, and the second probe that made the first
> one mean anything.
>
> **Built no product code.** `git diff` over `backend/src` and `frontend/src` is empty across both
> pull requests. Every file touched is a document, a `Makefile` recipe or a comment block.
>
> **`main` moved twice** — `d420568` → `91e1cfa` (PR [#32]) → `1db8a49` (PR [#33]) — and `dev` and
> `main` end **level**, zero commits apart, for the first time in this session's memory.
>
> **Nothing is outstanding.** The tree is clean and every claim below is read out of a job log, a
> live probe, or arithmetic.

[previous]: ./2026-09-12-the-test-that-needed-the-id-to-be-missing.md
[#32]: https://github.com/sanama-stack/reception-booking-system/pull/32
[#33]: https://github.com/sanama-stack/reception-booking-system/pull/33

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`1db8a49`** — PR [#33] merged. Moved twice this session; was `d420568` |
| `origin/dev` = `dev` | **level with `main`**, nothing unpushed, tree clean |
| CI | **green on every run carrying code.** `34706339512` (`471c9fa`, the tip) is where §4 and §6 are read from; PR [#33]'s own eight checks all passed. `34706291075` reads `cancelled` — the `cancel-in-progress` pattern [the previous handoff][previous] already documented, superseded by the next push, **not a break** |
| Frontend | **22 files, 76 tests** — unchanged. No frontend source or test was touched |
| Backend | **not re-run by anything here.** No Java changed |
| Migrations | **none.** New ADR: none |
| Issues | [#17] and [#15] open, untouched. **No model was called**; no credit check was made |
| Phase 11 | **43 boxes ticked, 29 open.** Two ticked here; the Documentation section is down to its **last row** |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 2. The two merges

**PR [#32]** was [the previous handoff][previous]'s §8.1 — five commits, `main` five behind, left as
a judgement rather than a task. The judgement was to merge. Eight checks green, merge commit,
`--delete-branch` deliberately absent because `dev` is long-lived.

**PR [#33]** is this session's own work: three commits, four files, no product code. Eight checks
green. **Its Compose smoke test is load-bearing rather than incidental** — that job runs
`make up-all`, which now depends on the gate §4.2 adds, so the gate ran in CI and the topology still
came up.

### 2.1 One commit in the log was not written here

`9a97421` — the one-line correction to [the previous handoff][previous]'s CI row — was **already
committed and pushed** when this session went to make it. `git add && git commit` answered *"nothing
to commit, working tree clean"* against a tree whose change was already in `origin/dev` under a
message this session did not write.

It is recorded because a future session reading `git log` will find it between two commits that were
written here, and the content is exactly the staged change. It was left alone rather than amended.
Something else — another session, or the user — got there first.

---

## 3. `docs/deployment.md`

A single VPS, five containers, Caddy terminating TLS for one domain. The path phase 11 asks for, and
the one [the Caddyfile](../../infra/caddy/Caddyfile) and [`.env.example`](../../.env.example) have
been pointing at **by name** since they were written — two dangling references, now resolved.

**It opens by saying no host has ever run it.** Every other claim in this project's documentation is
read off a CI log or taken as a measurement. This one is reasoned from the compose files, the
Caddyfile and the application configuration. A document that did not say so would read like a
report, and §7 of it splits the content line by line into what was measured and what was inferred.

### 3.1 Three files cannot deploy as committed

Each **fails quietly**, which is the reason they are worth naming in advance rather than discovering
one `curl` at a time:

- **The Caddyfile issues no certificate.** `auto_https off` with a port-only site address (`:8080`)
  leaves Caddy with nothing to request one *for*. Both are correct locally — the origin is
  `http://localhost:9080` and there is nothing to encrypt — and **neither errors**. You get a
  working plain-HTTP site on the wrong port.
- **`docker-compose.yml` publishes Postgres and both Mailpit ports on `0.0.0.0`, unconditionally.**
  On a laptop that is deliberate and the comment says so. On a public host it publishes the database
  and a web UI holding every email the system has ever sent. **A host firewall does not cover it** —
  Docker writes its own rules in the `DOCKER-USER` chain, below `ufw`.
- **Mailpit delivers no mail.** It captures. Every confirmation and Manage Link would stop at a web
  UI nobody is watching.

### 3.2 A documentation/code mismatch, named rather than fixed

[06-security.md](../06-security.md) §12 says the database port is exposed *"only in the `local`
compose profile"*. **There is no such conditionality in any file.** `docker-compose.yml` publishes
it in every topology that includes it, and `docker-compose.apps.yml` does not take it away. The
control §12 describes **does not exist**; the sentence describes an intention.

This is the same shape as the finding §13 of that same document already records about the security
headers, which were described for ten phases before any file set them — and it is the second
instance, which is what makes it worth a gap rather than a note (**G26**, §8.2).

It is **named in the deployment document and not fixed**, because fixing it is a decision about
which side moves: tighten the compose file, or correct the sentence. That is a decision, not a
cleanup.

---

## 4. The `.env.example` audit

### 4.1 The file was already complete, and mechanically so

Every variable consumed by the three compose files, the backend's `application*.yml`, the Caddyfile
and the frontend was extracted and diffed against what is declared. **The two sets match exactly, in
both directions** — nothing read that is not declared, nothing declared that nothing reads. The
local `.env` has the same key set too.

Two absences are correct and were checked rather than assumed: `BACKEND_UPSTREAM` and
`FRONTEND_UPSTREAM` are set by compose rather than by `.env`, and the `NEXT_PUBLIC_*` pair is
**derived** from `APP_PORT` / `FRONTEND_PORT` in `next.config.ts` rather than written down twice.

Two of the file's prose claims were checked **by measurement rather than by reading**:

- **The `CSP_SCRIPT_EXTRA` double-quoting note is true and load-bearing.** The running `make up`
  stack serves `script-src 'self' 'unsafe-inline' 'unsafe-eval'`; the containerised stack serves the
  same line without it. Had Compose stripped the quotes the policy would have named a *host* called
  `unsafe-eval` and permitted nothing, silently.
- **One claim was wrong.** The header said the `prod` profile refuses to start while *"any secret"*
  still holds its local default. `SecretsGuard` checks **three of the five** secrets
  [06-security.md](../06-security.md) §9 lists — it skips `MAIL_PASSWORD` and `OPENAI_API_KEY`, for
  reasons that are good but are not *any*. Corrected in place, with the reasons and a pointer to
  the deployment document's §5, which says the guard **has no test behind it**.

### 4.2 The finding was not in the file, and could not have been

**Four ports are written down twice. `make check-ports` guarded one pair.**

| Coupling | Was |
|---|---|
| `FRONTEND_PORT` = `frontend/package.json`'s `next dev --port` | guarded |
| `POSTGRES_PORT` = `DB_PORT` | **not** |
| `MAILPIT_SMTP_PORT` = `MAIL_PORT` | **not** |
| `APP_PORT` = the port inside `APP_PUBLIC_URL` | **not** |

The port block's own comment **invites** moving them — *"deliberately away from the 3000/8080/5432
range so this project never collides with another one"* is an instruction to change them when it
does. Three of the four then fail somewhere else entirely: a connection refused against Postgres,
mail that only the outbox knows never sent, or **Manage Links pointing at a dead port** — which is
the one a customer finds rather than a developer.

All four are guarded now. The three `.env`-internal pairs are checked only while the matching host
is local, because that is the only topology in which they are coupled: `make up-all` overrides
`DB_*` and `MAIL_*` with the compose service names, and a `DB_HOST` pointing at a real server has
every right to its own port.

**Shown red on demand, one planted half-move at a time.** `POSTGRES_PORT=9095`, `MAIL_PORT=9094`,
`APP_PORT=9090` and `FRONTEND_PORT=9092` each failed naming its own cause, and `.env` was restored
byte-identical after every one.

The **fifth** coupling is not guarded and the file now says why: `SERVER_PORT` is duplicated by a
hardcoded `http://localhost:9081/api` fallback in `src/lib/api/client.ts`, which a gate reading
`.env` cannot see.

### 4.3 The gate did not reach the topology that deploys

It ran at `make up` only — and CI runs `up-all` and `up-e2e`. **The shape that deploys, and the
shape CI proves, never met it.** `up-all` depends on it now.

For `up-all` this is **deliberately one check wider than that topology needs**, and the comment
above the target says so rather than leaving it to be discovered. `APP_PORT` against
`APP_PUBLIC_URL` is live there. The `DB_*` and `MAIL_*` pairs are inert — compose overrides both —
and are still enforced, because `.env` describes one *machine* rather than one *topology*: letting
it go internally inconsistent under `up-all` only moves the failure to the next `make up`.

**Shown to stop the target rather than merely to print.** With `APP_PORT` planted at 9090,
`make up-all` failed at the prerequisite with the gate's own message, **compose was never invoked**,
and every running container came through untouched — checked by hashing `docker ps` either side.

---

## 5. The measurement that refuted the reading

**This is the reusable part of the session.**

The backend sets `server.forward-headers-strategy: framework`, so it **trusts** `X-Forwarded-For`,
while `RateLimitFilter` reads the peer address deliberately and its own comment explains that the
trust is safe *only because the proxy is the only ingress*. Reading those two facts together
suggested a hole: Caddy **appends** to an incoming `X-Forwarded-For`, and a framework that took the
**first** entry would read the attacker's forged value — buying a fresh bucket per request, and
turning every per-IP limit off for exactly the caller it exists to stop.

That reading was plausible, specific, and **wrong**. Probed against the running containerised stack:

| Probe | Result |
|---|---|
| 8 requests to `/public/appointments/lookup` (5/hour/IP) from one real source, each carrying a **different forged** `X-Forwarded-For` | `422 ×5, 429 ×3` — forging buys **no** fresh bucket |
| 3 requests from a **different real source address** (inside the compose network) | `422 ×3` — a genuinely different client **does** get its own |
| The exhausted source again, interleaved after the above | `429`, `Retry-After: 3584` |

**The middle row is the one that matters, and it is the one that is easy to skip.** Rows one and
three alone are *equally consistent* with every visitor sharing one bucket keyed on Caddy's address
— which would lock out real users while stopping nobody, and is the **other** failure mode
`application.yml`'s comment warns about. One source address cannot tell the two apart. Both are
absent, measured rather than read.

---

## 6. Six traps

**T63 — a code reading that suggests a vulnerability is a hypothesis, not a finding.** §5's was
precise enough to write up and would have been wrong in a security document. Probe it before it
reaches prose.

**T64 — one source address cannot distinguish "correctly per-IP" from "collapsed into one global
bucket".** Both answer `429` on the sixth request. The second probe — a *different real* client —
is what makes the first mean anything. Any rate-limit check from a single machine is measuring less
than it looks.

**T65 — an audit's finding may lie entirely outside the artifact audited.** `.env.example` was
complete in both directions and had one wrong sentence; the defect was in what a `.env` file
**cannot express** — that four of its values are duplicated elsewhere and must agree. Auditing the
file against its consumers is the beginning, not the end.

**T66 — a gate is only as wide as the targets that depend on it.** `check-ports` had guarded one
coupling since phase 01 and ran at `make up`, which CI never invokes. *Which targets depend on the
gate* is part of the gate, and is not visible from the recipe.

**T67 — Caddy appends to `X-Forwarded-For` rather than replacing it.** This did not turn out to
matter here (§5), but the header the backend reads is `forged, real` and not `real`, so **what the
framework does with a multi-valued header is the whole question** — and it is settled in
`application.yml`, not in the filter.

**T68 — the second instance is what makes a documentation/code mismatch a gap.** §3.2 is the same
shape as the security-headers finding: prose describing a control no file implements. One is a
correction; two is a pattern, and nothing in this project checks a documented control against its
absence.

---

## 7. What phase 11 still wants

**Two boxes ticked**, both in the Documentation section: `docs/deployment.md`, and the
`.env.example` audit. **That section is down to its last row** — the final consistency pass over
`/docs` and `CONTEXT.md`, which §3.2's mismatch is now an input to.

Still open and untouched here: rate limits per public endpoint, the log-redaction test, the `prod`
default-secret refusal **test** (the guard exists; nothing proves it fires), the full-history secret
scan, the error-response leakage review, `ai_message` retention, the revenue remainder from
ADR-0010, and all four observability items.

**One observability row looks already satisfied and was not ticked.** *Health endpoint covering
database and mail* is unticked, and `HealthController` checks both, returning `503` when either is
down — which is also what makes a bad SMTP configuration keep the backend unhealthy and Caddy from
starting at all. The code was read, not tested; **ticking it on a reading is exactly T61**, so it
was left open for someone who will check whether a test stands behind it.

---

## 8. Every open item

### 8.1 Issues

**[#17]** and **[#15]** — open, untouched. **No model was called this session**, and no credit check
was made.

### 8.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**.

**New: G26** — *nothing checks a documented control against its absence.* [06-security.md](../06-security.md)
§12 describes a conditional port exposure no file implements (§3.2), and §13 of that document
already records the same shape about the security headers, which went ten phases described and
unimplemented. Both were found by a person reading prose beside a file. The headers now have
`make check-headers`; §12's claim has nothing, and neither does any other sentence in that document.

**No gap is closed here.**

### 8.3 Traps

Carried T1–T62. New: **T63**–**T68** (§6).

### 8.4 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; **nothing deletes an `ai_message`**; rate-limit buckets are in memory;
no key rotation; no backups. **No longer carried: no `docs/deployment.md`, and the unaudited
`.env.example`.**

### 8.5 Two environment notes

**The E2E stack is still running** on ports 9180–9185, now five hours older than when [the previous
handoff][previous] found it. **Its backend image is still faithful to `HEAD`**, because no
`backend/src` changed here either — and that is the thing the next session most needs to know, since
it stops being true the moment anyone touches Java.

**One rate-limit bucket on it is exhausted.** §5's probes consumed the host's hourly allowance on
`POST /public/appointments/lookup`, `Retry-After` 3584 at the time. In-memory buckets, so it clears
on its own or on a restart of that container. Nothing else about the stack was changed; no tenant
was created.

---

## 9. Next steps, in order

Nothing from this session is half-finished, and there is no pull request outstanding.

1. **`ai_message` retention** — the oldest carried item and the only open one touching data the
   system keeps about real people. **Read §9.1 before starting it.**
2. **The final `/docs` consistency pass**, which closes phase 11's Documentation section. G26 and
   §3.2 are its first inputs, and §3.2 needs a decision before it needs an edit: either
   `docker-compose.yml` stops publishing the database port unconditionally, or
   [06-security.md](../06-security.md) §12 stops claiming it does not.
3. **The security block**: rate limits per public endpoint, log redaction, the `prod`
   default-secret refusal test, the full-history secret scan, error-response leakage.
4. **The principal's**: credits for [#17]'s remaining arm; [#15]'s title.

### 9.1 Retention is the first item this machine cannot verify

It needs Java **and** a migration. That invalidates the running E2E stack's backend image (§8.5),
and **the images cannot be rebuilt here** — T62: BuildKit's `load metadata` times out against Docker
Hub, and the Gradle distribution download inside the build container has no workaround at all.

So the loop that has served the last three sessions — change it, `curl` the live stack, believe the
answer — **is not available for this one**. Its verification has to happen in CI, which means
writing the assertion first and letting a run tick it. That is T61's discipline, and it is the whole
shape of the next session rather than a footnote in it.

---

## 10. Commands

```bash
# The port gate, on its own. Silent means the four couplings agree.
make check-ports

# The frontend suite. About five seconds, no Docker, no network.
cd frontend && pnpm test

# The security headers, against a running origin (expects the up-all topology).
make check-headers
```

**A browser still cannot be launched on this machine**, and the E2E images cannot be rebuilt here
(T62). If a stack is already up, `curl` against `http://localhost:9180/api` is the available
substitute — and §9.1 is the case where it stops being enough.

---

## 11. Confidence

**High — that `.env.example` is complete.** The declared set and the consumed set were extracted
mechanically from five sources and diffed both ways. This is not an eyeball.

**High — that the four port couplings are guarded and the gate can fail.** Each was planted and
each went red naming its own cause; `.env` was restored byte-identical every time, verified by
`diff`.

**High — that the gate stops `up-all` before compose runs.** Observed: the gate's message, no
Docker output, and an unchanged `docker ps` hash either side.

**High — that the per-IP rate limits key on the real client.** Three probes, including the one that
distinguishes the two failure modes (§5). This is a measurement on a live containerised stack, not a
reading.

**High — that the CSP quoting note is true.** Both topologies' served headers were read.

**Medium — that `docs/deployment.md` would work.** Its §3 and §6 are **reasoned, not executed**. No
certificate has been issued, no domain site address has been started, and the loopback-binding
change has not been run. The document says so in its own §7 rather than leaving a reader to assume.

**Low — that §3's list of blockers is complete.** It is what reading four files found. A first real
deployment will find more, and the value of the list is that it starts below the waterline rather
than at zero.

**None — [#17]'s verdict.** Unchanged. No model was called.
