# Session handoff — 2026-09-13 — the third state

> **Purpose.** The principal ruled: **record level 3 as unavailable.** Carrying it out turned out to
> require admitting something the Definition of Done explicitly denied.
>
> `07-mvp-scope.md` said, in bold: *"A box is ticked against evidence, or the defect it covers is
> listed under Accepted, measured, open defects with a rate, a date and an issue. **There is no third
> state.**"*
>
> **There is.** A box can be *unverifiable* — the instrument that would decide it cannot be run. That
> is neither a tick nor a measured defect: there is nothing to tick against, and no rate to carry
> because nothing was measured. **The document that insisted on two states had nowhere to put this
> one**, which is part of why it sat unnamed while seven handoffs asked the principal to choose.
>
> **`Gates that cannot be run`** is the new section. No rates. An entry needs what the gate checks,
> when it last ran, what has changed since, and what must happen for it to run again — and it leaves
> in one direction only: the gate runs, and the result becomes a tick or a measured defect.
>
> **The dates are worse than the last handoff said.** The corpus last ran green on **2026-09-10**.
> The credits ran out on **2026-09-11**, *during* the experiment that shipped the first prompt change.
> The second landed **2026-09-13**. So the corpus has never been run against either change and **never
> could have been** — G32 was born blocked, two days old at birth.
>
> **Nothing was pushed and no code changed.** Three documents, one commit plus this file, leaving
> `dev` **42 ahead of `origin/main`**. Phase 11 still at **62 of 72** — no box moved, and §2 says why
> that is the correct outcome.

