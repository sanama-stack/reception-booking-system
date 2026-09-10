# Session handoff — 2026-09-10 — The flat allow-list, the 40 px default, and an empty tracker

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand; §2 is the two decisions the principal made and the options they were made from; §3 and §4
> are what implementing them found that the issues had not; §6 is what a fresh session must not
> redo.
>
> **This session built no screen.** It was asked to clear the tracker before the phase 09 frontend
> half. Both remaining issues were `ready-for-human` — decisions, not patches — so both were put to
> the principal first. The pull request four handoffs had been asking for was opened and **merged**;
> §5.1 is what merging it turned up, which was not nothing.
>
> **The tracker is empty — zero open issues — for the first time in the project's history.**

---

## 1. Where the project stands

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| `origin/main` | **`3efcfd2`** — phase 09's backend half and everything before it |
| `dev` | **`3efcfd2`** — fast-forwarded onto `main` after the merge, so the two are identical (§5.2) |
| Pull request | **[#12](https://github.com/sanama-stack/reception-booking-system/pull/12) is MERGED** — `dev` → `main`, seventeen commits, merge commit `3efcfd2`. All six checks green |
| Backend | **798 tests, 0 failures, 0 errors, 0 skipped** — unchanged in count, because this session strengthened an existing test rather than adding one |
| Frontend gates | lint, typecheck, `format:check` green. **`pnpm build` not run** — a dev server is up |
| Migrations | **V7**, unchanged. No migration this session |
| Issues open | **Zero.** #5, #8, #9, #10 and #11 closed automatically on merge; #7 was closed by hand and §5.1 says why |
| Phase 09 | Backend complete. Frontend: **types and client only**, no component. Unchanged |

### The commits, in order

```
12d93b7  Make 44 px the default rather than something four files remember      #11
46c8d5c  Let the allow-list say where a key is allowed, not merely that it is  #10
60168e8  Record the session that decided twice and opened the pull request
637d41e  Merge branch 'main' into dev                                          §5.1
3efcfd2  Merge pull request #12 from sanama-stack/dev                          on main
```

### The one thing that is genuinely new to know

**The pull request merged, and that is a change of policy rather than of code.** Three handoffs
held it back on the same reasoning: phase 09 is half-built, and `main` is meant to be a commit a
stranger could clone and run. The principal asked for it directly. It is not actually a problem —
`main` gets a complete Classic Flow and a Receptionist reachable by API, with no chat panel — and
the PR body says so in its second paragraph rather than leaving a reader to discover it.

**The consequence for the next session is that `main` is no longer a phase behind.** Every previous
handoff opened with a `dev` that was some number of commits ahead of an older `main`; this one does
not, and `dev` and `main` are the same commit.

---

## 2. The two decisions, and what they were made from

Both issues were labelled `ready-for-human` by the sessions that filed them, and both labels were
right. Neither was implemented before the principal chose.

### 2.1 #11 — where the 44 px guideline should live

| Option | Chosen |
|---|---|
| Raise the shared default to `h-11`; everything inherits, the overrides are deleted | **yes** |
| Add a `touch` size to the variant set, used on public surfaces | no |
| Scope by surface — a wrapper or CSS variable public layouts set | no |

The recommendation, and the reason it was accepted: the issue's own framing is that **the default is
backwards**. The mouse-operated dashboard was setting the size for the thumb-operated public
screens. Option 2 keeps the dashboard's density but leaves the guideline as something to remember,
just better named; option 3 is the only one where a new public screen is safe without opting in, and
is also the most machinery. The cost accepted is 4 px of vertical rhythm on every dashboard form.

### 2.2 #10 — how the public allow-list should stop being flat

| Option | Chosen |
|---|---|
| Paths instead of bare key names | **yes** |
| A map from record to its permitted keys (the issue's option 1) | no |
| Plant contact details on every fixture entity (the issue's option 2) | no |
| Both of the issue's options together | no |

**The chosen option was not in the issue.** It was surfaced while reading `collectKeys`, which
already walks the tree and so could accumulate paths for nothing. It sidesteps the blocker that had
deferred this four times — the issue's option 1 requires the test to know which Java record backs
which endpoint, and a path does not.

Worth recording because a future reader will meet the issue text before this file: **the issue's own
assessment of its options was not the best available**, and the cheap-versus-structural framing it
offered was a false choice.

---

## 3. #11 — what the issue named, and what it had missed

The issue listed four files carrying `min-h-11` and named `Input` as having no override anywhere.
All correct. Three things it did not name, found by grepping rather than trusting the list:

| | |
|---|---|
| `components/ui/select.tsx` | 40 px, same as `Input`. Dashboard-only, so not a thumb defect — but it would have rendered 40 px beside 44 px buttons on the same Settings screen |
| `components/week-editor.tsx` | A hand-rolled `<input type="time">` repeating `input.tsx`'s class string verbatim. That duplication is *why* it drifts |
| `app/landing-cta.tsx` | A skeleton at `h-10` whose own doc comment promises "a placeholder of the same height". It would have jumped 4 px when the session check resolved |

All three moved with the rest.

### 3.1 Four `min-h-11` sites stay, and are not oversights

- **`fortnight-picker.tsx:113,132`** — `size="sm"` buttons, deliberately small controls carrying a
  full-size target. `h-8` sets `height` and `min-h-11` sets `min-height`, so the larger minimum wins
  without fighting the size class. Raising `md` does not touch `sm`, so these are still doing work.
  Their comment is still accurate and was left alone.
- **`classic-flow.tsx:542` and `fortnight-picker.tsx:249`** — raw `<button>` elements that never
  touch the shared component.

### 3.2 `cn` is a plain join, not tailwind-merge

```ts
/** Joins class names, dropping falsy entries. Deliberately not a dependency. */
```

So conflicting classes both land in the attribute and **stylesheet order decides**, not attribute
order. This makes a `className` height override a coin-flip rather than a win, and it is why the
whole codebase overrode `min-height` rather than `height` in the first place. Checked before
committing: nothing anywhere passes a competing `h-*` to `Button` or `Input`, so the base height is
uncontested.

---

## 4. #10 — the hole that pure paths still had

Paths alone were not sufficient, and this was found while writing them rather than afterwards.

**Five public responses are root objects** — `businessProfile`, `booked`, `lookup`, `manage`,
`chatReply` — so an unqualified top-level path `phone` would have been admitted on *all* of them.
`phone` is on the list because `BusinessProfile` publishes the business's own number; under pure
paths, a `ManagedAppointment` that grew a top-level `phone` would have passed.

So each path is qualified by the response that produced it: `businessProfile.phone` is listed,
`lookup.phone` is not. This needs **no** map from endpoint to Java record — the call site already
knows what it called, which is the whole reason it stays inside the chosen option rather than
drifting into the one the principal declined.

Other decisions inside the implementation:

- **Array indices erase to `[]`**, so a second element cannot introduce a field the first did not.
- **Container keys are listed too** (`businessProfile.hours`), so a new array or object is a
  decision as much as a new leaf.
- **`NEVER` stays a set of key names**, now matched against a path's last segment. It is about keys
  that are acceptable *nowhere*, and `businessId` three levels down is the same leak as at the top.
- **The three shared shapes are helpers, not copies.** `bookedAppointment`, `managedAppointment` and
  `availability` are written once and applied at each source. They are genuinely one record in each
  place, and `PublicChatTest.one_card_shape_serves_both_doors` is what keeps that true — copying
  them would have been asserting a resemblance instead of a fact.

### 4.1 The counterfactual, which is the part worth trusting

`email` was planted on `ServiceSummary`: a key legitimate on `BusinessProfile`, appearing on a
different record, carrying a value the fixture does not plant — so the value half could not catch it
either. Then both versions of the test were run against the same planted leak.

| | result |
|---|---|
| the old flat-set test | **all five PASSED** — the leak was invisible |
| the new path-aware test | FAILED: `but found these extra elements: ["services[].email"]` |

The old test was restored from `git` for that run rather than reconstructed. Both files were put
back afterwards, and `PublicResponses.java` is byte-identical to `HEAD`.

---

## 5. What is verified, and how

- **798 tests, 0 failures, 0 errors, 0 skipped.** Counted by parsing the 91 result XMLs, **not** read
  off `BUILD SUCCESSFUL`. `ConcurrentBookingTest` is among them and ran.
- **#10 verified against its counterfactual**, §4.1 — measured, not argued.
- **#11 measured in a browser**, computed heights rather than class names, which is the lesson issue
  #8 paid for:

| Surface | Control | Before | Now |
|---|---|---|---|
| `/book/{slug}` | Full name / Phone / Email | 40 px | **44 px** |
| `/book/{slug}` | Confirm booking | 40 px + override | **44 px** |
| `/book/{slug}` | Pager, slot buttons | 44 px | 44 px, unchanged |
| `/settings/booking` | Select, number inputs | 40 px | **44 px** |
| dashboard chrome | Sign out (`sm`) | 32 px | 32 px, unchanged |

  No horizontal overflow on either page.

- **CI green on the merged head.** All six checks — Backend, Frontend and Compose smoke test, on
  both the push and the pull request — against `637d41e`, which is the exact tree that became
  `main`. The Backend job runs the same 798 tests on a clean runner rather than on this machine, so
  it is the stronger of the two signals.

**Not verified.** Anything involving a real model — unchanged, and there is still no
`OPENAI_API_KEY`. Level 3 is still written and still unrun.

### 5.1 Merging found two things, and neither was a test failure

**`main` was not mergeable, and green CI would not have fixed it.** Branch protection on `main` sets
`required_status_checks.strict = true` — *require branches to be up to date before merging* — and
`main` carried one commit `dev` did not: `c56d9df`, the merge commit from PR #6. So #12 sat at
`mergeStateStatus=BEHIND` no matter how the checks went, and the failure mode is a quiet one: the
checks are green, the PR looks ready, and the merge button is simply refused.

Checked before fixing rather than after: `git diff origin/dev...origin/main` is **empty**, so there
was no content on `main` that `dev` lacked and the update was purely topological. `dev` already
carried the merge commits from #2, #3 and #4, so pulling `main` back is this repository's own
pattern and not an improvisation — there is even an `a038093` named *Record that main is already
contained in dev*. `gh pr update-branch 12` produced `637d41e`, which re-triggered CI, which is why
the suite ran twice.

**#7 did not close itself.** Its fix commit `f6cddf6` carries `Refs #7` rather than a closing
keyword, so GitHub never linked it, and the merge that closed the other five left it open. The PR
body claimed six. It was closed by hand *after* confirming the fix is genuinely on `main` —
`AppointmentLockRepository`, `DeadlockRetry`, and `BookingService` holding its boundary in a
`TransactionTemplate` — rather than on the strength of the PR body saying so.

**A closing keyword is worth writing even when a handoff records the link.** Five issues closed
themselves and one needed a person; the difference was one word in a commit message.

### 5.2 `dev` was fast-forwarded onto `main`

`dev` was an ancestor of the merge commit, so this is a true fast-forward and adds no commit. Done
deliberately rather than left: without it the next pull request opens one commit `BEHIND` and meets
the same strict-protection refusal described above, which is exactly the friction this session paid
for once already.

---

## 6. What a fresh session must not redo

- **Do not delete the four remaining `min-h-11` sites** as leftovers from #11. §3.1. Two are
  deliberately-small buttons with full-size targets and two are raw elements.
- **Do not put `Button`'s `md` back to `h-10`** to recover dashboard density. That is the decision in
  §2.1 being reversed, and it silently returns `/book/{slug}`'s three customer inputs to 40 px.
- **Do not assume `cn` merges Tailwind classes.** §3.2. It is a plain join, and a `className` height
  override is decided by stylesheet order.
- **Do not flatten `ALLOWED` back to key names**, and do not add an unqualified path to it. §4. The
  qualifier is what closes the root-object hole, not the path notation on its own.
- **Do not copy the shared appointment shapes** into each source to "make the list readable". §4.
- **Do not re-argue #10 into the record-to-keys map.** It was offered and declined, and the chosen
  option achieves the same property without the test needing to know which record backs which
  endpoint.
- **Do not trust `BUILD SUCCESSFUL`** — count the tests. Carried from the previous handoff and used
  in anger here.
- **Do not run `pnpm build` while `next dev` is running.** Unchanged, and still true.
- **The Browser pane must be visible.** A hidden pane is 0×0, the page never paints, and `navigate`
  times out after a full 300 seconds. It cost that here before the pane was revealed.
- **Do not read a green pull request as a mergeable one.** §5.1. `main` requires branches to be up to
  date, so a PR whose checks all pass is still refused while `dev` is behind. `gh pr view --json
  mergeStateStatus` is the field that says so; the checks do not.
- **Do not re-merge `main` into `dev` by hand while they are equal.** §5.2 already did the
  fast-forward. Merging again creates an empty merge commit for nothing.
- **Write `Closes #N`, not `Refs #N`, in a commit that fixes an issue.** §5.1. One word is the whole
  difference between the tracker maintaining itself and someone noticing months later.

---

## 7. Next steps, in order

### P0

1. **The frontend half of phase 09** — unchanged from the previous handoff, and now genuinely
   unblocked: the tracker is empty, `main` is current, and there is nothing left to fix first. It is
   the only substantial work between here and phase 10. What the phase document still owes:
   - the chat panel in `/book/[slug]`'s right column, full-width on mobile
   - the message list, typing indicator, and inline tool activity
   - the confirmation card **from `appointmentCreated`**, which is the Classic Flow's existing
     component rather than a new one
   - `sessionStorage` persistence, so a reload resumes and a new tab does not
   - the degradation banner on `AI_UNAVAILABLE` / `AI_LIMIT_REACHED`, and the permanent "book the
     classic way" affordance
   - `/conversations` and `/conversations/[id]` in the dashboard
   - **#11's decision now covers the composer and send button.** They inherit 44 px. Nothing needs
     `min-h-11`, and adding it would be re-creating the thing that was just removed
2. **The `aiEnabled` Settings toggle**, which phase 09 owes and neither half has built.

### P1

3. **Run level 3 once**, with a real key. Unchanged across three handoffs, and now the largest
   unverified thing in the project by a wider margin than ever — it is very nearly the only one
   left. Everything else on this list is work not yet done rather than work not yet checked.

### P2

4. **Retention.** Unchanged: nothing deletes an `ai_message`, and nothing is meant to yet.

### Done here, and off the list

**Merging #12**, which every handoff since 2026-09-09 carried as a P0 or P1. `main` is current for
the first time since phase 08, and the next pull request starts from an even branch.

---

## 8. Carried, and still carried

- **The advisory lock's cost under ordinary load is unmeasured.** One Employee's bookings serialise;
  twenty threads proved correctness, not throughput. Carried from the previous handoff, untouched
  here.
- **There is no "find my booking" page.** Phase 10 or 11.
- **`ConcurrentBookingTest` is still a 20-thread test** and still the heaviest thing in the suite. It
  should stay — it is what proves the constraint still admits exactly one row.
- **The value half of `PublicFieldAllowListTest` still only catches the four planted strings.** The
  path half now covers the *position* hole, which was #10; a leak of data the fixture does not plant,
  at a path that is legitimately allowed, is still invisible. That is inherent to a fixture-based
  check and is not filed as anything.
- **The sweep only sees paths the fixture actually produces.** A field on a branch no swept call
  exercises is unchecked. True before this session and true after.

---

## 9. Files, and what changed

`12d93b7` — #11:
```
frontend/src/components/ui/button.tsx             md 40 → 44 px, and the reason, once
frontend/src/components/ui/input.tsx              h-10 → h-11
frontend/src/components/ui/select.tsx             h-10 → h-11 — not named by the issue
frontend/src/components/week-editor.tsx           h-10 → h-11 — not named by the issue
frontend/src/app/book/[slug]/classic-flow.tsx     override + comment deleted
frontend/src/app/manage/[token]/manage-flow.tsx   two overrides + comment deleted
frontend/src/app/manage/[token]/reschedule-card.tsx  two overrides + comment deleted
frontend/src/app/landing-cta.tsx                  skeleton tracks the button it stands in for
```

`46c8d5c` — #10:
```
backend/src/test/…/PublicFieldAllowListTest.java  ALLOWED becomes qualified paths;
                                                  collectKeys → collectPaths; Body record;
                                                  NEVER matched on the leaf segment
```

`60168e8` — the handoff and its index row. `637d41e` — `main` merged into `dev` to satisfy strict
branch protection, no content (§5.1). `3efcfd2` — the merge of #12 into `main`.

Nothing else. `PublicResponses.java` was modified to plant the §4.1 leak and restored; it is
identical to `HEAD`.

---

## 10. Commands, and what they last returned

```bash
# Backend. From backend/. ~5 minutes.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```
`BUILD SUCCESSFUL in 5m 11s` — and, counted from `build/test-results/test/*.xml` rather than
believed: **91 classes, 798 tests, 0 failures, 0 errors, 0 skipped**.

```bash
# There is no formatter in the backend build. This fails, harmlessly.
./gradlew spotlessApply
```
`Task 'spotlessApply' not found in root project 'reception'.` Backend formatting is by convention
and by hand; only the frontend has a `format:check`.

```bash
# Frontend gates. From frontend/. NOT pnpm build — a dev server is running.
pnpm lint && pnpm typecheck && pnpm format:check
```
All three clean. `format:check` failed once on `reschedule-card.tsx` after an override was removed —
`pnpm prettier --write` on that one file fixed it, and the reflow is in the diff.

```bash
# The database, from the repo root. Note the container name.
docker exec -e PGPASSWORD=… reception-postgres-1 psql -U reception -d reception -c '…'
```
There are **three** Postgres containers on this machine — `smart-express-postgres` and
`order-management-platform-postgres-1` belong to other projects. A `docker ps | grep postgres | head -1`
picks the wrong one and fails with `role "reception" does not exist`.

```bash
# Whether a pull request can actually merge. The checks do not answer this.
gh pr view 12 --json mergeable,mergeStateStatus
```
Returned `mergeable=MERGEABLE mergeState=BEHIND` with every check green — §5.1. After
`gh pr update-branch 12` and a second CI pass it returned `CLEAN`, and only then did
`gh pr merge 12 --merge` succeed.

```bash
# What main requires before it will accept anything.
gh api repos/sanama-stack/reception-booking-system/branches/main/protection \
  --jq '{strict: .required_status_checks.strict, contexts: .required_status_checks.contexts}'
```
`strict: true`, contexts `["Backend", "Frontend", "Compose smoke test"]`. All three are required;
none may be skipped.

---

## 11. Confidence

**High — measured against a counterfactual.** §4.1. The old test and the new one were both run
against the same planted leak; one was blind to it and the other named it.

**High — verified against a command's output.** 798 tests, counted from the XML rather than inferred
from an exit code. The four `min-h-11` survivors and the absence of competing `h-*` classes were
grepped, not remembered.

**High — measured in the product.** §5's table is computed heights read out of a live browser on two
real screens, not Tailwind arithmetic.

**Moderate.** That raising every shared control to 44 px is right for the dashboard. It is the
principal's decision and the trade was stated, but the 4 px was checked for overflow on one Settings
screen, not on all of them. Nothing else was measured, and a dense table row elsewhere could look
different.

**High — checked on the server rather than assumed.** That #12 merged, that all six checks were
green on the merged head, that the tracker is empty, and that `dev` and `main` are the same commit.
Each read back from `gh` or `git` after the fact rather than inferred from a command having
succeeded — including #7, whose fix was confirmed present on `main` by listing the files before the
issue was closed rather than on the strength of the PR body claiming it.

**None — not verified at all.** Anything involving a real model. Unchanged, and now by a wider
margin than ever, since it is the only substantial unverified area left.

---

## 12. The verification tenant

**Untouched, and checked afterwards rather than assumed.** `Phase 06 Scratch` and the two other
businesses are as every previous handoff left them:

```
appointments | businesses | ai_conversations | ai_messages | schema_version
          32 |          3 |                0 |           0 |              7
```

The booking page was driven as far as **selecting a slot**, which is local state and writes nothing;
no booking was submitted. `/settings/booking` was opened on the signed-in owner's own business to
measure control heights and **nothing was saved** — a read, on a screen the memory note says to
prefer `Phase 06 Scratch` for, and worth naming rather than leaving to be discovered.

The backend on 9081 and the frontend on 9082 were left running and were not restarted.
