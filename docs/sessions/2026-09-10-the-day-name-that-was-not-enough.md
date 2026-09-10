# Session handoff — 2026-09-10 — The two backend defects, and the harness that measured them

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand; §2 is the one decision the principal made and the two taken here; **§3 is the finding that
> matters most — the fix the issue specified did not work, and how that was established**; §4 is
> every defect, gap and trap; §6 is what a fresh session must not redo.
>
> **Both open backend defects from the phase 09 frontend handoff are filed and fixed.** They were
> B1 and B2 in that document's §4.1, they are now [#13] and [#14], and each shipped with the
> regression the previous handoff said it needed.
>
> **Nothing was pushed.** The principal asked twice for the pull request to wait.

[#13]: https://github.com/sanama-stack/reception-booking-system/issues/13
[#14]: https://github.com/sanama-stack/reception-booking-system/issues/14

---

## 1. Where the project stands

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| `origin/main` | `3efcfd2` — still a phase behind |
| `dev` | **`c767dec`** — two commits added here, working tree clean, **not pushed, five ahead of `origin/dev`** |
| Backend | **801 tests**, 0 failures, 0 errors, **0 skipped** — was 798 |
| Level 3 | **10 tests** now, of which **only the new one was run**. See §4.3, G5 |
| Frontend | **Untouched.** No frontend file was opened this session |
| Migrations | **V7**, unchanged. No migration |
| Issues open | **#13 and #14**, both fixed on `dev`, both closing on merge via `Closes` |
| Phase 09 | Complete, and two of its three unticked boxes are now closed — see §4.3 |

### The one thing that is genuinely new to know

**There is a measurement harness for prompt changes, and it is the reason this session reached a
working fix rather than a plausible one.** It replays the conversation loop outside Gradle against
the real tool JSON, twenty times, for a fraction of a cent — where the level-3 corpus runs each case
once, for about a minute, and answers a coin flip. §3 is what it found; §10.3 is how to rebuild it,
because **it lives in a scratch directory and will be gone.**

---

## 2. Decisions

### 2.1 The principal's — how a tool error carries its field detail (#14)

| Option | Chosen |
|---|---|
| Add a `fields` array to the error object, `[{field, message}]` | **yes** |
| Fold the field detail into `message` as prose | no |
| Leave the boundary alone and validate inside each tool first | no |

The chosen shape widens what `ToolResults`' own Javadoc calls "two shapes and no third", which is
why it was put to the principal rather than patched. It cost one line in the prompt and bought a
structure the model can map back to the argument it sent.

Option 2 was declined because `message` is documented as written for the customer to hear, and an
argument name is not; option 3 because it duplicates rules `CustomerService` owns and would drift
from them.

### 2.2 Taken here — #14 is `ready-for-human`, against the previous handoff's advice

That handoff called both defects small. B2 is small in lines and is not small in kind: it changes
what the model sees on every refusal. It was labelled `ready-for-human` and written as an
options table rather than a patch. The principal chose in one exchange, so the label cost nothing
and the alternative — picking a contract change unasked — would have.

### 2.3 Taken here — the #13 commit was amended rather than corrected by a second

The first commit said the day name fixes the defect. §3 is the discovery that it does not. Since
nothing was pushed, the commit was amended so the log does not carry a claim that the next commit
has to withdraw. **The failed first attempt is not lost** — it is in the code comment, in the commit
body's table, and in the issue thread, which is where a reader looks for *why*.

---

## 3. The finding: the specified fix did not work

**#13 said "appending the day name is a one-line change". It is, and it does not fix the defect.**

With `- Today's date, in this business's timezone: 2026-09-10 (THURSDAY)` in the prompt — confirmed
by dumping what the loop actually sends, not by reading the source — `gpt-4o-mini` went on calling
`find_available_slots` for **2026-09-12** on "next Monday". The same wrong Saturday as the original
incident.

**It is not misreading the line. It is doing arithmetic it is bad at**, and no wording makes a weak
adder into a good one. So the counting was moved into Java: the next seven days are resolved and
listed by name, and rule 11 tells the model to take a named day from that list rather than
calculate one.

| Prompt | "What have you got free next Monday?" |
|---|---|
| Date alone | the incident — searched the Saturday |
| Date + day name | **2 of 5** |
| Date + day name + the seven-day list + the rule | **10 of 10** |

The last row also holds 4 of 4 each for "tomorrow", "Wednesday" and "Saturday".

### 3.1 Two things this cost, and both are traps

**A single live-test result is not evidence.** The corpus test failed **twice in a row** against a
prompt that was measuring 2-in-5. Two reds looked like "the fix does nothing" and meant "the fix is
a coin flip", which is a different problem with a different answer.

**Probe fidelity is what makes a harness predictive.** A simplified two-tool probe scored **5 of 5**
on the very prompt the faithful eight-tool one scored **2 of 5** on. The difference is the real tool
set and `strict: true` — the model fills six arguments at once under a constrained decoder, and that
is where it goes wrong. **Dump the specs from `ToolRegistry.specs()`; never hand-write them.**

---

## 4. Every defect, gap and trap

### 4.1 Fixed here

| # | Severity | Where | What |
|---|---|---|---|
| **B1** / [#13] | **High** | `SystemPromptBuilder` | The prompt stated the date with no day name, and — §3 — needed the resolved week and a rule as well |
| **B2** / [#14] | **Medium** | `ToolRegistry:91` | Tool errors dropped their `fieldErrors`, so `VALIDATION_FAILED` reached the model as "One or more fields are invalid" |

**B2's fix needed no translation layer, which the issue did not assume.** `CreateAppointmentTool`
already passes its own argument names into validation, so `field` is a key the model itself wrote;
and the messages already carry `PhoneAudience.CUSTOMER`, which is what stopped this refusal telling
a customer to change a Settings screen they cannot reach. The `ToolResults` constraint — no id, no
stack frame, no class name — therefore holds with no filtering at all.

Measured, `create_appointment` refusing an unusable phone number:

| | the reply asks the customer for international format |
|---|---|
| Before | **5 of 12** |
| After | **12 of 12** |

The other seven were a vague "there was a problem", which leaves a customer with nothing to act on.

### 4.2 What the measurement does *not* show

**B2's three identical retries did not reproduce.** The incident had `create_appointment` sent three
times with the same bad number; the harness made **one** attempt per trial, before and after. So
what improved and was measured is **the answer the customer gets**, not the model calls saved. The
retry needed the customer re-confirming across turns, which the single-turn harness does not stage.

This is written into the code comment as well, so nobody later reads "12 of 12" as "the retry is
fixed".

### 4.3 Gaps

| # | Where | What, and why it is open |
|---|---|---|
| **G1** | Phase doc, *Frontend* | **No inline tool activity.** Unchanged and still deliberate — needs streaming first |
| **G2** | Phase doc, *Level 3* | **No live cancel or reschedule.** Unchanged. The corpus proves the guard and never the success |
| **G3** | Phase doc, *DoD* | **Closed by #13.** "It never states a slot, price or policy that did not come from a tool" — the attribution defect that made it unticked is fixed and measured. **The box should be ticked when the corpus is re-run** |
| **G4** | Level 3 corpus | **Closed.** The relative-date test exists, failed against the unfixed prompt, and passes |
| **G5** | Level 3 corpus | **NEW, and the first thing to do. The corpus has not been run since the prompt changed.** Rule 3 and rule 11 were added and every rule from 3 down was renumbered. Level 3 exists precisely to evaluate a prompt change, and only the one new test has been run against it |

### 4.4 Checked, and not defects

- **`fields` omitted rather than `[]`.** Deliberate, and asserted: an empty array invites the model
  to conclude nothing in particular was wrong with what it sent. An absent key reads as "no detail".
- **The prompt's rule numbering now runs to 12.** No test asserts a rule *number* — the two prompt
  tests assert substrings of the rule text. Renumbering is safe; rewording those two sentences is
  not, and that is the intended asymmetry.
- **`ToolArguments` failures were already fine.** They throw `VALIDATION_FAILED` with a specific
  message naming the field, and carry no `fieldErrors` to lose. Only the generic-message path — the
  `CustomerService` and `PhoneField` style — was losing anything.

### 4.5 Traps

| # | Trap |
|---|---|
| **T1** | **One live-model result cannot judge a prompt change.** §3.1. Measure with the harness; use the corpus to confirm |
| **T2** | **A probe that simplifies the tool set lies.** §3.1. 5/5 versus 2/5 on the same prompt |
| **T3** | **A green level-3 run proves nothing until `skipped` is checked.** Carried, and still true |
| **T4** | **`strings` on macOS refuses a `.class` file** — it reads it as a fat Mach-O and errors. Use `javap -c -p` to confirm a compiled constant, which is worth doing before blaming a model for a prompt it may not have received |
| **T5** | **The IDE-launched backend does not re-read `.env`.** Carried. Irrelevant to Gradle runs, which is all this session used |

### 4.6 Security

**S1 — the `OPENAI_API_KEY` is still the one that was pasted into a chat transcript.** `.env` has not
been modified since 15:06 on 2026-09-10, an hour before the handoff that flagged it, and the key
still has the real `sk-proj-` shape rather than the placeholder. **The principal was asked and
explicitly decided to skip rotation for now**, so this is a known accepted risk rather than an
oversight. It was used for every measurement in this session.

Hygiene otherwise intact and checked: `.env` untracked, `.env.example` still holds
`sk-local-dev-only-not-a-real-key`, and the full outgoing diff (`origin/dev..dev`) was scanned — the
one `sk-proj-` hit is the previous handoff's own prose *describing* a scan.

---

## 5. What is verified, and how

**Counted, not believed.** `./gradlew build`, exactly as CI runs it: **801 tests, 0 failures, 0
errors, 0 skipped**, parsed from `build/test-results/test/*.xml`.

**Watched failing first.** Both fixes have a regression that was run against the unfixed code and
seen red before the fix went back:

- #13's level-2 test, against the stashed builder. The fix was then restored and `diff`-ed against a
  saved copy to prove the restore was byte-identical.
- #14's `ToolExecutionTest` case, against a `ToolRegistry` with the `fieldErrors` argument removed.

**Measured against a live model**, with the harness of §10.3: the tables in §3 and §4.1.

**Run against a live model**: the new level-3 test, `skipped: 0`, checked in the XML.

**Not verified.** The level-3 corpus as a whole (G5). The frontend, which was not touched. And
`pnpm build`, for the same reason as ever.

---

## 6. What a fresh session must not redo

- **Do not simplify the seven-day list out of the prompt.** It looks like padding and it is the half
  that carries the fix: 2 of 5 without it, 10 of 10 with it. §3.
- **Do not delete or reword rule 11** (take a named day from the list) **or rule 3** (fix the
  argument a refusal names). Each was measured, and each is load-bearing for a defect a customer met.
- **Do not judge a prompt change by one corpus run.** T1. It said "no effect" about a change that
  was working two times in five.
- **Do not hand-write tool JSON for a probe.** T2. Dump `ToolRegistry.specs()` and set `strict:true`.
- **Do not write `fields: []` on a refusal with no per-field detail.** §4.4, and there is a test.
- **Do not re-derive the confirmation card from `reply`, add a tool-activity indicator, pass
  `email={null}`, or put `Button`'s size back to `sm`.** All carried from the previous handoff and
  all still true; no frontend file was touched here.
- **Do not assume the `OPENAI_API_KEY` was rotated.** It was not, deliberately. §4.6.
- **Do not tick G1 or G2 without doing the work.** G3 may be ticked once the corpus is re-run.

---

## 7. Next steps, in order

### P0

1. **Re-run the full level-3 corpus** (G5). The prompt changed twice; the corpus is what evaluates a
   prompt change, and it has not seen either change. Check `skipped`, not `BUILD SUCCESSFUL`.
2. **Push, and open the pull request.** `dev` is five ahead of `origin/dev` and `main` is a phase
   behind. `main` requires branches to be up to date — `gh pr view --json mergeStateStatus` is the
   field that says so. Merging closes #13 and #14 by their commit trailers.

### P1

3. **Tick G3** in the phase document once the corpus is green.
4. **A live cancel and reschedule test** (G2). Unchanged and now the only phase-09 test gap.
5. **Consider committing the probe harness.** §10.3 describes it; it is currently a scratch file that
   will be gone. It is ~80 lines, it needs no key to write, and the next prompt change will want it.
   Not done here because nobody asked for it and it is not a defect fix.

### P2

6. **Phase 10** — calendar, analytics, polish. Nothing blocks it.
7. **Retention.** Unchanged: nothing deletes an `ai_message`, and nothing is meant to yet.

---

## 8. Carried, and still carried

- **The advisory lock's cost under ordinary load is unmeasured.**
- **There is no "find my booking" page.** Phase 10 or 11.
- **The value half of `PublicFieldAllowListTest` still only catches the four planted strings.**
- **The sweep only sees paths the fixture actually produces.**
- **`sessionStorage` holds a transcript containing a customer's name and number.**
- **The prompt is now 12 rules and grew twice in one session.** It is nowhere near
  `MAX_PROMPT_CHARACTERS`, and it is worth noticing that both growths were forced by a model
  limitation rather than a product decision. A stronger model would not need either.

---

## 9. Files, and what changed

Modified — **backend only, six files, +240/−11**:

```
ai/application/SystemPromptBuilder.java   the day name, the resolved seven days, rules 3 and 11
ai/tools/ToolRegistry.java                fieldErrors passed through instead of dropped
ai/tools/ToolResults.java                 error() overload taking List<FieldError>
```

```
ai/application/ConversationLoopTest.java  the prompt dates the week; the prompt explains `fields`
ai/application/LiveReceptionistTest.java  the relative-date case (G4)
ai/tools/ToolExecutionTest.java           a refusal names its argument; no empty `fields` array
```

No migration. No frontend file. No documentation file except this one.

---

## 10. Commands, and what they last returned

### 10.1 The gates

```bash
# From backend/. What CI runs. ~6 minutes.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```
`BUILD SUCCESSFUL`, and counted from the XML: **801 tests, 0 failures, 0 errors, 0 skipped.**

```bash
# Count it rather than reading the banner.
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for x in glob.glob('build/test-results/test/*.xml'):
    r = ET.parse(x).getroot()
    t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors')); s+=int(r.get('skipped'))
print(t,f,e,s)"
```

### 10.2 Level 3

```bash
# From backend/. The whole corpus is ~48s and ten tests. -PincludeTags=llm runs them AND NOTHING ELSE.
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test -PincludeTags=llm
```
Only `--tests '*LiveReceptionistTest.a_spoken_weekday_resolves_to_the_right_day'` was run here:
**1 test, 0 failures, skipped 0.** The corpus as a whole is G5.

### 10.3 The harness — how to rebuild it

Three steps, and none of them needs anything committed:

1. **Dump the real tool specs.** A throwaway test autowiring `ToolRegistry` and printing, for each
   `spec()`, `{"type":"function","function":{"name":…,"description":…,"parameters":spec.parameters(),
   "strict":true}}`. This is what `OpenAiChatModel` sends; **do not hand-write it** (T2).
2. **Dump the real system prompt.** A throwaway test printing
   `model.messagesOnCall(0).get(0).content()` after one scripted turn, so the probe runs against the
   prompt the loop actually builds rather than a paraphrase of it.
3. **Replay the loop in ~60 lines of Python.** `POST /v1/chat/completions` with those messages and
   tools; on a `tool_calls` response, append the assistant message, stub each result — `get_services`
   returns one service, `find_available_slots` returns a slot or `{"empty_reason":"CLOSED"}`,
   `create_appointment` returns the refusal under test — and loop. Assert on the **arguments the
   model sent**, run it ten to twenty times, and print the ratio.

Both dumps are deleted afterwards. Grep the tests for `TEMPORARY` before committing.

### 10.4 The database

```bash
docker exec -e PGPASSWORD=reception reception-postgres-1 psql -U reception -d reception -c '…'
```
There are **three** Postgres containers on this machine; `docker ps | grep postgres | head -1` picks
the wrong one. Not used this session.

---

## 11. Confidence

**High — measured, repeatedly, against a live model.** Both fixes have a ratio rather than an
anecdote behind them, and both ratios come from a harness whose fidelity was itself checked when the
simplified version disagreed with the faithful one.

**High — watched failing.** Both regressions were run against the unfixed code and seen red. The #13
restore was `diff`-ed byte-for-byte after the stash, because "I put it back" is exactly the kind of
claim that is usually true and occasionally not.

**High — counted.** 801/0/0/0, parsed from XML.

**Moderate.** That the two prompt additions do not degrade anything else. Rule 3 and rule 11 were
measured on the cases they exist for; the rest of the corpus has not run since (G5), and the whole
argument for level 3 is that a prompt change is the thing levels 1 and 2 cannot evaluate.

**None.** The frontend, untouched. `pnpm build`, unrun. And the retry half of B2, which §4.2 says
plainly was not reproduced.

---

## 12. The verification tenant

**`Phase 06 Scratch` was not touched.** Nothing was driven in a browser this session; every live
model call ran against Testcontainers or against the API directly from a scratch script, and no
application server was started. The counts the previous handoff recorded still stand.
