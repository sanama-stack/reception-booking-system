# Session handoff — 2026-09-13 — neither fix was the defect

> **Two issues were worked and both had been filed against the wrong thing.** Not carelessly — each
> description was an accurate account of a symptom. But in both cases the remedy the issue named was
> either too small to be the fix or did nothing at all, and the difference was only visible by
> running something.
>
> **[#36] asked for one line.** `make test` was documented as "backend and frontend test suites" and
> its frontend half never ran `pnpm test`. Adding it is one line. Writing the reconciliation the
> issue said to *consider* found **two more divergences in the same breath**: `make test` also
> skipped `pnpm format:check`, and `make e2e` never ran `pnpm typecheck`. **Three, not one.** G41
> was not an abstraction over a single bug.
>
> **[#37] offered two options and the first was a no-op.** It read that `.env.example`'s non-empty
> placeholder made the application "consider a key configured". It does not:
> `AiProperties.isConfigured()` rejects the placeholder and a blank key **identically**, proven by
> running both through the real adapter. Emptying that line changes nothing a visitor sees.
>
> **The real defect was a control three documents described and no file implemented** — G26's shape,
> for the third time. `requireReceptionistAvailable()`'s own Javadoc named the no-key case and the
> branch below it checked only the owner's switch. `.env.example` said the Receptionist degraded
> while the key was a placeholder; it degraded only *after* a message was sent. The README said the
> panel "says so"; nothing could, because the public profile carried only the toggle. **And a fourth
> the issue never reached**: `business-panel.tsx` rendered the panel with no `else`, so an
> unavailable Receptionist was an absence rather than a statement.
>
> **The principal ruled: implement the control, and have the panel say so.** Done, and verified in a
> browser in **both** directions against the real deployment topology. **README step 9 and
> `.env.example`'s comment are now true as written, and neither was edited.**
>
> **Committed, not pushed.** Two commits plus this file, leaving `dev` **4 ahead of `origin/dev`**.
> 1059 backend tests, 0 failed. 84 frontend. Phase 11 still at **62 of 72** — no box moved, and §6
> says which one stopped being blocked by this and is still blocked by something else.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#36]: https://github.com/sanama-stack/reception-booking-system/issues/36
[#37]: https://github.com/sanama-stack/reception-booking-system/issues/37
[prev]: ./2026-09-13-the-target-that-named-tests-it-never-ran.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`26e09eb`** — unchanged. Nothing was pushed this sitting |
| `origin/dev` | **`02463a0`** — unchanged. Local `dev` is **4 ahead** once this file lands |
| CI | **Has not seen any of it.** The last green was `6321485`, and [prev] §11 applies to it |
| Backend | **1059 tests, 0 failed** — 1056 plus three, and the run **executed** rather than restoring `FROM-CACHE` |
| Frontend | **84 tests, 24 files** — 82 plus two. Now run by `make test`, which is the point of half this sitting |
| Migrations | `V10`, unchanged. No new ADR |
| Issues | **[#36] and [#37] are fixed and still OPEN** — nothing pushed, no comment posted. [#15] and [#17] open, untouched. Still no model called |
| Phase 11 | **62 ticked, 10 open.** No box moved |

---

## 2. [#36] — the reconciliation found two more the moment it existed

The target, as it stood for ten phases:

```make
test: ## Run backend and frontend test suites
	cd backend && ./gradlew build
	cd frontend && pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm build
```

`pnpm test` is `vitest run`, 23 files, 82 tests, and CI ran it in its own step. The target a stranger
is told to run did not. That is the issue, and it is one line.

**The instruction worth following was the one the issue hedged on** — *"worth considering alongside
it: a check that reconciles the Makefile's targets against the commands CI runs"*. It was written as
an optional extra. It found two defects the issue had not seen:

| Where | Missing |
|---|---|
| `make test` → `frontend` | `pnpm test` — the filed one |
| `make test` → `frontend` | **`pnpm format:check`** |
| `make e2e` → `e2e` | **`pnpm typecheck`** |

So G41 was never the general form of one bug. The Makefile and CI had drifted in **three** places,
and two of them nobody had noticed.

### What the check derives, and the one thing it cannot

`tools/pipeline-parity/check.py`, `make check-pipeline`, and a CI job. Both sides are **parsed** —
the workflow's steps and this Makefile's recipes — and every script name is resolved against that
project's `package.json`.

**What is compared is the script name, not the command line.** `--no-daemon` is a runner's concern
and `--with-deps` is deliberately absent from `make e2e` (that target's comment says why). Flags are
where CI and a laptop are *supposed* to differ; which suites run is where they are not.

**The irreducible hand-written part is a three-row table** pairing a directory with the target that
stands in for CI's work there. Something has to state that `make test` is the local stand-in for the
Backend and Frontend jobs. **Its own hole is closed**: a CI step working in a fourth directory fails
`directory coverage` rather than going quietly uncovered.

`test` depends on `check-pipeline`, so the documented local build checks itself against the pipeline
before it runs anything.

### Shown red six ways

Each reverted byte-identically, each red for its own reason:

| | Plant | Caught by |
|---|---|---|
| P1 | `pnpm test` removed from the target | suite parity |
| P2 | a script added that CI does not run | suite parity, other direction |
| P3 | frontend `test` renamed in `package.json` | script names |
| P4 | `pnpm typecheck` removed from CI's E2E job | suite parity, other direction |
| P5 | a CI job working in an unpaired directory | directory coverage |
| P6 | `jobs:` renamed so the parser reads none | **SKIPPED**, not a green |

**P3 and P6 are the ones that matter**, and P3 caught a false green in the check's own first draft.
See T155 and T156.

---

## 3. [#37] — the option that changed nothing

The issue's first option was *"ship `OPENAI_API_KEY=` empty in `.env.example`"*, on the reading that
the non-empty placeholder made the application treat a key as configured. Run against the real
adapter:

```text
PROBE placeholder -> No OpenAI API key is configured
PROBE empty       -> No OpenAI API key is configured
PROBE configured(placeholder) = false
PROBE configured(empty)       = false
```

`isConfigured()` is `apiKey != null && !isBlank() && !startsWith("sk-local-dev-only")`. Both values
fail it, both throw the same `ChatModelException`, and both produce the same screen. **The option
would have changed nothing.** The probe was thrown away; this transcript is the whole of it.

### The control three documents described

| Where | Said | Did |
|---|---|---|
| `ConversationService.requireReceptionistAvailable()` Javadoc | "the owner switched it off, **or nobody configured a key**" | checked only the switch |
| `.env.example`, AI block | "degrades to the Classic Flow while this is the placeholder" | degraded only after a message was sent |
| `README.md` step 9 | "without a key the panel says so" | nothing could say so — see below |

The public profile carried `aiEnabled`, **the owner's toggle and nothing else**, so the server never
told the page whether a model could run. A fresh clone therefore rendered a live panel, accepted the
customer's message, and answered the **outage** copy — *"I can't reach the booking assistant just
now"* — which tells a customer that a configured assistant is temporarily down when in fact there is
none.

**And the fourth, which the issue never reached.** `business-panel.tsx` was
`{business.aiEnabled && <ReceptionistPanel …/>}` — **no else**. When the Receptionist was
unavailable the panel was simply absent and nothing said why. "The panel says so" described a state
that had never existed, for the toggle case either.

### The ruling, and what was built

**The principal ruled: implement the control, and have the panel say so.** Recorded here because
that is the decision [#37]'s definition of done asks for; the reasoning is the three rows above.
The two options the issue offered were a no-op and a prose rewrite, and neither was put to the
principal as written.

**One rule, two surfaces.** `ConversationService.receptionistAvailable(business)` is the single
definition and is **public for that reason** — `GET /public/businesses/{slug}` and `POST /chat` ask
the same method, so the answer a customer is shown and the answer their message gets cannot drift
apart. Two places deciding one thing by hand is G28 and G41, and it was not worth a third.

**Availability is on the port.** `ChatModel.isAvailable()` rather than a read of `AiProperties`,
because "configured" meaning an OpenAI key is the adapter's business — the scripted double needs no
key and is perfectly available, and the E2E's fake provider is the real adapter pointed elsewhere
(ADR-0011). **Gating on the properties directly would have needed a key in `application-test.yml`
and would have broken `LiveReceptionistTest`'s `assumeTrue` guard**; see T157.

It is a question about **configuration, not health**. A configured provider that is momentarily
unreachable answers `true` and fails in `complete()`, because *"isn't available"* and *"can't reach
just now"* are different things to tell a customer. `PublicChatTest` asserts the two paths by their
**copy**, not by their code — both answer `AI_UNAVAILABLE` and the codes cannot tell them apart.

**`aiEnabled` → `receptionistAvailable` on the public payload.** It now means the switch AND a
model, so an owner with the toggle on would have read their own setting back as `false`. A name
meaning something other than the field it was copied from is this repository's recurring defect
(T144, T150). The dashboard's `aiEnabled` is untouched and still means the switch.

### Shown red seven ways

Backend, against `PublicChatTest`: the door forgetting the model again (2 red), the page reporting
the switch instead of the rule (2 red), both unavailable paths given the same copy (1 red), and the
rule as an `||` (2 red). Frontend, against `business-panel.test.tsx`: the `else` dropped again,
the note rendering beside the panel, and the note losing its copy (1 red each). Each reverted
byte-identically.

---

## 4. The browser run, both directions

Against `make up-all` — the deployment topology, not the IDE — with `OPENAI_API_KEY` overridden in
the shell to the shipped placeholder so `.env` was never touched.

**`salon-aria`'s `ai_enabled` is `t` in the database**, which is what makes the run mean something:
the owner's switch was ON and the page still said there was no Receptionist.

| Key | Payload | Page |
|---|---|---|
| the placeholder | `receptionistAvailable: false` | *"The booking assistant isn't available right now. You can book on this page as usual"* — and the form untouched above it |
| a configured value | `receptionistAvailable: true` | *"Ask the receptionist"*, the live panel, the Send button |

`aiEnabled` is **absent** from the public payload in both.

**The counterfactual is the half that matters.** A run showing only the note proves a panel is
missing, which is also what a broken render looks like. Switching the key back and watching the
panel return is what separates them.

**No screenshot**: the Browser pane was not displayed, so nothing composited. The DOM text above is
the evidence and is the better evidence anyway.

The stack was returned to the three infrastructure containers it was found in. **The database volume
was not touched** — `down -v` was never run, and the seeded tenants are intact.

---

## 5. Why a browser run at all, when 1143 tests pass

Because of one line in `frontend/src/lib/public/api.ts`:

```ts
api.get<PublicBusiness>(publicBusinessPath(slug))
```

A generic cast with **no runtime validation**. Rename a field on the wire and forget one reader, and
`tsc` is silent, the frontend unit tests are silent because they pass fixtures, and the page renders
`undefined` as falsy. The backend half of the rename is nailed down — `PublicFieldAllowListTest`
pins the exact field set of the public payload and `PublicChatTest` reads `$.receptionistAvailable`
off a real HTTP response — but nothing executed proves the *page* reads what the *server* sends.

That seam is **G44**, below. The browser run was how it was covered this once.

---

## 6. Phase 11 — what stopped being blocked, and what still is

No box moved. What changed is the reason one of them cannot be ticked:

**"The demo script in the README runs end to end without deviation"** was blocked by G42 — step 9's
"without a key the panel says so" described a state no file produced. **That half is now true and
browser-verified.** The step's *first* half still cannot run: it needs a working `OPENAI_API_KEY`,
and the account has no credits. So the box stays open **for the reason already carried under
`07-mvp-scope.md`'s *Gates that cannot be run*, and no longer for a defect in this repository.**

That is a smaller claim than it sounds and is worth keeping small: the box is still not tickable.

---

## 7. Traps

**T155** — a derivation check that resolves names against a shared source can be **broken by a
rename in that source**, and the failure mode is a green. Renaming `test` in `package.json` removes
it from CI's set *and* the Makefile's set at once, leaving two empty sets that agree about nothing.
The fix is to assert that every name either side invokes **resolves**, not merely that the two sets
match. Caught in the check's own first draft by plant P3.

**T156** — a hand-written parser that loses its subject must say so. Renaming `jobs:` made the
workflow scan read zero steps, and without the zero-population guard every check would have passed
over nothing. This is the third time this repository has been bitten by a clean-looking result over
an empty scan; T151 and T152 in [prev] are the other two.

**T157** — a predicate used as a guard in two unrelated places **cannot be changed for one of them**.
`AiProperties.isConfigured()` answered both "can the Receptionist run?" and "should the live
`@Tag("llm")` tests run?". Gating the conversation on it would have forced a key into
`application-test.yml`, which would have flipped `LiveReceptionistTest`'s `assumeTrue` from *skip* to
*run against a fake key*. Putting availability on the `ChatModel` port left the second question
alone, and needed no test-profile change at all.

**T158** — **a plant must survive the gates standing in front of it.** The first attempt at proving
`make test` reaches the frontend suite planted a failing test that was not Prettier-clean, so the
run died at `format:check` — one step earlier. The target failed, which is what the plant asserted,
and it proved nothing about the suite. A plant that fails early looks exactly like a plant that
worked.

**T159** — `{condition && <Component/>}` with no `else` makes an unavailable state **indistinguishable
from a broken render**, and a test asserting only that the component is gone passes for both. The
test added here asserts the absence *and* that something says why.

**T160** — a filed issue's diagnosis is a hypothesis. [#37] was well written, specific, and
reproduced end to end, and its account of the cause was still wrong — which made one of the two
options it offered a no-op. Verify the cause before choosing between remedies written against it.

---

## 8. Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**, **G39**, **G40**.

**G41 is closed** ([#36]). The Makefile and CI are reconciled by `make check-pipeline`, which runs in
`make test` and as its own CI job, and fails in both directions.

**G42 is closed** ([#37]). The booking page states whether there is a Receptionist, from the same
rule `POST /chat` enforces.

**G43 is new and open.** *A red `Pipeline parity` run does not block a merge.* The job exists and
runs, and branch protection is configured outside the workflow file, so nothing in the repository
can assert it either way. Adding it to the required set is **the principal's call** — the job's own
comment says this rather than claiming otherwise, which is the lesson G26 was filed for.

**G44 is new and open.** *Nothing checks that the frontend's types match the wire.*
`api.get<PublicBusiness>` is a cast with no runtime validation, so a renamed field is invisible to
`tsc`, to the backend suite and to frontend unit tests that pass fixtures. It was covered by hand
this sitting (§4, §5). The E2E flow would catch it for the paths it walks; the *unavailable* branch
is not one of them, because the E2E topology configures a fake provider key.

---

## 9. Commands

```bash
make check-pipeline         # the Makefile and CI run the same suites
make test                   # now genuinely both suites — 1059 backend, 84 frontend

cd frontend && pnpm test    # 84, and `make test` runs these now
```

To stand up the fresh-clone state without touching `.env`:

```bash
OPENAI_API_KEY=sk-local-dev-only-not-a-real-key make up-all
```

`JAVA_HOME` is still not optional on this machine: the default `java` is 25, the build needs 21.

---

## 10. Confidence

**Certain that [#37]'s first option was a no-op.** Both key values were run through the real
`OpenAiChatModel` and produced the same message; the transcript is in §3.

**Certain on the three [#36] divergences.** Both sides are quoted in the commit, and the check that
found them is red against each and green after.

**High on the browser run.** Both directions, the real deployment topology, and the owner's switch
confirmed ON in the database rather than assumed.

**Explicitly unproven: CI has seen none of this.** Nothing was pushed. The 1059 comes from a local
run that executed, not from a runner — and the parity job has never run on GitHub at all, so its
YAML is asserted only by having been parsed by the checker it configures.

**Explicitly unproven: G40's boundary**, unchanged from [prev].

**And nothing here says anything about Receptionist behaviour.** No model answered this sitting
either. The configured key used in §4 was deliberately not a working one — it proves the
*availability* signal and nothing downstream of it.

---

## 11. The push that did not happen

Four commits sit on local `dev` — these three and the one carrying this file — and `origin` has
none of them:

```text
a6d59be  Say there is no Receptionist before a customer finds out by asking (#37)
4d2c530  Run the suites the Makefile names, and derive the agreement from CI (#36)
c4445ef  Bring the handoff up to date, because it now is merged
```

**[#36] and [#37] are still open**, deliberately: an issue closed by a commit nobody has pushed is a
claim the tracker cannot back. Push first, let CI go green, then comment and close.

**Watch the Frontend job on the first push.** It gains nothing new, but `make test` now runs
`format:check` and `pnpm test` locally, so the two are finally asserted to be the same set — and the
first CI run is the first time that assertion is checked against the runner rather than against a
laptop. **Watch `Pipeline parity` too**: it has never executed on GitHub.
