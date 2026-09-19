# Session handoff — 2026-09-18/19 — the blockers that had already lifted

> **Nothing in this sitting was unblocked by this sitting.** The demo script was carried as
> *"blocked on credits"*; the credits returned on **2026-09-15**, three days before anybody
> re-walked it. [#40] was carried as *"one trial, does not qualify for a rate"*; a third arm had
> made that false on **2026-09-17**. The security checklist said *"all fifteen sections done"*
> while recording **twelve**. The README quoted a **10.6%** rate its own fix had superseded. Four
> stale claims, none of them hard to check, all of them load-bearing. §7.
>
> **Phase 11 is closed. All eleven phases are complete**, and the MVP Definition of Done stands at
> **27 ticked, 3 carried**. §5.
>
> **The demo script was followed end to end** for the first time, which is what a first walk is
> for: it found one sentence in the script that was wrong. §2.
>
> **`dev` merged to `main`** as [PR #41], 43 commits, seven jobs green. `main` had been 40 commits
> behind since 2026-09-13. §4.
>
> **Two of my own instruments were defective and both failed in the safe direction**, which is the
> part worth carrying forward — a harness that can be wrong one way can be wrong the other. §6.

[prev]: ./2026-09-15-three-names-for-one-signature.md
[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#40]: https://github.com/sanama-stack/reception-booking-system/issues/40
[PR #41]: https://github.com/sanama-stack/reception-booking-system/pull/41

---

## 1. Where the project stands

| | |
|---|---|
| `dev` | **`646db39`.** Pushed. Working tree clean |
| `main` | **`1edfb34`**, the merge of [PR #41]. Level with `dev` as of that merge; `646db39` is one commit ahead and not yet merged |
| Backend | **1092 tests, 124 classes, 0 failures**, counted from the result XML |
| Phase 11 | **Every Definition-of-Done box ticked.** The one remaining checklist line is struck through and is not work |
| MVP DoD | **27 ticked, 3 carried.** The three are the Receptionist rows and all rest on [#17] |
| Open issues | [#17] fixed-not-closed · [#40] recorded, awaiting a ruling · [#15] title still wrong |

**Two sittings before this one have no handoff.** 2026-09-16 (the [#40] rate harness, the 14%
measurement, the retraction, the closure into [#17]) and 2026-09-17 (the fifth candidate's
pre-registration, [#15]'s re-baseline, and the fix itself) are recorded only in their commit
messages and experiment records, which are unusually thorough — but the convention was broken and
this row is where that is admitted rather than left to be noticed.

---

## 2. The demo script, walked end to end

Ten steps, on the topology step 1 names: `make up`, both applications from the terminal, `make
seed`. Not `make up-all`, which is what happened to be running.

**The backend was asserted before anything rested on it.** The stack that had been up for 45 hours
was a `make up-all` image, and a five-day-old IDE process was still holding `9081`. Rule 13 was
grepped out of `build/classes` rather than inferred from an image timestamp that landed two minutes
after the fix commit — close enough to look conclusive and not be.

What the walk produced, in its own numbers: confirmation in Mailpit in **~15 s** with the outbox row
`SENT` and the reminder queued for exactly 24 h before; the code surviving a move (*"It is still
AN9G65KS"*, in the email); tomorrow's manage page refusing **structurally**, rendering no move or
cancel control at all; revenue **890.00 → 930.00 GEL** on one completion; and the Receptionist
booking 23 September at 16:00 with `create_appointment`'s real `SENT`/`RETURNED` JSON readable under
**Conversations**, carrying no `business_id`.

**The script had one wrong sentence.** Step 3 read *"Calendar → Week. Three employees…"* — the
three-employee column layout is the **Day** view. Fixed. Everything else in that step was right,
and the hatching was confirmed in a screenshot rather than off the DOM, because a stripe pattern is
not something the text layer can report.

**One deviation is mine and is recorded in the tick rather than smoothed over.** Step 5 says
*private window*; the driver had one browser profile and signed out instead. Same end state, not the
same mechanism.

---

## 3. Phase 11's closeout, and the section that counted itself done

Three boxes, none tickable by reading.

**Security — the interesting one.** The checklist said *"all fifteen sections done 2026-09-13"*.
Twelve carry a walk. **§9, §10 and §11 do not** — §10 is not mentioned anywhere in this phase, and
every `§11` in it refers to a *different document's* §11. The three it skipped were precisely the
three with nothing written under them to make the absence visible. **Summarised was not the same as
walked**, which is the §3/§4 defect one level up.

All three proved substantively covered: each names its own tests inline, and each test was checked
to exist, to carry **no `@Tag`** — so it actually runs, which is how §8's claims were once
unchecked — and to assert the load-bearing claim rather than merely share its subject.

**§9 had a genuine hole.** It names `MAIL_PASSWORD` and `OPENAI_API_KEY` as *deliberately not
checked* and ends *"SecretsGuardTest holds all of this."* It did not. Twelve tests pinned what the
guard **refuses**; a deliberate **absence** was pinned by nothing. Adding either key would make the
`prod` profile refuse to start for a business whose SMTP relay needs no authentication, or one
running without the Receptionist — both supported — with the suite green.

**Shown red two ways, and the second is the one that matters.** `MAIL_PASSWORD` added to the guard
turns 4 red, three of them collateral. `OPENAI_API_KEY` checked with `contains` instead of
`startsWith` is caught by the new test **alone, 1 of 13** — and it is the realistic drift, because
`.env.example` ships `sk-local-dev-only-not-a-real-key`, which *contains* the marker the guard
matches and is not *prefixed* by it.

**Concurrency: 10 of 10, 30 tests, 0 failures**, each run with `--rerun-tasks` so Gradle could not
replay a cached pass. **Ten is *repeatedly*, not proof** — a fault rarer than about 25% per run sits
inside ten green runs unseen, and the row says so.

**Documentation.** `.env.example` re-measured rather than cited: 35 consumed, 35 declared, nothing
missing or unused. The one apparent miss was `${1}`, the Caddyfile's regex capture in the
manage-token redaction. `deployment.md` is complete *as a document*, with a §7 separating verified
from reasoned; walking it on a host was never in scope.

---

## 4. `main`, and the merge style the README gets wrong

[PR #41], 43 commits, all seven jobs green on both runs before it landed, merged as `1edfb34` and
green on `main` afterwards — verified against the merge commit's SHA, not merely the newest run on
the branch.

**Merged, not squashed, deliberately.** The README's *Branching* section says `gh pr merge
--squash`. `main` holds the individual commits of every previous PR, so that line has never
described what actually happens — and squashing here would have collapsed 43 messages carrying
p-values, retractions and rejected candidates into one. **The README line and the practice still
disagree**, and reconciling them is open work in either direction.

---

## 5. What the MVP list now means

**27 ticked, 3 carried.** The list's own rule is that a box is ticked against evidence **or** its
defect is carried under *Accepted, measured, open defects* with a rate, a date and an issue. [#17]
now has all three, so nothing on that list is unaccounted for.

The three that remain are the Receptionist rows, and they are **one question, not three**. [#17] is
fixed and measured at **98%** correct landing, but 98% is not 100%, fifty trials bound the residual
only below **10.6%**, and the arm covered one phrasing at one distance. The relative phrasing that
was the 55.2% worst case has had **zero trials since the fix**.

**[#40] is now filed** under *Accepted, measured, open defects*, written as an **order of magnitude
rather than a rate** — 2 observations in 150 conversations — with its **Ruling row deliberately
empty**, because every other entry there records a decision to ship knowingly and this one records
only a measurement. **Every observation of it is of a prompt that no longer ships**: trials 38 and
40 are in the fifth candidate's *baseline* arm. Relabelled `ready-for-human`.

---

## 6. Two defective instruments, both failing safe

**The concurrency harness reported a pass as a failure.** It computed the verdict in the shell with
`set -- $r`, which does not word-split in zsh, so every field landed in `$1` and run 1 was reported
FAIL when its XML said 3 classes, 10 tests, 0 failures. Rebuilt in Python and **shown able to fail
before any of the ten runs were trusted** — planted failure, empty population, clean control.

**A `grep -rl` for rule 13 in `build/classes` returned nothing**, and the rule was there: `.class`
is binary and the flag combination skipped it. Had that been taken at face value it would have read
as *the fix is not in the running build*, and the walk would have been abandoned or the backend
needlessly rebuilt.

**Both failed in the safe direction and that is the point.** A verdict that can be wrong one way can
be wrong the other, and neither was caught by noticing the answer was implausible — both were caught
by checking the instrument against a case whose answer was already known.

---

## 7. The lessons, T199–T201

| | |
|---|---|
| **T199** | **A blocker is a claim with a date, and it expires.** Four separate items were carried as blocked by conditions that had already lifted — credits three days earlier, a denominator two days earlier. Nothing re-read them because a carried blocker reads as settled. Re-check the *condition*, not the note |
| **T200** | **A summary that counts is not a record that walked.** *"All fifteen sections done"* counted fifteen and recorded twelve, and the three it skipped were the three with no text under them to make the gap visible. Where a count and an enumeration disagree, the enumeration is the evidence |
| **T201** | **Prove the instrument on a known answer, not on a plausible one.** Both defective instruments this sitting returned answers that looked fine in context. Neither was caught by judgement; both were caught by a control whose result was known in advance |

---

## 8. Next steps, in order

1. **Merge `646db39` to `main`.** The closeout is on `dev` only. CI was in progress at the time of
   writing — confirm it before opening the PR.
2. **[#40]'s ruling, and the sample-size call underneath it.** The principal's. At ~1.3% of
   conversations, fifty trials sees the mode about half the time, so the arm is a decision to make
   *before* it is bought.
3. **[#17]'s relative-phrasing arm.** Zero trials since the fix, and the largest remaining unknown
   in the project. The 55.2% it replaced is the only reason to think rule 13 might not carry.
4. ~~**[#15]'s title**, which still names a diagnosis a later session disproved — its own entry calls
   fixing it a prerequisite to accepting the issue, not a formality.~~ **Done later the same day, and
   the item was wrong when written.** The title had been corrected on 2026-09-10 — *the day before*
   the sentence in `07-mvp-scope.md` that called it a prerequisite — and again on 2026-09-16. What
   actually needed fixing was that sentence. [#15] now has a full entry under *Accepted, measured,
   open defects* with its bound, and its title was sharpened once more: **undetectable** in row 4
   became **not detected in 150 trials**, because the first reads as a property of the defect and
   only the second is a property of the sample.
   **This item is kept struck through because of what it is.** §7 of this document names T199 — *a
   blocker is a claim with a date, and it expires* — and then, forty lines later, §8 repeated an
   expired claim verbatim from the very document the lesson was drawn from. Writing a lesson down is
   not the same as applying it, and the gap between the two here was under an hour.
5. **The README's `--squash` line**, which disagrees with every merge this repository has made.
6. **The 360 px `/conversations` filter button**, carried since 2026-09-10 and still not covered at
   that viewport.

---

## 9. Confidence

**High** that phase 11's three boxes are genuinely closed rather than declared. Each was exercised:
ten runs, two plants reverted byte-identically, and a re-measurement rather than a citation.

**High** that §9's gap was real. The second plant is caught by one test out of thirteen, and the
value it uses is the one `.env.example` actually ships.

**Moderate** about the concurrency claim, and the row says so in writing: ten runs cannot see a
fault rarer than about 25% per run, and a booking race is exactly the kind of fault that hides
below that.

**Low** about anything concerning [#17] at a phrasing or distance other than the one measured. One
arm, one day, one distance, ISO dates only. The project has now been wrong three times about what a
signature meant, and each time the cost was one arm.
