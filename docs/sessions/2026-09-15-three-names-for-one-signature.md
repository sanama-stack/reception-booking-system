# Session handoff — 2026-09-15 — three names for one signature

> **The credits came back, and the first thing they bought was a correction to how this project
> names what it measures.** One observation — wrong writes landing on `2026-09-29` — was named
> `today + 14`, then `target + 1`, and is actually **`booked + 1`**. Each name fitted every
> observation available when it was made. Each was settled not by re-reading the old arms but by
> moving one variable, and each cost an arm to learn. §3.
>
> **Three causes for #17 were tested and eliminated**: `resolve_date` (removed entirely, p = 0.77),
> distance-to-horizon (13 days vs 10, p = 0.77), and the timezone. What is left is a clean
> statement of the defect — **on a reschedule, `date_from` is the day after the appointment's
> *current* date, ignoring the one the Customer named** — and one genuinely open question: the
> same fixture at the same distance scored **89.4%** on 2026-09-11 and **25.0%** tonight,
> p = 5.0 × 10⁻⁹. §3.4.
>
> **The resolver arm finished**, four days after an outage stopped it fifteen trials in. Primary
> met at *exactly* its pre-registered minimum, 19/50 — and the second veto fires, on 2 trials.
> Read literally, that is not an acceptance. **The call is the principal's and has not been
> made.** §2.
>
> **`UNASSERTED_WRITE_FAILURES` is 0**, from 28. The suite went from 110 tests in 28 files to
> **197 in 47**. What closed it was not diligence but a harness that could express the case at
> all. §4.
>
> **Three claims I published were refuted by measurement**, two of them on the issue itself. They
> are corrected in the repo and on #17, and the reasoning errors are T195–T197. §6.

[prev]: ./2026-09-14-the-name-nothing-had-to-use.md
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15

---

## 1. Where the project stands

| | |
|---|---|
| `dev` | **`be1f3c6`.** Twelve commits this sitting. Working tree clean |
| **Pushed** | **Yes, all of it.** CI green on the head — all seven jobs |
| Backend | **1087 tests, 0 failures**, counted from the result XML rather than read off `BUILD SUCCESSFUL` |
| Frontend | **197 tests in 47 files** (from 110 in 28). typecheck, lint, prettier, coverage gate green |
| E2E | **Run, and green** — in CI, three times. The 360 px `/conversations` risk is still *not* covered |
| Model | **Measured at last.** Four rate arms and three probe batches, ~200 live conversations |
| Ratchet | `UNASSERTED_WRITE_FAILURES` **0**, and still biting: setting it to 1 turns the gate red |
| Gaps | **G48** and **G49** still open, both wanting decisions |

```text
699b498  Watch four writes fail, and build the harness mode that lets them
65498d2  Finish the resolver arm, and report the veto it fires
44559c8  Press the three buttons whose screens only had empty states
ca9db03  Watch the two writes that do not fail like the others
1486cf5  Test the shared week editor, through both screens that use it
98c5368  Refuse a turn and a reorder, the two writes with nowhere to put a banner
9aee352  Assert the sign-out that shows nothing, and the conflict that says its own words
353561d  Find the off-by-one, and stop a long arm outliving its login
1896d78  Watch two refusals whose promise is state rather than a sentence
7245c85  Separate the two dates that were always equal: it is booked+1
5280f49  Watch three settings writes, and share the profile fixture they needed
be1f3c6  Close the write ratchet: 28 to 0
```

One CI run shows a red Backend job — `7245c85`, run 35008099952. **It was cancelled, not failed**:
*"Canceling since a higher priority waiting request for CI-refs/heads/dev exists."* I pushed the
next commit while it ran. `5280f49` touched only frontend files, so its backend tree is identical
and went green; nothing in `7245c85` is unverified. Pushing once per batch avoids the churn.

---

## 2. The resolver arm, finished — and not accepted

`c81d312` landed `resolve_date` as **work in progress**, because the deciding measurement stopped
fifteen trials in when the OpenAI account ran out of credits. That is the one thing the credits
unblocked, and it is done: fifty conversations, `PROBE_DATE_STYLE=WEEKDAY`, 0 errored, resolver
called in **50 of 50**.

| | control (2026-09-11) | this arm | Fisher, one-sided |
|---|---|---|---|
| **Window covered the named date** (primary) | 9/50 = 18.0% | **19/50 = 38.0%** | **p = 0.022** |
| Strict landing | 18/44 = 40.9% | **35/50 = 70.0%** | **p = 0.0041** |
| Never wrote | 6 | **0** | — |
| Nearer misreading of the phrase | 24 | **0** | — |

**The rule is `≤ 18/50 reject; ≥ 19/50 accept, if neither veto fires`.** The primary is *exactly*
19/50 — met, not cleared. Veto 1 does not fire. **Veto 2 does**, on trials 31 and 44: both asked
`MONDAY+1`, were answered `2026-09-28`, and wrote `2026-10-05` — "a landing one step off the
resolver's own output", stated exactly.

