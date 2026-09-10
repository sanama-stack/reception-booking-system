# Session handoff — 2026-09-11 — The rate, and the experiment that could not have worked

> **Purpose.** A focused measurement session, immediately after [the one that filed #17][prev]. It
> wrote no product code. §2 is what was measured; **§3 is the two claims of my own it retracted**;
> §4 is the defect CI found that nobody was looking for; §6 is every open item.
>
> **[#17] has a rate: 5 of 47 writes land on the wrong date — 10.6%, exact 95% CI [3.5%, 23.1%].**
> And `date_from` fully determines it: 42 of 42 trials that searched the named date landed on it,
> 0 of 8 that did not. §2.1.
>
> **#15 and #17 compound.** Same target, same appointment, only the wording changed: 89.4% correct
> when the customer reads out an ISO date, **44.8%** when they say "the Monday after next". Fisher
> one-sided p = 3.9 × 10⁻⁵. §2.2.
>
> **The first version of that experiment could not have worked**, and the reason is a fact about the
> defect rather than about the model. §2.3. This is the part to carry furthest.
>
> **On the dates.** Local time is `+04`, so the commits carry 2026-09-11 while the clock is still on
> 2026-09-10 in UTC. Continuous with [the previous handoff][prev]; split from it because that one is
> already merged and its §3.5 is now stale.

[prev]: ./2026-09-10-the-gap-that-was-hiding-a-wrong-write.md
[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | current — merged as pull request [#20] |
| `dev` | pushed, even with `main`, CI green on all three jobs |
| Working tree | clean |
| Backend | **804 tests, 0/0/0** — unchanged; the new instrument is `probe`-tagged and excluded from `build` |
| Level 3 | 12 tests, unchanged. **Not run this session** |
| Frontend | untouched; `pnpm build` still unrun (G9) |
| Migrations | V7, unchanged |
| Issues open | **#15 and #17**, both now carrying measurements and cross-references |
| Pull requests | **none open.** [#18] was spam and is closed — §5 |

[#18]: https://github.com/sanama-stack/reception-booking-system/pull/18
[#20]: https://github.com/sanama-stack/reception-booking-system/pull/20

---

## 2. What was measured

The instrument is new: `RescheduleDateFidelityRateTest`, tagged `probe`, a sibling of
`WeekdayResolutionRateTest`. Fifty live conversations per arm, a fresh diary per trial, three
outcomes rather than two — a trial that never writes is not a wrong write and is kept out of the
denominator.

### 2.1 #17's rate, and a perfect correlation

| Outcome | n |
|---|---|
| Landed on the named date | 42 |
| Landed on a different date | **5** |
| Never wrote | 3 |
| Errored | 0 |

**5 of 47 writes = 10.6%, exact 95% CI [3.5%, 23.1%]** (Clopper–Pearson; the helper was validated
against three published intervals and this project's own recorded Fisher pair before use).

|  | searched the named date | did not |
|---|---|---|
| landed on it | **42** | 0 |
| landed elsewhere | 0 | **5** |
| never wrote | 0 | **3** |

**No exceptions in either direction.** Every failure searched `date_from: tomorrow` with a
seven-day `date_to` that did not contain the named date, then wrote a slot from inside that window.
The defect is entirely upstream of the write.

### 2.2 The second arm — the phrasing is worth more than the defect

Same harness, same target date, same appointment. **Only the wording changed.**

| How the customer named the date | Landed on it | 95% CI |
|---|---|---|
| `2026-09-21` | 42/47 = **89.4%** | [76.9%, 96.5%] |
| "the Monday after next" | 13/29 = **44.8%** | [26.4%, 64.3%] |
| "the Monday after next", allowing the other reading | 24/40 = 60.0% | [43.3%, 75.1%] |

Fisher one-sided **p = 3.9 × 10⁻⁵** strict, **1.5 × 10⁻³** lenient. The threshold — p < 0.05,
requiring the weekday arm to be roughly three times worse — **was computed and written down before
the second arm ran.**

Upstream the gap is larger still: `date_from` aimed exactly at the target in **84%** of ISO trials
against **20%** of weekday trials, and the weekday arm failed to write at all four times as often.
Its landings scatter across seven dates: `09-21` ×13 (right), `09-14` ×11 (the other reading),
**`09-18` ×9 — the last day of the default window** — `09-25` ×3, `09-22` ×2, `09-15` ×1, `09-28` ×1.

**Eleven trials are counted separately, not scored as this defect.** "The Monday after next" is
genuinely ambiguous English and the nearest Monday is a defensible reading. Both framings are
reported because pre-committing to only the strict one would have meant choosing the flattering
number after seeing it — and the conclusion survives the lenient scoring anyway.

### 2.3 The first version of that experiment could not have worked

**Carry this one.** The arm originally had the customer say "Monday". It would have come back
near-perfect and meant nothing.

A bare weekday can only name a date within seven days. Every observed failure has the model
searching `[tomorrow, tomorrow+6]`. So **a bare weekday's target is always inside the very window
the model wrongly substitutes**, and the failure mode is unreachable by construction. Re-phrased to
"the Monday after next" — ten days out, the same target as the ISO arm — it measures something.

**#17 needs a target more than a week out, which a bare weekday cannot express.** Anyone verifying a
fix needs to know that: a test naming a nearby weekday will not see this defect at any failure rate.

*Ask what makes the defect visible before asking how often it happens.* A fixture that cannot
express the failing input measures the fixture. It now has a section in the probe README beside the
one about saturation.

### 2.4 A third thing, unfiled

`lookup_appointment` returns `service_name` but **no `service_id`**, while `find_available_slots`
requires one. So on **every** reschedule conversation the model composes a fake id — `"1"`,
`"haircut"` — is correctly refused by the hallucination control (*"must be an id you were given by a
previous tool call"*), and only then fetches the real one. **The guard is working exactly as
designed**; the cost is a wasted model-and-tool round trip on 100% of these conversations,
immediately before the step that goes wrong. Returning `service_id` from `lookup_appointment` would
remove it. **Not filed** — it is a design question, not a defect.

---

## 3. Two claims of mine, retracted

Both were in [#17]'s body, written by the previous session. Both are now marked superseded in the
issue rather than deleted, with the original text kept so the reasoning can be checked.

**"The probe may be usable here."** It rested on 2-in-7 ≈ 29%, which would have been comfortably
above the roughly-one-in-ten floor where `probe.py` saturates. Measured, the failure rate is 10.6%
with a lower bound of **3.5%** — *on* the floor, not above it. **It is not established that the
probe can screen this**, and the interval is consistent with it being unable to. There is a second,
independent reason anyway: the probe replays a single turn and this defect needs three.

**"Two failures in seven trials."** A roughly threefold overestimate. 2-of-7 is 28.6% with an
interval **sixty-seven points wide**; the measured value falls inside it, so nothing was
contradicted — it simply said almost nothing about the rate, only that the mode existed. **Fourth
instance of this lesson in this repository, and the first time refusing to quote the small sample
was what turned out to be right.**

---

## 4. The defect CI found that nobody was looking for

`ManageTokenServiceTest.editing_the_signature_invalidates_the_token` went red in CI on a push whose
only content was a `probe`-tagged test and a README — nothing that could have touched it. It had
passed five consecutive runs. **It was flaky one run in sixteen, and had been since phase 07.**

It changed the token's **last** character. base64url of a 32-byte HmacSHA256 is 43 characters, and
43 × 6 = 258 bits carrying 256 bits of signature — so **the final character has only four
significant bits and its low two are discarded on decode.** The sixteen reachable final characters
are `048AEIMQUYcgkosw`, and the test's own rule (swap `'A'` for `'B'`, else use `'A'`) decodes to the
identical byte array whenever the genuine signature ended in `'A'`. On those runs the "tampered"
token was byte-for-byte the real one, `verify` correctly accepted it, and the test failed for being
wrong rather than for finding anything.

Measured over 200 000 random signatures: **6.267%**, against 1/16 = 6.25%.

Now edits the **first** character, every bit of which is significant. Proven rather than assumed:
across 100 000 signatures the first character reaches all 64 alphabet values and the edit is never a
no-op; head-to-head on identical signatures the old rule no-ops 6.24% of the time against the new
rule's **0.000%**. Forty real runs of the fixed test, forty passes.

**The second assertion is the guard whose absence caused this.** It compares the *decoded* signature
rather than the token text, so an edit that changes the spelling without changing the meaning fails
there instead of silently testing nothing.

---

## 5. The spam pull request

[#18] was opened by an outside account five minutes after #17 was filed, with a comment on the issue
claiming a "verified solution". **Closed**, with the reasons on the record:

- `docs/agents/issue-tracker.md` says this repository does not treat pull requests as a request
  surface.
- It added one markdown file at the repository root and **modified no code**. Its proposed
  implementation is a Python string constant; this is a Java/Spring codebase.
- Its diagnosis — the prompt does not forbid date shifting firmly enough, so make the rule sterner —
  **is one this project has already measured and rejected.** The comment block above the seven-day
  list records a sterner rule 11 reaching 19 of 20 where a change to the shape of the data reached
  20 of 20: *"what fixes it is the shape of the data and not the force of the instruction"*.

It also carried a cryptocurrency payout address. **Treated as untrusted input throughout: read, not
acted on.** Worth expecting more of these — the repository is public and the issues are detailed.

---

## 6. Every open item

### 6.1 Issues

| # | State |
|---|---|
| **[#17]** | **OPEN, measured.** 10.6% wrong-date rate; two body claims superseded; the weekday arm and the reachability finding are in comments. Next step is a **fix**, and it now has a cheap place to be screened |
| **[#15]** | **OPEN, unchanged in substance**, plus a cross-reference to #17's compounding result. Still not closable at any affordable sample on its own terms — but see §7.1 |

### 6.2 Gaps

| # | What |
|---|---|
| **G1** | No inline tool activity in the chat panel. Deliberate, needs streaming |
| **G3** | Phase-09 DoD box unticked, by decision. #17's invented policy is fresh evidence against ticking it |
| **G8** | Phase 08's "Any available" is exercised but never **rendered**. Unchanged |
| **G9** | **`pnpm build` has not run** since before phase 09 merged. Unchanged — no frontend file has been touched since |
| **G10** | **NEW: the weekday arm has one run and one phrasing.** 44.8% is a single sample of a single ambiguous phrase; "next Monday week", "a week from Monday" and an explicit "Monday the 21st" are all untested |

### 6.3 Traps

Carried: T1–T16 from the previous handoffs. New here:

| # | Trap |
|---|---|
| **T17** | **A fixture that cannot express the failing input measures the fixture.** §2.3. Check reachability before rate |
| **T18** | **`fisher_one_sided(a, b)` tests whether b is BETTER than a.** Called the other way round it returns p = 1.000 for an arm that is dramatically worse. Validate the direction against the recorded 42/50-vs-48/50 pair every time, not just the magnitude |
| **T19** | **`awk 'length>100'` counts bytes, not characters.** A line with `10⁻⁵` in it is 96 characters and 101 bytes. Measure width in Python before rewrapping prose |
| **T20** | **A green CI run is a sample, not a proof.** §4 passed five times before it failed, and the failure had nothing to do with the change that exposed it. A test that fails on an unrelated push is a flake until proven otherwise — read it before re-running it |

### 6.4 Security

**S1 — the `OPENAI_API_KEY` is unchanged and was used heavily here** (about 110 live conversations
across the smoke runs and both arms). Still the key that was pasted into a chat transcript, still a
known accepted risk, still not rotated. **Do not assume otherwise.**

### 6.5 Carried

Unchanged: the advisory lock's cost is unmeasured; there is no "find my booking" page; the
allow-list sweep only sees paths the fixture produces; `sessionStorage` holds a customer's name and
number; **nothing deletes an `ai_message`**; rate-limit buckets are in memory; `make seed` is a
placeholder; there is no frontend test runner; phases 10 and 11 are unstarted.

---

## 7. Next steps, in order

### P0

1. **Fix #17.** It is the first time in this thread that a fix can be *screened cheaply*: the
   weekday reschedule path sits at 44.8%, nowhere near a ceiling and separable at n = 50, where #15
   at ~5% needs a perfect candidate to show anything. The reverted `date_from` candidate — its diff
   is preserved in #17's body — is aimed at exactly the surface §2.1 shows to be decisive. **Screen
   on the weekday arm, then confirm on the ISO arm, then take it to #15.**

### P1

2. **Re-measure #15 with the candidate that wins**, if one does. §2.2 makes it plausible that one
   fix serves both.
3. **Decide G3.** Unchanged; #17 is new evidence.
4. **Run `pnpm build`** when no dev server holds :3000 (G9).
5. **Consider returning `service_id` from `lookup_appointment`** (§2.4). One field, and it removes a
   wasted round trip from every reschedule conversation.

### P2

6. **More phrasings** (G10). "Monday the 21st" is the interesting one: it names a weekday *and* is
   unambiguous, so it separates "relative reference" from "weekday reference".
7. **Phase 10.** Nothing blocks it.
8. **Retention.** Nothing deletes an `ai_message`.

---

## 8. Commands

```bash
# From backend/. ~13 min for fifty conversations. Read the result out of the XML — Gradle does
# not stream it. PROBE_DATE_STYLE is ISO (default) or WEEKDAY, and both name the SAME date.
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
PROBE_CONVERSATIONS=50 PROBE_DATE_STYLE=WEEKDAY JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./gradlew test -PincludeTags=probe --tests '*RescheduleDateFidelityRateTest' --rerun
```

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
for x in glob.glob('build/test-results/test/*RescheduleDateFidelityRateTest*.xml'):
    print(ET.parse(x).getroot().find('system-out').text)"
```

**Copy the XML out before the next `--rerun` eats it** (T5).

---

## 9. Confidence

**High — #17's rate and the perfect `date_from` correlation.** Fifty trials, the correlation checked
row by row against the raw log rather than inferred from totals.

**High — the compounding result.** Two matched arms, threshold pre-registered, and the conclusion
holds under both the strict and the lenient scoring of the ambiguous phrase.

**High — §2.3's reachability argument.** It is arithmetic on the dates, not a measurement.

**High — the flake diagnosis.** 6.267% measured against a predicted 1/16, and the fix proven by
0 no-ops in 100 000 rather than by forty green runs alone.

**Moderate — that 44.8% characterises weekday phrasing generally.** One run, one phrase, and that
phrase is ambiguous. G10.

**None — anything about a fix.** Nothing was changed in `src/main` this session. No candidate has
been tried against either arm.
