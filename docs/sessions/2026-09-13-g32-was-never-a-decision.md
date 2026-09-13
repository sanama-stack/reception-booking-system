# Session handoff — 2026-09-13 — G32 was never a decision

> **Purpose.** **G32**, carried as *"the principal's call"* for seven consecutive handoffs: *the
> system prompt changed and the level-3 corpus has not been run against it.* The principal asked for
> an answer. **The answer is that it is not a decision and never was.**
>
> **The corpus cannot run. The OpenAI account has no credits.**
>
> > `429 Too Many Requests` — *"You have no credits remaining."* `insufficient_quota`,
> > `credit_balance_exhausted`
>
> Every one of the twelve conversations was refused before a token was billed, so **the attempt cost
> nothing**. Seven handoffs asked the principal to decide whether to spend the time. The thing
> blocking it was never time.
>
> **And the instrument reported it as twelve behavioural regressions.** All twelve tests failed at the
> same line with `AI_UNAVAILABLE`, and the run said *"12 tests completed, 12 failed"* with the word
> quota nowhere in it. The corpus is run **before a release and after a system prompt change** — which
> is to say, precisely when somebody is looking for a regression and primed to find one. §3.
>
> The class already skipped itself without a key, under a comment saying why: *"a red build on a
> machine that was never meant to run them teaches people to ignore red builds."* **A key the provider
> refuses is the same situation and behaved the opposite way.** It now aborts once, quotes the
> provider's own words, and says in the message that a skip is **not** a pass.
>
> **What the principal actually has to decide** is in §2, and it is a smaller question than seven
> handoffs implied: **add credits and run twelve cheap conversations, or record that level 3 is
> unavailable and say so in the phase-11 sign-off.**
>
> **Committed, not pushed.** One commit plus this file, leaving `dev` **39 ahead of `origin/main`**.
> 1054 backend tests, 0 failed. Phase 11 still at **62 of 72**.