Whether a 2-in-50 overshoot should sink a change that doubled the primary is a judgement §8 of the
pre-registration **deliberately did not delegate to whoever reads the numbers**. So the tool stays
shipped-but-under-test, and the architecture doc's row still says so.

**Two things not to carry forward.** The 2026-09-11 arm's **81.6% is superseded** and disagrees
with this one at p = 0.036 on the primary; the replication gap is unexplained. And
`PROBE_DATE_STYLE=ISO` calls the resolver **0 times in 50** — an ISO arm cannot judge this tool,
which cost one run to learn.

---

## 3. #17: three names for one signature

### 3.1 What was actually measured

Four ISO arms on one calendar, plus the weekday arm above.

| arm | appointment | asks for | first search | wrong landings | strict landing |
|---|---|---|---|---|---|
| resolver present | 2026-09-28 | 2026-09-28 | `2026-09-29` | `2026-09-29` ×27 | 12/40 = 30.0% |
| resolver **removed** | 2026-09-28 | 2026-09-28 | `2026-09-29` | `2026-09-29` ×23 | 8/32 = 25.0% |
| target moved | 2026-09-25 | 2026-09-25 | `2026-09-26` ×26 | `2026-09-28` ×23 | 8/32 = 25.0% |
| **days separated** | **2026-09-22** | **2026-09-25** | `2026-09-23` | **`2026-09-23` ×25, `2026-09-26` ×0** | 17/42 = 40.5% |

### 3.2 The naming, three times

| named | was also | separated by |
|---|---|---|
| `today + 14` — the bound `find_available_slots` advertises | `target + 1` | moving the target |
| `target + 1` | `booked + 1` | moving the appointment |
| **`booked + 1`** | — | stands |

**On a reschedule, `date_from` is set to the day after the appointment's current date, ignoring
the date the Customer named.** Everything else follows from opening hours: a Friday target sends
the model to Saturday (closed), Sunday (closed), then Monday — three days past — while a Monday
target lands one day past. Two signatures, one mechanism.

