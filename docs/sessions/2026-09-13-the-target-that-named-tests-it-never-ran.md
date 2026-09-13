# Session handoff — 2026-09-13 — the target that named tests it never ran

> **The full-history secret scan was never blocked by anything.** It sat under *Next steps* for four
> sittings as work that needed a scanner nobody had. It needed `brew install gitleaks` and ninety
> seconds. **230 non-merge commits across every ref: clean.**
>
> **The three findings were the redaction tests' own fixtures**, and the JWT was decoded before it
> was waived rather than excused for living in a test file: `{"alg":"HS256"}` over
> `{"sub":"1234567890"}`, with a signature that decodes to **25 bytes where HS256 requires 32**. It
> cannot verify against any key.
>
> **The check produced two false greens while it was being written**, which is why its first
> assertion is a count and not a conclusion. `--report-path /dev/null` makes gitleaks exit fatally on
> an unknown report format; a count regex anchored with `^` cannot match a timestamped line. Both
> printed a clean-looking result over a scan of nothing. **T151 and T152.**
>
> **Then a clean clone was taken and the whole README demo script walked.** `git clone && make up &&
> make seed` works from a virgin clone and virgin volumes — **and the seed ran at 17:42 on a
> Sunday**, six and a half hours past the G40 crossover. Before last sitting's fix this exact command
> died mid-seed. That box could not have been honestly ticked a day ago.
>
> **Two findings came out of the walk, and both are a name asserting what its body does not do.**
> `make test` is documented as *"Run backend and frontend test suites"* and its frontend half is
> `install && lint && typecheck && build` — **no `pnpm test`, so all 82 frontend tests are skipped**
> for anyone who runs the documented command. And `.env.example` ships a **non-empty placeholder**
> `OPENAI_API_KEY`, so a clean clone lands in a state the README does not describe.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#36]: https://github.com/sanama-stack/reception-booking-system/issues/36
[#37]: https://github.com/sanama-stack/reception-booking-system/issues/37
[#38]: https://github.com/sanama-stack/reception-booking-system/pull/38

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`26e09eb`** — [#38] squashed, merged 14:08Z. Was `55e5b02` |
| `origin/dev` | **`02463a0`** — `main` merged back. **0 behind**, and `git diff origin/main origin/dev` is empty |
| CI | **Green** on both runs of `6321485`, all five jobs — but read §11 before believing the Backend one |
| Backend | **1056 tests, 120 classes, 0 failed** — run from a clean clone, not from this working tree |
| Frontend | **82 tests, 23 files, all passed** — run **separately**, because `make test` does not run them (§4) |
| Migrations | `V10`, unchanged. No new ADR |
| Issues | [#17] and [#15] open, untouched. **[#36] and [#37] filed this sitting** (§4, §5). Still no model called — still none could be |
| Phase 11 | **62 ticked, 10 open.** No box moved; §6 says which four now have evidence behind them |

---

## 2. The secret scan, and what the scan does not reach

`gitleaks` 8.30.1, `--redact`, every ref. **230 non-merge commits, 6.83 MB, clean.**

The interesting part is the 30 commits it did *not* read. **gitleaks skips merge commits**, which is
sound only because a merge introduces no content its parents lack — untrue of an *evil merge*, whose
tree differs from what merging its parents produces. All 30 were checked with `git diff-tree --cc`:
**none is evil.** That is why `check-secrets` uses `git rev-list --all --no-merges --count` as its
denominator, and it is the line to revisit if that ever stops holding.

**19 unreachable dangling commits** were scanned too — 16 by diff, and the 3 merge-shaped ones
(two abandoned stashes and a merge) by unpacking their full trees, since a stash's content lives in
its tree and not in either parent's diff. Only the already-known fixture came back.

### The waivers

In `.gitleaks.toml`, each pairing a **path** with the **literal it permits** under
`condition = "AND"`. Deliberately not a `.gitleaksignore`: its fingerprints are
`commit:file:rule:line`, so one line inserted above a fixture re-arms the alarm on an innocent commit
while the real pattern goes unpinned — and a scanner that cries wolf is one people learn to skip.

### Plants

**Five red, and a sixth that passed and should not have.**

| # | Plant | Result |
|---|---|---|
| 1 | A real secret inside an allowlisted file | **2 findings** — the file is not blanket-waived |
| 2 | A waived literal in a file that does not own it | **1 finding** — the path half of the condition holds |
| 3 | The allowlists removed | **exactly the 3 known findings** return |
| 4 | A scan covering part of history | control refuses: *4 scanned, 230 expected* |
| 5 | A non-example secret actually committed | detected, told to rotate |
| **6** | **AWS's `…EXAMPLEKEY` committed** | **green — and wrong** |

**Plant 6 is the one worth keeping.** `wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY` is in gitleaks' own
allowlist, so committing it proved the scanner silent rather than the repository clean. A plant built
from a documentation example asserts nothing about the control. **T153.**

---

## 3. The clean-clone run

Fresh clone of `dev` — 739 files, no `.env`, no build artifacts — against **removed volumes**, so
Postgres came up empty rather than attaching to the existing database. The dev stack's volumes were
tarred and verified first (1859 entries, `PG_VERSION=16`, `base`/`global`/`pg_wal` present) and
restored afterwards; `Phase 06 Scratch`, `GD auto repair` and `GD STUDIO 010` are back, and the
clean-clone customers are **absent**, which is what proves the restore is the original and not a
relabelled copy.

`make up` created `.env` from `.env.example` unprompted, Flyway applied **10 migrations** to an empty
schema, and the seed loaded both tenants.

**The seed ran at 17:42 Sunday Tbilisi.** The G40 crossover is Sunday 11:00. This is the first time
the fixed `BlueprintCheck` has been exercised by the real `make seed` on the far side of it, rather
than by a test.

### The demo script, end to end

| Step | Result |
|---|---|
| 2 · home counts and timezone | 0 / 7 / 18, *"Sun 13 Sept in Asia/Tbilisi"* |
| 3 · calendar week | AI badges present; **Mariam Beridze away Mon 14 – Fri 18**, hatched, working again Sat 19 |
| 4 · analytics | 890.00 GEL completed-only; changing the range moved every figure (17→7, 890→0.00) while the live counts correctly held |
| 5 · stranger books | `201`, code `86EV43S9`. Both Sundays showed *no times*, Saturday 12 against a weekday 31 |
| 6 · Mailpit | confirmation inside the minute, carrying the code and a Manage Link |
| 7 · Manage Link | moved to 17 Sept, **code unchanged**, *"has moved"* email followed |
| 7b · tomorrow refused | *"Too late to change this online"* — both actions gone, phone and email offered instead |
| 8 · dashboard | the customer's reschedule already there; completing a 150.00 GEL appointment moved revenue **890.00 → 1040.00** |
| 9 · receptionist | **cannot run — §5** |
| 10 · other tenant | Dato's Auto, Europe/Berlin, EUR, and **no trace of the salon** |

The owner could still act on *yesterday's* appointment while the customer could not act on
tomorrow's, which is the Cancellation Window binding the customer rather than the business, observed
rather than read.

---

## 4. `make test` names a suite it does not run

```make
test: ## Run backend and frontend test suites
	cd backend && ./gradlew build
	cd frontend && pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm build
```

`pnpm test` exists — `vitest run`, 23 files, 82 tests — and **CI runs it in its own step**. The
target a stranger is told to run does not. The 82 in §1 exist only because they were run by hand
after the discrepancy was noticed.

**This is T144 with the subject changed.** A step named *"both confirmations arrive"* over a body
checking one, and a target named *"backend and frontend test suites"* over a body running one, are
the same defect: **the name is an assertion, and nothing executes it.** It is also G28's shape — two
things left to agree by hand, CI's frontend job and the Makefile, with nothing reconciling them.

Filed as [#36]. **Not fixed here.** Adding `pnpm test` to the target is one line, but it is a change to what the
documented build does, and the last sitting's rule still applies: a green obtained by changing the
gate is worth less than a red that was understood first. Filed rather than patched.

---

## 5. The third state the README does not describe

Demo step 9 says, in full: *"With an `OPENAI_API_KEY` in `.env` … Without a key the panel says so and
the form is still there."* Two states.

`.env.example` ships **`OPENAI_API_KEY=sk-local…`** — 32 characters, a placeholder, and **non-empty**.
So a clean clone is in neither documented state: the application considers a key configured, offers
the Receptionist, accepts the message, and answers **`503`** with *"I can't reach the booking
assistant just now. You can book directly on this page instead."*

**The degradation is correct and the copy is good.** The defect is that the shipped default and the
documented default disagree, so the one path a stranger actually takes is the one path the README
does not mention. Either `.env.example` ships the key empty — restoring *"the panel says so"* — or the
README grows the third state. Filed as [#37]. **That is a decision, not a fix**, and it is the principal's: it is the
same shape as the level-3 entry in *Gates that cannot be run*, where a thing that cannot work is
recorded rather than quietly tolerated.

Worth stating narrowly: **this is independent of the credits.** A real key would also have to be paid
for, but a stranger with an unpaid account and a stranger with the shipped placeholder see the same
screen, and only one of those is written down.

---

## 6. Phase 11 — four boxes with evidence, none ticked

Ticking remains the principal's. What changed is what could be ticked against:

- **"Full-history secret scan"** — ran, clean, and repeatable as `make check-secrets`.
- **"No secret is in the repository or its history"** — the same run, with the merge and dangling
  gaps closed explicitly rather than assumed.
- **"Full suite green from a clean clone"** — 1056 backend and 82 frontend, from a fresh clone. Read
  §4 before ticking: the documented command does not produce the second number.
- **"`git clone && make up && make seed` produces a fully working, populated system"** — done on
  virgin volumes, on a Sunday afternoon.

**"The demo script in the README runs end to end without deviation" is the one to leave alone.**
Nine of its ten steps ran exactly as written; step 9 cannot, for §5's reason.

---

## 7. Traps

**T150** — a *target's* name is an assertion that nothing runs. `make test` promised two suites and
ran one for ten phases, while CI ran both and agreed with nobody.

**T151** — a report path the tool cannot write turns a scan into a fatal exit whose summary still
reads like a result. `--report-path /dev/null` printed *"Unknown report format"* and `0 commits
scanned`, and a loop around it reported clean.

**T152** — `^` cannot anchor a match in a log line that begins with a timestamp. The count regex
reported 0 of 19 over a scan that had worked, and the conclusion drawn from it was the opposite of
the truth.

**T153** — a plant built from a documentation example proves nothing. Scanners allowlist the
canonical `EXAMPLE` credentials by design, so committing one demonstrates the scanner's silence, not
the repository's cleanliness.

**T154** — a CI green does not mean the suite ran. Gradle restores `:test` `FROM-CACHE` when its
inputs are unchanged, so a documentation commit inherits a pass it did not earn — legitimately, and
without saying so anywhere but the log.

---

## 8. Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**, **G39**, **G40**.

**G41 is new and open** ([#36]). *Nothing reconciles the Makefile's `test` target against CI's jobs.* §4's
defect is one instance; the general form is that the documented local build and the pipeline can
diverge silently, exactly as §5 of the limits table did before `RateLimitTableTest`.

**G42 is new and open** ([#37]). *A clean clone lands in a Receptionist state the README does not describe*,
because `.env.example` ships a non-empty placeholder key. Needs a ruling, not a patch — §5.

---

## 9. Commands

```bash
make check-secrets          # every commit on every ref; needs gitleaks
make up && make seed        # verified from a clean clone on virgin volumes

cd frontend && pnpm test    # the 82 `make test` does not run
```

`JAVA_HOME` is still not optional on this machine: the default `java` is 25, the build needs 21, and
Gradle reports the mismatch as a bare version number in a build that fails in five seconds.

---

## 10. Confidence

**Certain on the scan's coverage.** The merge exclusion is justified by checking all 30, the dangling
commits were scanned by tree where the diff path could not reach them, and the count control is
asserted rather than assumed.

**Certain on §4.** The target's text and `package.json`'s scripts are both quoted above, and CI's own
step runs the command the target omits.

**High on the clean-clone run.** Everything in §3 was observed in a browser against a virgin database,
and the revenue assertion moved by exactly the price of the appointment completed.

**Explicitly unproven: G40's boundary**, unchanged from the last handoff. And **nothing here says
anything about Receptionist behaviour** — §5 is about a configuration default, not about the model,
and no model answered this sitting either.

---

## 11. The push, and a green that did not run

[#38] went `dev` → `main`, squashed as **`26e09eb`**, ten checks green. **GitHub ran the workflow
twice on the same SHA** — once for the `push` and once for the `pull_request` — which is why a
watcher that had correctly seen five-of-five green sat beside a PR reporting *8 passing, 2 pending*.
Neither reading was wrong; they were counting different things. **Wait for the checks the PR lists,
not for the run you happened to start watching.**

**The Backend job passed in 38 seconds, against 5m41s on the last sitting.** The log says why:

```text
> Task :test FROM-CACHE
8 actionable tasks: 4 executed, 4 from cache
```

**CI did not execute the backend suite on this commit.** Gradle restored the task, correctly — this
sitting changed the Makefile, `.gitleaks.toml` and documentation, so the test task's inputs hashed
identically to a run that had already passed. Nothing is wrong with the cache and nothing is wrong
with the green.

What is worth writing down is what the green *means*. **The 1056 in §1 comes from the clean-clone
run, which executed them; it does not come from CI.** A reader who sees a green Backend job on a
docs-only commit and concludes the suite ran has concluded something the log denies — and on a
commit that *does* touch backend source the cache key changes and the tests run, so the distinction
is invisible exactly when it does not matter and invisible again when it does.

### The branch arithmetic after a squash

`dev` reports **52 commits ahead** of `main` over **identical trees**, because the two commits it
carried are not ancestors of the squash. The merge-back fixes the direction that matters — `dev` is
**0 behind** — and leaves the ahead count, which is inherent to the workflow and will grow by one
per squash forever. **`main-only=0` is the number to read; the ahead count says nothing.** The same
state was recorded after [#35], one sitting ago, which is how it is known to be the shape rather
than a problem.