[prev]: ./2026-09-13-the-list-that-was-one-short.md
[previous]: ./2026-09-13-the-list-that-was-one-short.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. Still no pull request |
| `dev` | **39 ahead of `origin/main`**, 37 ahead of `origin/dev`. The work is **`8060278`** |
| CI | **has still seen none of it.** Thirteen sessions |
| Backend | **1054 tests, 0 failed, 120 classes** — unchanged; the corpus is excluded by default |
| Level 3 | **12 tests, 12 skipped.** Cannot run — §2 |
| Frontend | **82 tests**, untouched and not run |
| Migrations | **`V10`**, unchanged. No new ADR |
| Issues | [#17] and [#15] open, untouched |
| Gates | `make check-docs` green |
| Phase 11 | **62 ticked, 10 open**, unchanged |
| E2E stack | **down.** Not attempted |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

Production code changed in **no file**, for the seventh session running.

---

## 2. The answer, and the decision that is actually left

**G32 is blocked, not deferred.** The distinction matters because the two have different owners: a
deferred item waits on somebody choosing to spend an afternoon, and a blocked one waits on somebody
topping up an account. Seven handoffs described the first and the truth was the second.

What is known without running it:

- The system prompt changed six sessions ago — §8's FAQ fencing, which put the description, the
  cancellation policy and fifty FAQs inside delimiters that only `ai_additional_info` had before.
- **Levels 1 and 2 cannot evaluate it.** A scripted model reads neither the system prompt nor a tool
  description, which is the whole reason level 3 exists.
- `SystemPromptSafetyTest` asserts the fencing is emitted. That the fence is *present* is checked;
  that the Receptionist still answers from a fenced FAQ is not.

So the exposure is unchanged and is narrow: **the fencing could have made the model less willing to
use the FAQs it is fenced around, and nothing would say so.** It cannot have widened the injection
surface — the fence only ever adds delimiters.

The choice:

| | Cost | What it buys |
|---|---|---|
| **Add credits, run the corpus** | Twelve `gpt-4o-mini` conversations. Cents | The level-3 gate, green or red, against the current prompt |
| **Record level 3 as unavailable** | Nothing | An honest phase-11 sign-off that says the gate was not run and why |

**Both are fine and the second is not a failure**, but it has to be *written down* rather than left as
a gap — which is what four previous handoffs asked for and what this one is finally in a position to
make concrete, because the reason is now known.

The one thing that is no longer acceptable is carrying it as *"the principal's call"* with no cost
attached to either side.

---

## 3. The instrument, which reported a billing problem as a behavioural one

```
12 tests completed, 12 failed
```

Twelve red level-3 tests, every one of them an assertion about what the Receptionist does. Nothing in
the summary mentions quota; the reason is a `ChatModelException` logged twelve times inside the noise
of a Testcontainers run.

The failure mode this creates is specific and expensive. The corpus is run **after a system prompt
change** — a reader who has just changed the prompt, running the one instrument that can evaluate it,
gets twelve failures naming booking, cancellation and the adversarial cases. The obvious conclusion is
the wrong one, and the cost of reaching it is a revert.

### 3.1 The principle was already in the file

```java
// Skipped rather than failed without a key. These are opt-in by design, and a red build on a
// machine that was never meant to run them teaches people to ignore red builds.
assumeTrue(properties.isConfigured(), "no OPENAI_API_KEY configured");
```

Correct, and it covers *no key*. A key the provider refuses is the same situation — the corpus cannot
ask the model, so there is nothing to assert — and it fell through to twelve assertion failures. **The
guard was written against the case somebody had had, not against the class it belongs to.**

### 3.2 Why this is a skip and not a swallowed failure

`ConversationService` raises `AI_UNAVAILABLE` in exactly one place, and only when the adapter threw
`ChatModelException` — the provider call itself failing. **A model that answers badly cannot produce
it.** So nothing the corpus exists to catch is turned into a skip.

Shown rather than argued: pointed at any other error code, the twelve failures come straight back
(§4). The skip message also refuses to be mistaken for a pass —

> *"This is a skip and not a pass — the corpus has NOT been run against the current system prompt."*

— and quotes the provider verbatim, because `ConversationService` logs the cause and then throws a
sentence written for a customer, so the log is the only place the reason exists.

---

## 4. Every plant

| # | Plant | Result |
|---|---|---|
| 1 | The error-code check pointed at a different code | **12 failed** — the check is what makes it a skip, not the `catch` |
| 2 | The run itself, before the change | **12 failed**, no mention of quota — §3 |
| 3 | The run itself, after the change | **12 skipped**, provider's words quoted |

**One direction is unproven and it is worth naming.** That a genuine behavioural failure still fails
cannot be demonstrated while the provider refuses every call: every test aborts at its first turn.
Plant 1 is the closest available evidence — it drives the same path with the abort disarmed and the
failures arrive — and the structural argument in §3.2 is exact. But **the first person with credits
should run the corpus and confirm it can still go red for the right reason.**

---

## 5. Every open item

### 5.1 Committed, not pushed

**Thirty-nine commits, thirteen sessions, no CI.**

### 5.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**.

**G32 is answered and reclassified.** It is not a decision about effort; it is blocked on account
credits, and the decision left is §2's two-row table. It should be carried as *blocked* until somebody
adds credits or records the sign-off.

**G39 is new.** *The level-3 corpus has never been shown to go red for a behavioural reason since the
abort was added.* §4. It costs one run on an account with credits and should be done as part of §2's
first option rather than separately.

### 5.3 Carried

Unchanged from [the previous handoff][prev] §5.3.

### 5.4 The E2E stack

Unchanged and not attempted.

---

## 6. Next steps, in order

1. **Push, and open a pull request.** Thirty-nine commits, thirteen sessions. Deferred nine times.
2. **§2: add credits, or record level 3 as unavailable.** The first also closes G39.
3. **Try the E2E stack on an unmetered connection.** It unblocks eight of the ten remaining phase-11
   items, and `make check-access-log` has still never executed anywhere.
4. **The full-history secret scan.** Needs a scanner installed.
5. **The end-of-phase gates**, which want the stack from item 3.
6. **The principal's**: **§2**, G29, G30, G31; credits for [#17] — *the same credits*; [#15]'s title.

**[#17]'s remaining arm has been waiting on the same thing.** Five handoffs have listed *"credits for
#17"* beside G32 without either noticing they are one blocker.

---

## 7. Traps

- **T135 — an item carried as a decision may be a blocker nobody tried.** G32 asked the principal to
  choose for seven handoffs. One attempt, costing nothing, established that there was no choice to
  make. **Try the thing before escalating the decision about the thing.**
- **T136 — a guard written for the case somebody hit does not cover the class it belongs to.** *No
  key* was handled and reasoned about in a comment; *a key the provider refuses* is the same
  situation and fell through to twelve red assertions. §3.1.
- **T137 — an infrastructure failure inside a behavioural suite reads as a behavioural finding**, and
  it reads that way most strongly at the exact moment the suite is run: just after the change it
  exists to evaluate. §3.
- Carried and re-confirmed: **T132**–**T134**, **T129**–**T131**, **T124**–**T128**, **T89**,
  **T104**/**T118**, **T105**, **T113**, **T116**, **T120**, **T122**, **T69**, **T70**.

---

## 8. Commands

```bash
# The level-3 corpus. Skips with the provider's own reason if it cannot ask the model.
cd backend && OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-) JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests '*LiveReceptionistTest' -PincludeTags=llm
```

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

---

## 9. Confidence

**Certain on the answer.** The provider's response is quoted verbatim from the run:
`insufficient_quota`, `credit_balance_exhausted`. There is no reading of that which is about anything
but billing.

**Certain that the attempt was free.** Every call was refused with `429` before any token was
processed.

**High on the instrument change**, in the direction that was testable: three runs, and the code check
shown to be the load-bearing part.

**Explicitly unproven in the other direction**, and §4 says so: nothing here demonstrates that a live
model failing an assertion still fails, because no live model answered. The structural argument is
exact and it is still an argument. **G39.**

**Unchanged and unhappy on the deployed picture.** Thirteen sessions, no CI.
