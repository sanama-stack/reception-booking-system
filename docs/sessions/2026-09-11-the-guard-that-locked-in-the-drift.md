# Session handoff — 2026-09-11 — The guard that locked in the drift

> **Purpose.** Two halves. The second attempt to **fix** [#17] — a third candidate, rejected, and
> **harmful** like candidate 2 and unlike candidate 1 — and then, once the principal parked the
> issue, **the start of phase 10**.
>
> **Nothing the [#17] half touched survives**: every file it changed under `src/main` is
> byte-for-byte as it was. The product code in this session is phase 10's, and it is in §10.
>
> **§2.3 is the one to carry.** A required `requested_date` on `reschedule_appointment`, refusing
> any write that does not land on it, moved wrong writes from 28% to **44%** — and piled the
> landings onto **2026-09-18, the last row of the seven-day list**, 7 against 18. The guard did not
> catch the drift. It made the model commit to the drift early and then enforced it.
>
> **§4 is the instrumentation gap that makes the mechanism an inference rather than a finding.**
> `requested_date`'s values were never recorded, so *whether the guard ever fired is unknown*. That
> should have been closed before the arm was spent, and it was not.
>
> **§5: [#17] is now deliberately parked.** Three candidates, three rejections, two of them
> harmful, all three with the seven-day clamp implicated. Work moved to phase 10 **by the
> principal's decision**, not because the issue is resolved. It is still an open gap and §5 is
> where it is recorded.
>
> **Phase 10's backend half is [its own handoff][phase10]** — two endpoints, 23 tests, the suite
> at 827, one decision left to the principal. §10 here is only a pointer to it.
>
> Continuous with [the previous handoff][prev]; that one rejected two candidates, this one a third.

[prev]: ./2026-09-11-two-candidates-and-the-bound-that-was-load-bearing.md
[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`a80ffad`** — pull request #21, CI green on all three jobs |
| Working tree | the previous session's documentation and `stats.py`, still uncommitted, plus this handoff |
| Backend | **827 tests** — 804 carried in, 23 added by phase 10's backend half |
| `src/main` | **unchanged.** The candidate was reverted after measurement |
| Issues open | **#15 and #17.** #17 now carries three rejected candidates |
| Live conversations spent | **100** (two arms of 50) |
| Phase 10 | **backend half built and green**: analytics and calendar — [its own handoff][phase10] |

---

## 2. What was measured

One instrument: `RescheduleDateFidelityRateTest`, `PROBE_DATE_STYLE=WEEKDAY`, n=50 per arm, both
arms today. Target 2026-09-21, ten days out. **Friday, so T23 does not apply** — checked before the
run, which is the point of T23.

### 2.1 The endpoint was changed, and fixed before any model call

The previous two candidates were scored on strict landing. **This one could not be**, and the
reason is a property of the candidate rather than a preference: a guard's success mode is
*refusing*, not landing. A guard that eliminated every wrong write while recovering from none
would score zero on strict landing and read as no improvement.

So the primary endpoint was **wrong writes per 50 trials** — the harness's `WRONG` count, with
`OTHER READING` and `NO WRITE` excluded exactly as the rate already excludes them. Fixed in the
pre-registration before the control ran, along with the three ways the result could come out and
what each would mean.

**And it turned out to be the steadier measure.** Wrong writes replicated the previous session's
control, 16/50 against 14/50, p = 0.743. Strict landing did not: 14/30 = 46.7% against today's
22/36 = 61.1%, p = 0.177 but fourteen points apart on the point estimate. That is luck rather than
foresight — the endpoint was chosen mechanically, not because it was expected to be stabler — but
it is why this comparison rests on firmer ground than the previous two.

### 2.2 The control

| | today | previous session |
|---|---|---|
| **wrong writes** | **14/50 = 28.0%** CI [16.2%, 42.5%] | 16/50 = 32% |
| strict landing | 22/36 = 61.1% | 14/30 = 44.8% |
| `date_from` exact | 16/50 = 32% | 11/50 = 22% |
| never wrote | 6/50 = 12% | 8/50 = 16% |

### 2.3 Candidate 3 — the declared-date write guard. Rejected, and harmful

`reschedule_appointment` gained a **required** `requested_date`, declared before `new_starts_at` in
the schema, and refused the write through a recoverable `DATE_MISMATCH` when `new_starts_at` did
not fall on it. The shape was deliberately the one control in this codebase already proven against
its counterfactual — `context.authorized().contains(appointmentId)` — applied one field over: an
unverifiable intent becomes a declared parameter the server can cross-check. The system prompt and
the seven-day list were not touched.

| | control | candidate 3 | |
|---|---|---|---|
| **wrong writes** | 14/50 = 28.0% | **22/50 = 44.0%** CI [30.0%, 58.7%] | Fisher **p = 0.9700**, FAIL |
| strict landing | 22/36 = 61.1% | 18/40 = 45.0% | |
| `date_from` exact | 16/50 = 32% | 11/50 = 22% | |
| window covered | 23/50 = 46% | 18/50 = 36% | |
| never wrote | 6/50 = 12% | **4/50 = 8%** | |

**Not re-scored on another endpoint to rescue it**, per the same pre-registered rule candidate 1
was held to.

**Never-wrote going DOWN is the tell.** A guard working as a safety net catches wrong writes and
converts them into refusals, so never-wrote must climb. It fell. Whatever the new parameter did, it
was not catching wrong writes and holding them.

**The landings have the clamp's signature on it, and this part is post-hoc.** They concentrated on
`2026-09-18` — today+7, **the last row of the seven-day list**:

| landing date | control | candidate 3 |
|---|---|---|
| `2026-09-18` | 7 | **18** — p = 0.0099 |

The reading — an inference, see §4 — is that requiring `requested_date` makes the model commit to a
date **early**, it resolves "the Monday after next" by clamping into the seven-day list's reach,
and the guard then **enforces consistency with the wrong intent**, refusing a 09-21 slot it might
otherwise have drifted into writing correctly. *The guard does not catch the drift; it makes the
model commit to the drift and then holds it there.*

---

## 3. What I got wrong

**I recommended this candidate, and argued it over the two alternatives in the same breath.** The
argument had three legs. Two were sound and the third was the one that mattered.

Sound: that **option 1 was already built** — `FindAvailableSlotsTool` has put `searched_from` and
`searched_to` into every result since phase 09, the string appears nowhere else in the backend, and
#17 happens anyway. Sound: that a confirmation step (option 3) cannot fix a wrong write, because
#17's own transcript has the model announcing the wrong date twice in sentences that read like
success.

**Wrong: that a declared parameter would carry the customer's intent.** I predicted the failure
mode — the model filling `requested_date` with the same wrong date it searched — and put it at
roughly even odds in writing before the run. What I did not predict is that this would be *worse
than a no-op*. A self-consistent hallucination is not inert: it converts a soft error the model can
still drift out of into a hard constraint that holds it in place.

**T25 — a guard is only as good as the intent it is given.** Cross-checking two model-authored
fields against each other proves they agree, not that either is right; and where they agree on the
wrong answer, the check enforces it.

---

## 4. The gap that makes §2.3's mechanism an inference

`requested_date`'s actual values were **never recorded**. The harness logs `find_available_slots`
arguments only, and the Testcontainer is gone. So:

- **Whether the guard ever fired is unknown.** No `DATE_MISMATCH` count exists.
- The mechanism in §2.3 is the best available reading of the landing shift, **not a measurement of
  it**. It is labelled post-hoc above and should not be quoted as a finding.

This should have been closed before the arm was spent. Closing it needs a counter on the refusal
and the declared value logged per trial — and another fifty conversations, which is exactly the
cost of not having thought about it first. It is the same class of mistake as T22: *count the thing
the mechanism is about, not the thing that is easy to count.*

---

## 5. [#17] is parked, not resolved — and this is the record of that

**Three candidates, three rejections, two of them harmful:**

| | candidate | result |
|---|---|---|
| 1 | an instruction at the constrained-decode point | 44.4% vs 46.7% control, p = 0.666. Null |
| 2 | the dated list extended to fourteen days | never-wrote 16% → 42%. **Harmful** |
| 3 | the declared-date write guard | wrong writes 28% → 44%. **Harmful** |

**All three have the seven-day clamp implicated.** Candidate 1 established the clamp is real by
lifting it and watching wrong landings move from before the target to beyond it. Candidate 2 walked
off the end of a longer list into a Saturday. Candidate 3 piled landings onto the list's last row.

That is now reasonable evidence that **the clamp is the defect's home**, and weak evidence for any
further variant that leaves it in place. It is *not* a conclusion that no fix exists.

**Work moved to phase 10 by decision of the principal, with #17 open.** The rate stands at 10.6% of
writes landing on a date the customer did not name, CI [3.5%, 23.1%] — a silent wrong write, still
shipping. **G3 stays untickable**: phase 09's Definition of Done cannot be honestly closed while
this is open, and nothing in this session changes that.

Cheapest routes back in, when it is picked up again:

1. **Instrument first** (§4) — 50 conversations, fixes nothing, but tells you whether a declared
   parameter is even reaching the tool with a wrong value. Without it candidate 3's mechanism stays
   an inference.
2. **Attack the clamp directly**, now that three arms point at it. Not by lengthening the list —
   candidate 2 measured that — but by changing what the model is asked to do with it.
3. **Rewrite the candidate in #17's body or delete it** — §4 of the previous handoff explains why it
   cannot be screened as written. Still true, still not done.

---

## 6. Every open item

### 6.1 Issues

| # | State |
|---|---|
| **[#17]** | **OPEN, unfixed, deliberately parked.** Rate and mechanism measured. **Three candidates screened and rejected**, two harmful |
| **[#15]** | **OPEN, unchanged.** Not worked this session |

### 6.2 Gaps

Carried unchanged: **G1** (no inline tool activity), **G3** (phase-09 DoD box unticked — #17 is
open, so it stays that way), **G8** ("Any available" never rendered), **G9** (`pnpm build` still
unrun), **G10** (the weekday arm still has one phrasing). New: **G11** — `requested_date` was never
recorded, so candidate 3's mechanism is an inference, §4.

### 6.3 Traps

Carried: T1–T24. New: **T25** — *a guard is only as good as the intent it is given.* §3.

### 6.4 Security

**S1 — the `OPENAI_API_KEY` is unchanged and was used again**: 100 live conversations this session,
on top of the previous two sessions' ~260. Still the key pasted into a chat transcript, still not
rotated, still a known accepted risk.

### 6.5 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; nothing deletes an `ai_message`; rate-limit buckets are in memory;
`make seed` is a placeholder; no frontend test runner; phase 11 unstarted.

---

## 7. Next steps, in order

### P0

1. **Phase 10's frontend half** — `/calendar`, `/analytics`, the dashboard home and the polish
   sweep. The backend half is done and green; [its handoff][phase10] says what it decided, what it
   left, and which of its decisions is still the principal's.

### P1

2. **Return `service_id` from `lookup_appointment`** — still unfiled, still one field, still a
   wasted round trip on 100% of reschedule conversations.
3. **Run `pnpm build`** (G9).

### P2

4. **[#17]**, by one of §5's three routes · **[#15]** · more phrasings (G10) · `ai_message`
   retention.

---

## 8. Commands

```bash
# From backend/. ~12-15 min for fifty conversations. PROBE_DATE_STYLE is ISO or WEEKDAY.
# CHECK THE DAY FIRST (T23): a run on a Monday is not a result.
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
PROBE_CONVERSATIONS=50 PROBE_DATE_STYLE=WEEKDAY JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./gradlew test -PincludeTags=probe --tests '*RescheduleDateFidelityRateTest' --rerun
```

**Copy the XML out before the next `--rerun` eats it** (T5). Both arms were kept that way; every
number in §2 is read from those files. Doing it this session also rescued the *previous* session's
fourteen-day arm, which was still sitting on disk uncopied.

`tools/receptionist-probe/stats.py` was run before use and reproduced all six recorded values (T18).

---

## 9. Confidence

**High — the rejection.** Same-day control, same harness, pre-registered endpoint and threshold,
control replicated the previous session on that endpoint to within four points.

**High — that the candidate is harmful.** Every measure moved against it, and the primary is a
sixteen-point move on a pre-registered endpoint.

**Moderate — the 09-18 concentration is real.** p = 0.0099, but **post-hoc**.

**Low — the mechanism in §2.3.** It is an inference from the landing shift, and §4 says why it
cannot be more than that until `requested_date` is recorded.

**None — that any fix for #17 exists.** Three have failed. That is not proof that none can work.

---

---

## 10. Phase 10, backend half

**Moved to its own handoff: [Phase 10, Calendar and Analytics, backend half][phase10].** Two
endpoints, 23 tests, the suite at 827, no migration — and one decision left to the principal, the
revenue currency.

It is a separate document because it is a separate piece of work, in the way 2026-09-09's five are:
nothing in it depends on anything above, and a session picking up phase 10's frontend half should
not have to read three sections about a rejected prompt candidate to find it.

[phase10]: ./2026-09-11-phase-10-backend.md
