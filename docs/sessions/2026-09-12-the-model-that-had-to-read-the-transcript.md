# Session handoff — 2026-09-12 — the model that had to read the transcript

> **Purpose.** The previous session's one open decision was taken, and phase 11's next item was
> designed and half built. §2 is the merge. §3 is **[ADR-0011]**, and the audit that reframed the
> question before it was answered — phase 11 *looked* like it had already ruled, and had not. §4 is
> the fake provider and the constraint that shapes it: **it cannot be a tape**. §5 is how it was
> proven, twice, and shown able to fail. §6 is three findings, one of them a retraction of my own.
>
> **Built no product code.** `backend/src` and `frontend/src` end **byte-for-byte** as they started —
> `git diff 34c454e..HEAD -- backend/src frontend/src` is empty. Everything here is documentation,
> compose configuration, and one new tool under `infra/`.
>
> **Read §6.2 before writing the Playwright flow.** An empty `OPENAI_API_KEY` does not fail the
> Receptionist, it **degrades** it to the Classic Flow — so an E2E pointed at the fake provider with
> no key set would book successfully, through the wrong door, and look green (**T47**).
>
> **Nothing is outstanding.** `dev` and `origin/dev` are both `95b0812`, the working tree is clean,
> and CI is green on all three jobs. `main` is two behind and that is a judgement, not a task — the
> same one §2 records being taken the other way this morning.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#28]: https://github.com/sanama-stack/reception-booking-system/pull/28
[ADR-0011]: ../adr/0011-the-e2e-fake-provider-lives-behind-the-base-url.md
[previous]: ./2026-09-11-the-seed-and-the-headers-that-existed-at-last.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`34c454e`** — [#28] merged this session, CI green on the merge commit |
| `origin/dev` = `dev` | **`95b0812`** — **two ahead of `main`**, nothing unpushed, working tree clean |
| CI | **green on `dev`** (`34690085462`): Backend, Frontend and Compose smoke. The new step was checked for having *run* rather than for the job being green — see §5.3 |
| Backend | **860 tests, and they were NOT re-run.** No Java changed, so the number is carried from the [previous handoff][previous] rather than counted here. Treat it as inherited, not as evidence |
| Frontend | untouched. No `pnpm build` ran locally; the dev server stayed up throughout (G9 unchanged) |
| Migrations | **none** |
| New ADR | **[ADR-0011]** — the E2E's deterministic model lives behind `app.ai.base-url` |
| Issues | [#17] and [#15] open, untouched. **No model was called**, and the credit state was **not** re-checked this session — the [previous handoff][previous] §7.1 is the last reading |
| Phase 11 | **18 boxes ticked, 53 open** — unchanged. G19's build half is done but **no box is ticked for it**, because the flow that consumes it does not exist (§7.2) |
| New tool | `infra/fake-provider/` — server, self-test and README, wired into CI and `make` |

---

## 2. The merge the previous session left open

[Its §8 item 0][previous] ended on a judgement rather than a task: whether seven commits went into
`main` or waited for more of phase 11. **Taken: merge.** Opened as [#28] with eight commits (the
seven, plus the commit that finished that handoff) and merged as **`34c454e`**, a merge commit — what
every pull request since #20 has been, and what phase 05 recorded as the thing that ends squash
conflicts on a long-lived `dev`. CI is green on the merge commit itself, waited for rather than
inferred from the pull request's head.

`dev` was then **fast-forwarded** onto it, which is the step whose omission put two content-free
*"Merge main into dev to satisfy strict branch protection"* commits into this history.

**The first `gh pr merge` was refused**, and the message named a cause that was not the cause:
*"the base branch policy prohibits the merge"*, while `mergeStateStatus` read `CLEAN`, protection
required **zero** approving reviews, and all three required checks had passed. The retry succeeded
with nothing changed — GitHub was still computing mergeability. Recorded because the obvious response
to that error is `--admin`, which would have bypassed a protection that was not blocking anything.

The [previous handoff][previous] carries this as dated addenda in four places rather than as a
rewrite: that session genuinely did not take this decision, and a handoff that read as though it had
would be the drift this project keeps filing traps about.

---

## 3. G19, and the ruling that was never made — **T46**

Phase 11 said the E2E runs *"against the scripted model so it is deterministic in CI"*. That reads
like a decision already taken. **It was not one.** `ScriptedChatModel` lives in `backend/src/test`,
Playwright drives a booted application, and `OpenAiChatModel` is the only `ChatModel` on the main
classpath. The phase document was describing an option nobody had built, in the grammar of a ruling.

**T46 — a specification sentence is not a decision, and the two are written identically.** The
difference is whether anything was ever weighed. Before defending or overturning a choice, find its
origin; "the document says so" is not an answer when the document is where the assumption first got
written down. This is T39's sibling — *a gate is only as strong as the oldest document it points at*
— one layer further in: there the wording was stale, here it was never load-bearing at all.

So the question was reopened rather than overridden, three options were put to the principal, and the
ruling was **the fake provider behind `base-url`**. [ADR-0011] records it, including why the other two
were rejected:

- **A `@Primary` `ChatModel` bean on the main classpath** would leave `OpenAiChatModel` exercised by
  **nothing** end to end. Every other test in the suite resolves the double deliberately, so the one
  test that boots the whole application is the only place the adapter can be crossed. Its apparent
  cheapness is also illusory: it needs the same id-threading logic as §4, only in Java.
- **Recorded cassettes** are not viable rather than inferior: nothing can be captured without
  credits, and the ids differ per run, so an exact replay could not match a request anyway.

Four documents said "scripted model" of the E2E. The three that meant the E2E are corrected; the one
describing **level 2**, where it is true, was left alone.

---

## 4. The provider cannot be a tape

This is the part the phase document's "scripted" framing actively hides, and the part a future
session is most likely to get wrong.

A booking conversation must name a `service_id`, an `employee_id` and a `starts_at` that **`make seed`
generates fresh on every run**. A reply fixed in advance names ids that do not exist, and
`create_appointment` refuses it. **A queue of canned replies cannot work here at all.**

The ids are reachable: `OpenAiChatModel.encode()` sends the whole message list on every call,
including the `role: tool` messages carrying earlier results. So the provider answers from what it
was sent — four states, chosen by **which tool results are already present** rather than by a turn
counter:

| Seen so far | Answer |
|---|---|
| nothing | call `get_services` |
| services | call `find_available_slots` for the service the customer named |
| slots | call `create_appointment` on the first slot, `starts_at` **verbatim** |
| a booking | text, quoting the Confirmation Code the tool returned |

This is the mirror of `backend/tools/receptionist-probe/probe.py`, which drives a real model against
stubbed tool results and already threads a `service_id` the same way. Only the faked side is new.

It is zero-dependency Node run by the stock `node:22-alpine` image from a read-only bind mount —
no build step and no lockfile to drift — and it lives in `infra/` because it is compose
infrastructure, beside the Caddyfile, rather than application code.

---

## 5. Proven twice, and shown able to fail

### 5.1 Against its own harness — and the harness can fail

`node infra/fake-provider/selftest.js`, **19 checks**, no dependencies and no runner. It asserts the
transcript-reading property directly, because that is what a plausible wrong implementation gets
wrong.

**Four defects were planted in `server.js` one at a time, and each was named by the check meant to
name it:**

| Planted | Named by |
|---|---|
| a turn counter instead of reading the transcript | *extra conversation before the tools does not shift the state* |
| `starts_at` normalised through `Date` instead of copied | *starts_at is copied verbatim, offset included* |
| always the first service, ignoring what was asked for | *find_available_slots gets the id of the service the customer named* |
| the search starting today | *the search starts at least two days out (T44)* |

### 5.2 Against the real application — and `source = AI` is the assertion that matters

A backend booted with `OPENAI_BASE_URL` pointed at the provider, against the **real seeded
`salon-aria`** and the real Postgres, booked a real Appointment in one turn. Read back **in SQL**:

```
confirmation_code | source | status    | starts_tbilisi      | from_now
QD6J1Z21          | AI     | CONFIRMED | 2026-09-14 10:00:00 | 1 day 18:56:29
```

**`source = AI` is the check that carries the weight**, not the confirmation card. A booking that
went through the Classic Flow would also produce a card and a code; only the source distinguishes the
Receptionist having done it. See T47 for why that distinction is not hypothetical.

`from_now` is **42.9 hours** — outside Salon Aria's 24-hour Cancellation Window, so the E2E's later
*follow the Manage Link and cancel* steps can run. That is T44 applied in advance rather than
rediscovered.

**This proof was local, not CI.** It ran against a throwaway backend on a spare port, deliberately,
so the running development topology was not taken over. CI proves the provider's *policy*; nothing in
CI yet crosses the layers, because the flow that would is unwritten.

### 5.3 The CI step was checked for having run

`make check-fake-provider` runs in the Compose smoke job. The job going green was **not** taken as
evidence the step executed: the step's own conclusion was read (`success`, not `skipped`) and the
runner's log was read for the 19 `ok` lines. A step that silently does not run is the failure mode
T41 already cost this project once.

---

## 6. Three findings, one of them mine

### 6.1 A counterfactual that coincides with the correct behaviour proves nothing — **T48**

The turn-counter defect was caught by **only one** of the two cases that exist to catch it. The
unpadded transcript — user, tool call, tool result — happens to put a counter and a transcript-reader
in exactly the same state, so that case passed **with the defect installed**. Only the variant with
ordinary conversation in front of the tool calls failed.

**T48 — a planted defect can survive the test written to catch it, when the case happens to coincide
with correct behaviour.** A suite with just the obvious case would have passed a provider that breaks
the moment a customer says hello first. Plant the defect and check *which* assertion fires, not
merely that one does.

### 6.2 An absent API key degrades rather than fails — **T47**

`OpenAiChatModel.complete()` throws **before it opens a socket** when `app.ai.api-key` is empty, and
the Receptionist's answer to a provider failure is to fall back to the Classic Flow — correctly, and
by design, since a clone with no key is this repository's ordinary state.

The consequence for an E2E is nasty: pointed at a fake provider with no key set, it books
**successfully**, renders a confirmation card, and asserts everything except the one thing that was
under test. `docker-compose.e2e.yml` sets a dummy key for exactly this reason, and §5.2's
`source = AI` is the assertion that would catch it if the key were ever lost again.

### 6.3 My own overclaim, caught by the measurement — retracted

The comment on `SEARCH_FROM_DAYS` first said two days *"absorbs any offset on earth, so the chosen
slot is more than 24 hours away whichever zone the tenant is in."* **That is false.** At `+14:00`, a
00:00 slot on `date_from` is only ten hours after a 23:59 UTC start.

It holds for the seeded tenants because they open at 08:00 or later at `+04:00` and `+01:00` — which
is an assumption about opening hours, not a property of the arithmetic. The comment now states the
real bound and names the case that breaks it. The measurement that exposed it is §5.2's 42.9 hours,
which is not 48 and prompted the question.

### 6.4 And one small one: `make help` hid a target

The help target's pattern was `^[a-zA-Z_-]+:`, which excludes digits — so `up-e2e` was **absent from
the list** rather than listed wrongly. A missing row looks exactly like a target that does not exist.
Fixed by adding `0-9` to the class, with the reason in a comment beside it.

---

## 7. Every open item

### 7.1 Issues

**[#17]** and **[#15]** — open, untouched, unchanged. No model was called this session; the credit
state was not re-checked and the [previous handoff][previous] holds the last reading.

### 7.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G21**.

**G19 — narrowed, not closed.** Its two halves were *"the deterministic model is unbuilt"* and *"the
phase document describes it as available"*. Both are now false: the model exists and the document is
corrected. **It stays open because nothing consumes it** — the Playwright flow is unwritten, so no
phase-11 box is ticked and the provider has never run inside the topology it was built for. Close it
when the flow runs, not on the strength of §5.

**New: G22 — the fake provider has never run inside the compose topology.** `make up-e2e` produces a
valid merged configuration, checked with `docker compose config`: the service resolves, `depends_on`
**merges** rather than replaces (backend waits on `postgres`, `mailpit` and `fake-provider`), and
`OPENAI_BASE_URL` overrides to the in-network host. But no container has been started from it. §5.2's
proof deliberately used a throwaway backend on a spare port rather than taking over the running
development topology, so what is unproven is specifically container-to-container: DNS to
`fake-provider`, the healthcheck's `wget --post-data` form, and the startup ordering. The Playwright
flow cannot avoid closing this, since it has to run there.

**G21 is unchanged and is worth pairing with the above.** The strict CSP has been shown *served* and
never exercised by a browser against a production build. The Playwright flow is the first thing that
will load a page under it — so the flow closes G19's remainder and G21 together.

### 7.3 Traps

Carried T1–T45. New:

**T46 — a specification sentence is not a decision, and the two are written identically.** §3.

**T47 — an absent API key degrades the Receptionist rather than failing it**, so an E2E can book
through the wrong door and look green. §6.2.

**T48 — a planted defect can survive the test written to catch it** when the case coincides with
correct behaviour. §6.1.

### 7.4 Security

**S1 — no model was called. Zero API requests, zero tokens, zero cost.** No credit check was made
either, so this session made **no outbound request to the provider at all**.

The dummy key in `docker-compose.e2e.yml` (`e2e-fake-provider-key-not-a-secret`) is committed on
purpose: it authenticates nothing, is never sent anywhere but a container on the compose network, and
its absence would cause the silent degrade in T47.

### 7.5 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; **nothing deletes an `ai_message`**; rate-limit buckets are in memory;
no frontend test runner; no `docs/deployment.md`; **no Playwright**.

---

## 8. Next steps, in order

1. **The Playwright flow.** Its blocker is gone — the model strategy is decided and the provider is
   built, seeded and proven. It closes G19's remainder and **G21** at the same time, and it is the
   only phase-11 item that can close either. Read §6.2 first.
2. **The isolation suite**, built as classify-or-fail. Independent of the above; the seed gives it
   two real tenants to probe across.
3. **The frontend test runner**, independent of both.
4. `docs/deployment.md` — HSTS, `CSP_SCRIPT_EXTRA`, and the unauthenticated API documentation are all
   waiting on it.
5. **The principal's**: whether `dev` merges now or accumulates; add credits and finish the [#17]
   arm; [#15]'s title.

---

## 9. Commands

```bash
# Where things actually stand.
git status -sb && git log --oneline -3 && git rev-list --left-right --count origin/main...origin/dev

# The fake provider's own self-test. No containers, no network, ~1 second.
make check-fake-provider

# The E2E topology: everything in containers, with the backend pointed at the fake provider.
# Seed it before booking by chat — it books real seeded data.
make up-e2e && make seed

# Book by chat against the running system, the way §5.2 did.
API=localhost:9080/api/public/businesses/salon-aria/chat
TOKEN=$(curl -s -X POST "$API/session" -H 'Content-Type: application/json' -d '{}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["sessionToken"])')
curl -s -X POST "$API" -H 'Content-Type: application/json' \
  -d "{\"sessionToken\":\"$TOKEN\",\"message\":\"Hi, I would like a Haircut please.\"}" | python3 -m json.tool

# The assertion that matters. A Classic Flow booking also produces a card and a code (T47).
docker compose exec -T postgres psql -U reception -d reception \
  -c "select confirmation_code, source, status from appointments order by created_at desc limit 1;"
```

**The whole backend suite is `--rerun` plus waiting for the `BUILD` line** (T41), and
**`pnpm build` still must not run while the dev server is up**.

---

## 10. Confidence

**High — the provider's policy.** 19 checks, run locally and in CI, and **shown able to fail**
against four planted defects with the firing assertion recorded for each rather than assumed.

**High — that it books through the Receptionist.** The row was read back in SQL and says
`source = AI`, which the confirmation card alone would not have established.

**Medium — that it works in the topology it was built for.** The merged configuration is verified;
no container has ever been started from it. Carried as **G22** rather than left in this section.

**High — §6.3's retraction.** It is arithmetic, and the case that breaks it is stated rather than
gestured at.

**None — [#17]'s verdict.** Unchanged, and no model was called.