This is sharper than [#17]'s body, which describes a search over `[tomorrow, tomorrow+6]`. That
mode still exists — 2 of 28 first searches — but it is now the rare one.

### 3.3 Three causes eliminated

- **`resolve_date`.** Removed entirely — the bean *and* both prompt passages — verified by dumping
  an eight-tool schema and a prompt naming it zero times. p = 0.77.
- **Distance to the horizon.** 13 days out against 10. p = 0.77.
- **The timezone.** The JVM runs at UTC+14 against a Tbilisi business, the obvious source of a +1
  — but the first two arms ran while both zones read the same date and showed the same drift.

### 3.4 What is unexplained, and is now the whole question

The same fixture at the same distance scored **42/47 = 89.4%** on 2026-09-11 and **8/32 = 25.0%**
on 2026-09-15, **p = 5.0 × 10⁻⁹**. Three candidate causes are gone and that gap is untouched.
Whatever changed in four days is the question; nothing in this repository answers it.

### 3.5 The same-day fixture was masking a near miss

Separating the days moved strict landing 25.0% → 40.5% (p = 0.125, suggestive only) but moved
*window covered the named date* from 6/50 to **41/50, p = 4.9 × 10⁻¹³**. The model's search range
nearly always covers the named date. It simply does not start there.

### 3.6 An untried candidate, with a warning

Nothing in the prompt or the tool schemas tells the model that a move's search starts from the
**requested** date; `lookup_appointment` hands it the current one, and that is what it uses.
**Zero trials. It must not be committed unmeasured** — three of the four candidates before it were
rejected and two made things measurably worse. Instrument: `RescheduleDateFidelityRateTest`.
**Not `probe.py`** — see §6.3.

---

## 4. The write ratchet, 28 to 0

197 tests in 47 files. Every one of the twenty-eight entries was proven against a counterfactual
rather than trusted for passing, and several of those counterfactuals are the *obvious
simplification*:

| screen | the plausible change that turns it red |
|---|---|
| `week-editor` (both screens) | key messages by row index instead of submitted position |
| `profile-form`, `customer-form`, `employee-form`, `booking-form` | treat any fielded message as shown |
| `delete-service` | make the refusal a toast like every other write's |
| `active-toggle` via `employees-screen` | close the dialog on refusal |
| `services-section` | snap the selection back to the server's last word |
| `session-context` | move the clear-and-redirect inside the `try` |
| `appointments/new`, `classic-flow` | clear the typed details on a stale slot |

**What closed it was the harness, not diligence.** Every `serve()` mode was all-or-nothing, so a
screen could only be shown a server refusing *everything* — which never reaches a save button,
because the read behind the screen fails first and the loading state is what gets asserted. That
is why the count started at 28 with none taken. `serve({ kind: 'body', refusing })` is the missing
mode; `refusing.path` competes under the same longest-prefix rule the bodies use, so a refusal
aimed at `…/chat` no longer takes `…/chat/session` down with it.

Also added: `Retry-After` as a header rather than a body field; one stable router in
`test/navigation.ts` so a navigation can be asserted at all; and `PROFILE` lifted into
`src/test/fixtures.ts`.

The constant **stays at zero** rather than going out with the debt. Setting it to 1 with nothing
unasserted turns the gate red, so it is still a claim being checked.

---

## 5. Two harness bugs that were costing real things

**A long arm outlives its own login.** The fixture's access token lives fifteen minutes; an ISO
arm ran 16m 32s because the provider was slow that hour and died at trial 36 with
`401 TOKEN_EXPIRED` — thirty-six trials of paid model calls thrown away for a reason unrelated to
what was being measured. `BookingScenario.bookedAt` refreshes once on a 401 and retries; once
only, and on demand rather than on a schedule so the expiry path keeps being exercised.
`ProbeInstrumentationTest` holds it to that by dropping the access cookie — the exact state of a
real session fifteen minutes in — and booking through it. Free, in CI, red without the retry.

**Two knobs that should have existed.** `PROBE_TARGET_DAYS` and `PROBE_BOOKED_DAYS`, both refusing
a weekend rather than measuring one: the business opens Monday to Friday, so every trial would
answer NO WRITE and the summary would read as a behavioural collapse — the T36 shape.

---

## 6. What I got wrong

Three published claims, all refuted by measurement, two of them on [#17] where they would have
misdirected the next session.

**6.1 "The resolver caused the ISO regression."** A tool in the schema can change behaviour
without being called, which is true in general and false here. Removing it moved nothing.

**6.2 "The signature is `target + 1`."** It is `booked + 1`. Both namings fitted every observation
available; no amount of re-reading the earlier arms could have separated them.

**6.3 "The defect is screenable by `probe.py`."** It is not — **70 of 70** across three
utterances, inside the seven-day list, outside it, and with a time. The model copies an explicit
ISO date into `date_from` without difficulty. I reasoned from *where* the bad argument is filled
rather than *what the model had been told before filling it*, and in doing so **overrode a
documented, measured limit with an inference.** The three-instrument table was right.

**6.4 A proxy signal I accepted twice.** A backgrounded `cmd > log 2>&1; echo "EXIT=$?"` always
reports exit 0, because the shell's status is the echo's. A failed probe arm and an unfinished
backend suite both came back "completed (exit code 0)". Read the artifact — `BUILD SUCCESSFUL`, or
better, counts parsed out of `build/test-results/test/TEST-*.xml`.

---

## 7. The lessons, T195–T197

| | |
|---|---|
| **T195** | **A signature consistent with two explanations is evidence for neither.** `2026-09-29` was read as a horizon bound through two arms because it was simultaneously `target + 1`. Re-reading those arms harder could never have separated them |
| **T196** | **A long arm outlives its own login.** Any harness that authenticates once and then runs longer than a token lifetime throws away everything it has bought, at the least convenient moment, for a reason unrelated to what it was measuring |
| **T197** | **Separate two variables before naming a signature after either.** Three namings, two wrong, each consistent with everything then observable. The cost each time was one arm; the fix each time was moving one variable |

---

## 8. Next steps, in order

1. **Decide `resolve_date`.** §2. The pre-registration, read literally, does not accept it. Nothing
   on [#17] can close until this is called, and it is the principal's.
2. **Re-baseline [#15].** Still not done, and it has been open since this sitting began:
   `WeekdayResolutionRateTest`'s own javadoc says its 142/150 predates the resolver, so **#15's
   headline number describes a prompt this repository no longer ships.** 150 conversations, ~12
   minutes — and the token fix now means it will survive a slow hour.
3. **The `89.4% → 25.0%` gap.** §3.4. Three causes eliminated, nothing left standing. This is the
   most interesting open question in the project and it has no candidate.
4. **§3.6's candidate**, with a pre-registration written before any number is collected.
5. **G48 and G49.** Both still decisions, not patches.
6. **The 360 px `/conversations` filter button.** CI's E2E is green and does not cover that
   viewport. Nine sittings carried now.

---

## 9. Confidence

**High** that the defect is `booked + 1`. Twenty-five wrong landings on it, zero on the
alternative, in an arm built specifically to separate them.

**High** that `resolve_date`, distance and the timezone are all eliminated as causes of the ISO
gap. Each was tested with one variable moved and a p-value attached.

**High** about the ratchet. Every entry has a counterfactual that turned red, and the gate itself
was checked at zero.

**Moderate** about the 40.5% in §3.1 — it is 42 writes of 50 trials with 3 errored, and its
comparison against 25.0% is p = 0.125, which is not a result. The *landing distribution* is what
that arm establishes, not the rate.

**Low**, and this is the honest centre of the sitting: **nothing explains the 89.4% → 25.0%
gap.** Three hypotheses were spent. A fourth is not in hand.
