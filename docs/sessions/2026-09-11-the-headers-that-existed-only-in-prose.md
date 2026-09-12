# Session handoff — 2026-09-11 — the headers that existed only in prose

> **Purpose.** Enough context to start phase 11 without re-reading anything. §1 is where things
> stand; §2 is what the pre-phase-11 survey found **green**, which is the part that saves work;
> §3 is the four drifts nobody had filed; §4 is the two phase-11 items that are *design* work
> rather than typing; §5 refines **G18**; §6 is the clearance — eight commits pushed, CI green,
> merged, branches level.
>
> **Read §3.1 before writing phase 11's security-header test.** The test is specified against
> Caddy, and Caddy is missing two of the headers `06-security.md` says it sets. Written against
> the file, the test goes green and leaves the document false — which is T39's failure mode with
> the documents swapped.
>
> **No product code was touched.** `backend/src` and `frontend/src` end byte-for-byte as they
> started — `git diff 5c898e2..HEAD -- backend/src frontend/src` is empty. The work was a survey,
> and then shipping the backlog the survey said was the biggest problem.
>
> **Nothing is outstanding but this document.** `origin/main` and `origin/dev` are both
> `fb9717a` with identical trees; the only thing `dev` carries beyond them is the commit that
> adds this handoff. The second session since phase 07 to end with the code level.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#26]: https://github.com/sanama-stack/reception-booking-system/issues/26
[#27]: https://github.com/sanama-stack/reception-booking-system/pull/27
[previous]: ./2026-09-11-the-gate-that-would-have-ticked-itself.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`fb9717a`** — merge commit of [#27] |
| `origin/dev` | **`fb9717a`** — fast-forwarded, **zero divergence, identical tree** |
| `dev` | **one commit ahead** — this handoff, `eaa837b`. Everything else is level; the working tree is otherwise clean |
| Backend | **844 tests, 0 failures, 0 errors, 0 skipped** — **re-run this session**, not carried. 5m 50s |
| Frontend | `lint`, `typecheck`, `format:check` green locally; `build` green in CI |
| CI | **six checks green** on [#27] — Backend 6m07s, Frontend 1m17s, Compose smoke 2m37s, each twice |
| Migrations | none |
| New ADR | none |
| Issues | [#26] **closed** on merge. [#17] and [#15] open, untouched |
| Phase 11 | **not started** |
| **The OpenAI account** | **not re-checked this session.** No model was called. The out-of-credits state is carried from the [previous handoff][previous], not re-confirmed |

### 1.1 What this session did

Answered "what traps, gaps, issues do we have before phase 11", then acted on its own headline
finding. No commit here changes behaviour; the eight that shipped were already written.

---

## 2. What the survey found GREEN

Recorded first because it is the part that saves the next session work. Each was **verified**, not
read off a checklist.

| | |
|---|---|
| **The backend suite** | 844 / 0 / 0 / 0, re-run with `--rerun`. The number three handoffs carried is now measured |
| **`.env.example` is complete** | Diffed against every `${VAR}` in *both* compose files **and** every `application*.yml`. Exact in both directions — nothing read by the app is undocumented, nothing documented is unread. Phase 11's *".env.example audited against the compose file"* box is effectively done |
| **The health endpoint already covers database and mail** | [HealthController](../../backend/src/main/java/dev/reception/common/web/HealthController.java) opens a connection and performs the SMTP handshake — it reports the dependencies by *using* them rather than by reporting that a bean exists. Phase 11's observability box is done, and deliberately not Actuator |
| **The V8/V9 ceilings are comfortable** | `MAX_DURATION_MINUTES` is 1440 and `MAX_BUFFER_MINUTES` is 240. Phase 11's seed spec — Dato's Auto, a 240-minute full service with a 30-minute buffer — sits well inside both. This was checked because a ceiling added for *query* safety that rejected a *legitimate* service would have been a nasty way to discover the seed |

---

## 3. Four drifts nobody had filed

### 3.1 `06-security.md` documents two headers that exist nowhere — **T42**, **G20**

[06-security.md:171](../06-security.md) §13 says, of Caddy:

> Security headers via Caddy: `Strict-Transport-Security` (non-local), `X-Content-Type-Options:
> nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY`, and a
> Content-Security-Policy with no `unsafe-eval`.

[The Caddyfile](../../infra/caddy/Caddyfile) sets three of those and `-Server`. **There is no CSP
and no HSTS** — grepped across `backend/src`, `frontend/src`, `next.config.*` and `infra`, in every
profile. `SecurityConfig` sets `frameOptions().deny()` as the defence-in-depth copy and nothing else.

**Why this matters before phase 11 rather than during it.** The phase says *"Security headers
verified by an automated test against Caddy."* Whoever writes that test reads the Caddyfile, asserts
what is there, and goes green — and §13 keeps asserting two controls that have never existed. The
checklist ticks while a documented control is absent.

**T42 — a document can describe a control that was never built, and the test written from the
implementation will certify the gap.** T39 was a gate pointing at a document too old to bind. This is
the mirror: a document ahead of the code, where the natural way to write the test is to read the
code. The order has to be *decide, then assert* — implement CSP and HSTS, or correct §13 — and the
decision is the principal's because it changes what the MVP claims about itself.

**G20 — there is no CSP and no HSTS, and §13 says there are.** Filed, not fixed.

### 3.2 The tool count says eight in three documents and nine in one

`resolve_date` shipped in `c81d312`. [05-ai-architecture.md:33](../05-ai-architecture.md) was updated
with it — *"Eight tools, **plus a ninth under test**"* — and three documents were not:

| | |
|---|---|
| [07-mvp-scope.md:65](../07-mvp-scope.md) | "Eight tools; the model can do nothing else" |
| [08-testing-strategy.md:124](../08-testing-strategy.md) | "Each of the eight tools is called directly with a `ToolContext`" |
| [ADR-0004:9](../adr/0004-llm-confined-to-tools.md) | "eight validated, strictly-schema'd tools" |

**The resolution has a dependency the phase plan does not record.** Whether the right number is eight
or nine is [#17]'s verdict, and that verdict is unfunded. Phase 11's *"final consistency pass over
`/docs` and `CONTEXT.md`"* therefore cannot be scheduled as a last-day tidy: either the arm runs
first, or the pass writes "eight, plus a ninth under test" into all four and says so.

### 3.3 One more container-internal port, in the file a reader opens to understand ports

[infra/caddy/Caddyfile:3](../../infra/caddy/Caddyfile) still says *"Everything the browser talks to
is served from `http://localhost:8080`."* That is what Caddy listens on **inside** the compose
network; `9080` is what a browser can reach.

Same class as the two the [previous session][previous] fixed, and it survived because that grep swept
`.md` files. It is the worst remaining instance for its size: the Caddyfile is precisely where
someone goes to find out what the origin is.

### 3.4 The API documentation is unauthenticated in every profile

[SecurityConfig.java:94](../../backend/src/main/java/dev/reception/common/config/SecurityConfig.java)
permits `/docs/**`, `/openapi/**` and `/swagger-ui/**` to everyone, `prod` included.

Correct for the MVP's local-compose contract and not a defect against it. But phase 11 writes
`docs/deployment.md`, whose job is *"what would have to change first"* — and on an internet-reachable
VPS this publishes the entire API surface to anyone. It is **not** in §15's accepted-risks table, so
right now it is in neither place, which is the state that table exists to make impossible.

---

## 4. Two phase-11 items are design work, not typing

The [previous handoff][previous] §5.1 established that five phase-11 items have *zero* existing code.
Two of those five are harder than the phase document implies, and both need a decision before anyone
starts writing.

### 4.1 The deterministic model the E2E needs cannot be reached by a booted app — **G19**

Phase 11: *"The single Playwright flow … run against the scripted model so it is deterministic in
CI."*

[`ScriptedChatModel`](../../backend/src/test/java/dev/reception/ai/support/ScriptedChatModel.java)
lives in **`backend/src/test`** — the JVM test classpath. Playwright drives a **booted application**.
There is no profile-conditional `ChatModel` bean, no `@ConditionalOnProperty` anywhere in
`dev.reception.ai`, and nothing in `src/main` can serve a scripted conversation.

The route exists: `base-url: ${OPENAI_BASE_URL:...}`
([application.yml:132](../../backend/src/main/resources/application.yml)) is already configurable, so
a stub HTTP server speaking the chat-completions shape would work and would keep the production code
path honest — the E2E would exercise `OpenAiChatModel` rather than bypass it. But that server does
not exist, and **there is no live fallback**: the account is out of credits.

**G19 — the E2E's deterministic model is unbuilt, and the phase document describes it as available.**

### 4.2 Reflection does not remove the omission risk from the isolation suite

Phase 11 argues for reflection-based discovery because *"a test that must be remembered will
eventually be forgotten."* Counted this session: **17 `@RestController`s, 65 mapping methods**, of
which roughly 17 are public, auth or health and must not be probed for a 404.

So the suite is a reflection sweep **plus an exclusion list**, and the exclusion list is a thing that
must be remembered. A new public endpoint added to it by habit restores exactly the failure the
reflection was for.

Two things make it actually work, neither of which the phase document names:

- **Classify, do not skip.** Every discovered mapping must fall into a named bucket — tenant-scoped,
  public, auth, health — and an *unclassified* mapping must fail the build. Then a new endpoint
  cannot be silently omitted; it can only be deliberately labelled.
- **The cost is the fixture, not the reflection.** Probing endpoint X needs a valid **Business-B
  resource of the right type** for every path variable it takes. That is per-endpoint work across 48
  endpoints, and it is what the estimate should be built on.

---

## 5. G18, refined against the live database

The [previous handoff][previous] filed G18 from inspection. Re-checked here against the running
Postgres, and one detail is **worse than recorded**:

| Claim | Verified |
|---|---|
| 31 600 appointments across 10 tenants, this machine only | **Yes.** `git ls-files` has no generator |
| "no `flyway_schema_history` at all" | **Not quite — the table exists and is empty.** That is the worse state: Flyway would treat a fully populated database as unmigrated and attempt `V1` against it, rather than refusing outright |
| missing the V8/V9 ceilings | **Yes, exactly three.** Diffed `pg_constraint` against `reception`: `appointments_max_length`, `appointments_buffer_before_max`, `appointments_buffer_after_max` are absent |

The consequence is unchanged and is the reason this blocks: phase 11 aims all three NFR checks, G15
and G16 at a fixture that **cannot enforce the invariant the fast queries assume**. One over-long
appointment in a regenerated dataset and the bounded query never sees it — a fast, wrong answer, from
the instrument that is supposed to be checking. **The committed generator must migrate the schema,
not clone it.**

---

## 6. The clearance

The survey's headline was that the release state was the biggest problem: eight commits unpushed,
**385 insertions across nine files in the AI module that CI had never compiled**, and an
`origin/main` asserting three things this repository had already corrected.

| | |
|---|---|
| Push | `e33af63..5c898e2` to `origin/dev` |
| Pull request | **[#27]** — `dev` → `main`, eight commits |
| CI | six checks green, first independent run of `ResolveDateTool` and the prompt changes |
| Merge | **`fb9717a`**, a merge commit — [#26] closed automatically |
| Sync | `dev` fast-forwarded to `fb9717a` and pushed. Zero divergence, identical trees |

**A merge commit rather than `--squash`, against the README's own line.** The README's branching
section says `gh pr merge --squash`; #19, #22, #24 and #25 all landed as merge commits, and phase
05's handoff records that switch as deliberate. Squashing would also have collapsed
`c81d312 … the arm is UNFINISHED` into one subject line — and that subject is the whole mechanism by
which an unaccepted candidate can sit on `main` without being read as a resolution. **The README line
is now wrong about this repository's practice and should be corrected in phase 11's documentation
pass**, in one direction or the other.

**Because the merge commit already had `dev` as a parent, the sync was a fast-forward** — so unlike
`3fc2e63`, there is no "Merge main into dev to satisfy strict branch protection" commit in the
history this time.

### 6.1 Verified on the public branch, not assumed

- Build status reads **"phase 10 of 11 complete"**
- The only surviving occurrence of *"cannot invent"* is line 32 — the **retraction**, not the claim
- `.vscode/` is on `main`, all four files: the documented <kbd>F5</kbd> works on a clean clone and
  both README links resolve

---

## 7. The trap this session paid for — **T41**

The first `./gradlew test` printed:

```
BUILD SUCCESSFUL in 7s
5 actionable tasks: 5 up-to-date
```

It ran **nothing**. Every task was `UP-TO-DATE`, because no source had changed since the previous
session's run. The [previous handoff][previous] §8 already warns that the console *"does not
distinguish a stale result file"* and prescribes counting the XML instead. Counting the XML returned
**844, 0 failures** — from files timestamped before the session began. The prescribed remedy produced
the same wrong reassurance as the thing it was prescribed against.

Then, having started a real run with `--rerun`, counting the XML **mid-run** returned 844/0/0 again —
a blend of the finished classes of this run and the leftover files of the last, indistinguishable
from a completed pass.

**T41 — a stale green and a half-finished green look identical to a fresh one, and the XML count
tells you apart from neither.** The count is only meaningful between the `BUILD` line and the next
build. The sequence that works: `--rerun`, wait for `BUILD SUCCESSFUL`, *then* count — and check the
directory's mtime against the clock if there is any doubt. `--rerun` is doing the load-bearing work
here, not the counting.

---

## 8. Every open item

### 8.1 Issues

**[#26]** — **CLOSED** on merge.

**[#17]** — open. Carried as an accepted, measured defect: 10.6% of reschedule writes land on a date
the customer never named (5 of 47, CI [3.5%, 23.1%]), rising to **55.2%** on relative phrasing. The
fourth candidate, `resolve_date`, is **fifteen trials of fifty** short of a verdict and is **live on
`main`** — an unconditional `@Component`, named by the system prompt at
[SystemPromptBuilder.java:193](../../backend/src/main/java/dev/reception/ai/application/SystemPromptBuilder.java)
and `:338`. **Backing it out is a prompt revert as well as a tool deletion**, and that cost rose the
moment it landed on `main`.

**[#15]** — open, untouched, title still names a diagnosis a later session disproved, which blocks
its own acceptance under the *rate, date, issue* rule.

### 8.2 Gaps

Carried: **G1**, **G3**, **G8**, **G10**, **G11**, **G13**, **G14**, **G15** (calendar half),
**G16**, **G18** (refined — §5).

**G9** stays as it was: `pnpm build` has still not run *locally* — a dev server was up on `9082`
throughout. It ran green in CI on [#27].

New:

**G19 — the E2E's deterministic model is unbuilt.** §4.1. `ScriptedChatModel` is test-classpath only
and Playwright drives a booted app.

**G20 — there is no CSP and no HSTS, and `06-security.md` §13 says there are.** §3.1.

### 8.3 Traps

Carried T1–T40. New:

**T41 — a stale green and a half-finished green look identical to a fresh one.** §7. Neither the
console nor the XML count distinguishes them; only `--rerun` plus waiting for the `BUILD` line does.

**T42 — a document can describe a control that was never built, and the test written from the
implementation will certify the gap.** §3.1. The mirror of T39: decide, then assert.

### 8.4 Security

**S1 — no model was called this session, and the credit state was NOT re-checked.** Zero API
requests, zero tokens, zero cost. The out-of-credits state is carried from the previous handoff
rather than re-confirmed; §9 has the one-call check.

**Nothing became public this session that was not already reviewed.** The eight commits pushed were
written and read in earlier sessions, including the `.vscode` files the previous handoff cleared
individually.

### 8.5 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; **nothing deletes an `ai_message`** (re-verified — no `delete*` or
`@Modifying` in `src/main` touches the table); rate-limit buckets are in memory; `make seed` is an
`echo` at [Makefile:79](../../Makefile); no frontend test runner; no `docs/deployment.md`; no
Playwright.

---

## 9. Next steps, in order

### P0 — the principal's

1. **Add credits, then finish the [#17] arm.** Ten minutes of machine time. It is now the only thing
   standing between `resolve_date` and either acceptance or removal, and it decides what the tool
   count in §3.2 should say.
2. **Decide CSP and HSTS** (§3.1) — implement them, or correct §13. This must happen **before**
   phase 11's header test is written, or the test certifies the gap.

### P1 — phase 11, in a workable order

3. **The two-tenant seed.** It is the prerequisite for the E2E, the isolation fixtures and phase 08's
   missing "Any available" fixture (G8), and it unblocks more than anything else in the phase.
4. **The perf generator (G18)**, migrating rather than cloning, before any perf work rests on
   `reception_perf` again. Then drop the ad-hoc database.
5. **The E2E's model strategy (G19)** — a stub at `OPENAI_BASE_URL` keeps `OpenAiChatModel` on the
   path. Decide it before writing the flow.
6. **The isolation suite**, built as classify-or-fail (§4.2), with the estimate set by the fixture
   work rather than the reflection.

### P2

7. [#15]'s title, which blocks its own acceptance.
8. The documentation drifts: §3.2's tool count, §3.3's Caddyfile comment, §3.4 into
   `docs/deployment.md` or §15, and §6's README merge-strategy line.
9. G15's calendar half · G16 · the other two instruments' error counters (T36).

---

## 10. Commands

```bash
# Where things actually stand.
git status -sb && git log --oneline -3 && git rev-list --left-right --count origin/main...origin/dev

# The whole backend suite, ~6 minutes, from backend/. --rerun is load-bearing (T41).
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --rerun

# Count it ONLY after the BUILD line. Check the mtime too.
ls -la build/test-results/test | head -2
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for x in glob.glob('build/test-results/test/*.xml'):
    r=ET.parse(x).getroot()
    t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors')); s+=int(r.get('skipped'))
print(t, f, e, s)"

# Frontend gates. NOT `build` while a dev server is up.
cd frontend && pnpm lint && pnpm typecheck && pnpm format:check

# Is the account still out of credits? One call, costs nothing when it is.
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $(grep '^OPENAI_API_KEY=' .env | cut -d= -f2-)" \
  -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}],"max_tokens":1}'

# The deciding arm, once there are credits. From backend/.
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
PROBE_CONVERSATIONS=50 PROBE_DATE_STYLE=WEEKDAY ./gradlew test -PincludeTags=probe \
  --tests '*RescheduleDateFidelityRateTest' --rerun

# G20, in one line: what does Caddy actually set?
grep -n -A6 'header {' infra/caddy/Caddyfile

# G18, against the running database. Expect three constraints present in one and absent in the other.
docker compose exec -T postgres psql -U reception -d reception -c \
  "select conname from pg_constraint where conrelid='appointments'::regclass and contype='c' order by 1"
docker compose exec -T postgres psql -U reception -d reception_perf -c \
  "select conname from pg_constraint where conrelid='appointments'::regclass and contype='c' order by 1"

# .env.example completeness, both directions. Expect both to print nothing.
grep -ohE '\$\{[A-Z_]+' docker-compose.yml docker-compose.apps.yml | tr -d '${' | sort -u > /tmp/cv
grep -ohE '\$\{[A-Z_]+' backend/src/main/resources/application*.yml | tr -d '${' | sort -u >> /tmp/cv
grep -oE '^[A-Z_]+=' .env.example | tr -d '=' | sort -u > /tmp/ev
comm -23 <(sort -u /tmp/cv) /tmp/ev
```

**Do not run `./gradlew` twice at once against this tree**, and **do not run `pnpm build` while the
dev server is up**.

---

## 11. Confidence

**High — the green list in §2.** Each item was executed rather than read. `.env.example` was diffed
in both directions against two sources; the health endpoint was read in full; the suite was re-run
after being caught reporting a stale pass.

**High — §3.1, the missing headers.** A grep for `Content-Security-Policy` and
`Strict-Transport-Security` across `backend/src`, `frontend/src`, `next.config.*` and `infra` returns
one hit, and it is `frameOptions().deny()` in `SecurityConfig`. The absence is the easy half; §3.1's
claim about what the *test* would do is an argument, not a measurement.

**High — §5, G18's refinement.** `pg_constraint` diffed between the two live databases. The three
absent constraints are named by the query, not inferred from the migrations.

**High — §6, the clearance.** Every assertion re-read off `origin/main` after the merge: the build
status line, the retraction at line 32, `git ls-tree` for `.vscode/`, and tree-hash equality between
the two branches.

**Medium — §4.2's estimate shape.** That the fixture dominates the reflection is a judgement from
counting 65 mappings and reading their path variables, not from having built the suite.

**Medium — §3.2's dependency claim.** That the tool count cannot be settled before [#17]'s verdict
assumes the verdict is the only thing that decides it. A principal could instead rule that the ninth
tool stays regardless, which would settle all four documents today.

**None — [#17]'s verdict.** Unchanged at fifteen of fifty. Its standing moved last session; its rate
has not moved since it was measured.

**None — the OpenAI credit state.** Carried, not checked. No model was called.