[prev]: ./2026-09-13-g32-was-never-a-decision.md
[previous]: ./2026-09-13-g32-was-never-a-decision.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. Still no pull request |
| `dev` | **42 ahead of `origin/main`**, 40 ahead of `origin/dev`. The work is **`ec65114`** |
| CI | **has still seen none of it.** Fourteen sessions |
| Backend | **1054 tests, 0 failed, 120 classes** — unchanged; **no code was touched** |
| Level 3 | **Recorded unavailable.** Ruling of 2026-09-13 |
| Migrations | **`V10`**, unchanged. No new ADR — §4 |
| Issues | [#17] and [#15] open, untouched |
| Gates | `make check-docs` green |
| Phase 11 | **62 ticked, 10 open**, unchanged — §2 |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 2. Why nothing got ticked, and why that is right

Recording a gate as unavailable does not tick anything and must not. The three Receptionist rows in
*Functional* — answers from configured information, offers only engine slots, writes land on the named
date — were already unticked and already carried under [#17].

What changed is what a reader now learns from their being unticked. Before: *work outstanding.* Now:
**work that is not currently possible**, with the reason, the last green run and the cost of resuming
written beside it. At a sign-off those are different sentences, and the second is the true one.

Phase 11's own sign-off box was widened to admit the state at all — as written it offered a ticker two
options, and the situation is a third.

---

## 3. The dates, corrected

[The previous handoff][prev] said the corpus "has not been run against the current prompt". True, and
it understated it.

| | |
|---|---|
| Corpus last ran green | **2026-09-10** |
| **Credits exhausted** | **2026-09-11**, during [#17]'s deciding run — it stopped at fifteen trials of fifty |
| Prompt change 1 | **2026-09-11** — `resolve_date` and the instructions driving it |
| Prompt change 2 | **2026-09-13** — §8's fencing of the description, cancellation policy and FAQs |
| G32 raised | **2026-09-13** |

**The credits ran out before the first change and two days before the question was asked.** There was
never a moment when the corpus could have been run against either. G32 did not become blocked; it was
never anything else.

### 3.1 And it was written down the whole time

- `07-mvp-scope.md`, **2026-09-11**, under [#17]'s *Status*: *"stopped at fifteen trials of fifty when
  the OpenAI account ran out of credits"*.
- `phase-11-hardening-and-deployment.md`, §8's note: *"`-PincludeTags=llm` needs a key and credits"*.

Both were in the repository before G32 was raised. Seven handoffs then listed G32 and *"credits for
[#17]"* in the same numbered list of the principal's calls, session after session, without either
being read against the other.

> **T135, sharpened.** The last handoff drew *"try the thing before escalating the decision about the
> thing."* That is right and it is not the whole lesson, because this was not merely untried — **the
> answer was already in two documents.** Two facts in one list are not connected by being in one
> list. **A handoff that carries an item forward unchanged is not re-reading it**, and seven of them
> proved it: the item was copied, not examined, and copying is invisible from inside.

---

## 4. No ADR, and why

This is a release-quality decision rather than an architectural one — nothing about the system's
structure changed, and the eleven ADRs are all design choices with consequences in code. The document
the phase-11 sign-off box actually points at is `07-mvp-scope.md`, so that is where the ruling lives,
in a section that box now names.

Recording it in two places would have created the thing this walk keeps finding: a claim in prose and
a claim in a checklist, agreeing by hand.

---

## 5. Every open item

### 5.1 Committed, not pushed

**Forty-two commits, fourteen sessions, no CI.**

### 5.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**.

**G32 is closed.** Answered [last session][prev], ruled and recorded this one.

**G39 is unchanged and now has a home.** *The corpus has never been shown to go red for a behavioural
reason since the abort was added.* It costs one run on a funded account and is listed in the scope
document's entry as part of what running the gate again must confirm.

### 5.3 Carried

Unchanged from [the previous handoff][prev] §5.3.

### 5.4 The E2E stack

Unchanged and not attempted.

---

## 6. Next steps, in order

1. **Push, and open a pull request.** Forty-two commits, fourteen sessions. Deferred ten times.
2. **Try the E2E stack on an unmetered connection.** It unblocks eight of the ten remaining phase-11
   items, and `make check-access-log` has still never executed anywhere.
3. **The full-history secret scan.** Needs a scanner installed.
4. **The end-of-phase gates**, which want the stack from item 2.
5. **If credits are ever added**: run the corpus, close G39, and move the level-3 entry out of *Gates
   that cannot be run*. [#17]'s open arm is the same run.
6. **The principal's**: G29, G30, G31; [#15]'s title.

**The AI thread is closed for now.** Everything left needs the network, a scanner, or money.

---

## 7. Traps

- **T138 — a rule that denies a state has nowhere to record it.** *"There is no third state"* was
  written to force a choice and it worked, right up to the case that was neither. **A checklist's
  exhaustiveness claim is itself a claim, and it can be wrong.** §1's blockquote.
- **T139 — two facts in one list are not connected by being in one list.** G32 and *"credits for
  [#17]"* sat in seven consecutive next-step lists. §3.1.
- **T140 — carrying an item forward is not re-reading it, and the difference is invisible from
  inside.** Seven handoffs copied G32's description verbatim. A carried item should periodically be
  re-derived from the repository rather than from the previous handoff.
- Carried and re-confirmed: **T135**–**T137**, **T132**–**T134**, **T129**–**T131**, **T124**–**T128**,
  **T89**, **T104**/**T118**, **T105**, **T113**, **T116**, **T120**, **T122**, **T69**, **T70**.

---

## 8. Commands

```bash
# The documentation gate — the only one this session could move.
make check-docs
```

```bash
# The level-3 corpus, for whoever has credits. Skips with the provider's own reason if it cannot ask.
cd backend && OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-) JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests '*LiveReceptionistTest' -PincludeTags=llm
```

---

## 9. Confidence

**Certain on the ruling and its record.** It is the principal's, dated, and it lives in the document
the sign-off box points at.

**High on the dates in §3.** Each is a commit date or a line already in the repository, not a
recollection: `93439d3` for the credits, `c81d312` and `f5fa215` for the two prompt changes.

**Nothing was verified by running anything**, because nothing runnable changed. The suite was not
re-run for that reason, and `make check-docs` is the only gate this session could move.

**Unchanged and unhappy on the deployed picture.** Fourteen sessions, no CI.
