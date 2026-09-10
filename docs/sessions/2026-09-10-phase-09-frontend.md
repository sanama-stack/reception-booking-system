# Session handoff — 2026-09-10 — The frontend half of phase 09, and the first live model

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand; §2 is the two decisions the principal made; §3 is what the panel does not do and why;
> **§4 is the complete register of every defect, gap and trap found — start there**; §6 is what a
> fresh session must not redo.
>
> **Phase 09 is complete.** The chat panel, the transcript screens and the `aiEnabled` switch all
> ship, and **level 3 ran for the first time in the project's history** — green, against a live
> model, with `skipped: 0`.
>
> **A customer booked entirely by conversation, in a browser, and the row is correct.** That is the
> first Definition-of-Done box this phase has ever been able to tick.

---

## 1. Where the project stands

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| `origin/main` | `3efcfd2` |
| `dev` | **`17e1c37`** — two commits added here, working tree clean, **not pushed** |
| Backend | **798 tests**, unchanged — **no backend file was modified this session** |
| Level 3 | **9 tests, 0 failures, 0 errors, 0 skipped.** First execution ever |
| Frontend gates | lint, typecheck, `format:check` all green. **`pnpm build` not run** — a dev server is up |
| Migrations | **V7**, unchanged. No migration |
| Issues open | Zero at the start, zero now. **B1 and B2 in §4.1 are unfiled and should be** |
| Phase 09 | **Complete.** 68 boxes ticked, 3 left open with reasons stated in the document |

### The one thing that is genuinely new to know

**There is a real `OPENAI_API_KEY` in `.env` now.** The principal pasted it into the chat and asked
for it to be placed; it went into `.env` only, which is gitignored and untracked, and
`.env.example` still holds `sk-local-dev-only-not-a-real-key`. **It was exposed in a transcript and
should be treated as compromised** — the principal was told to rotate it. A future session should
check whether that happened rather than assume it.

The consequence for everything downstream: level 3 is runnable, the happy path is verifiable, and
"not verified because there is no key" is no longer an available excuse for anything.

---

## 2. The two decisions, and what they were made from

### 2.1 The right-hand column widens from 19 rem to 24 rem

| Option | Chosen |
|---|---|
| Widen to `24rem`; the Classic Flow's column drops to ~560 px | **yes** |
| Keep the `19rem` phase 08 reserved | no |
| Chat becomes the primary left column, Classic Flow demoted | no |

304 px is enough for opening hours and was never enough for a transcript — a bubble wraps every
four or five words and the confirmation card inside it is unreadable. Option 3 was declined because
it contradicts the phase-08 comment that made the Classic Flow a first-class column: it is the
fallback for *every* AI failure mode, and a fallback the chat can displace is not one.

### 2.2 Build first, verify the happy path once a key exists

Taken before the key arrived. The degradation paths were verified for real against the running
server with no key at all — which turned out to be the better half of the bargain, because it meant
all four error codes were exercised deliberately rather than incidentally.

---

## 3. What the panel does not do, and why

**There is no inline tool activity, and the phase document asks for some.** This is the one place
the delivered screen departs from the specification, and it is deliberate.

`ChatReply` carries four fields: `reply`, `conversationStatus`, `messagesRemaining`,
`appointmentCreated`. **There is no tool activity on the wire.** A turn is one non-streaming `POST`,
and streaming is explicitly out of scope for this phase. So a panel saying "checking availability…"
would be asserting something about a request it has never seen — which is the same class of mistake
as reading a booking out of prose, and this phase exists partly to not make that mistake.

What it does instead: "Thinking", becoming "Still working — booking takes a moment" after six
seconds. Honest about latency without claiming to know the cause.

**The tool calls are not hidden — they are on the owner's screen.** `/conversations/[id]` renders
every one with its arguments and its result as stored JSON. That is where "it told my customer the
wrong price" is answerable, and B2 in §4.1 is a case of it working.

**If a future phase ships streaming, this is the thing to revisit** — not before, because there is
nothing to render until then.

---

## 4. Every defect and gap, in one register

Twenty-five entries: **2** open backend defects, **9** frontend defects fixed here, **4** gaps left
open on purpose, **3** things checked and found not to be defects, **6** environment traps, and
**1** security item. **Nothing found this session is omitted**, including the ones already fixed and
the ones that turned out to be nothing — a register that lists only open items cannot be checked
against, because a reader cannot tell "not found" from "found and dropped".

Severity is about the customer, not the code: **High** means somebody booking is misled or blocked,
**Medium** means an owner is, **Low** means it is visible but harmless.

### 4.1 Backend — open, not fixed, not filed

Both were found by *using* the finished screens, not by reading code, and both had survived 798
tests.

| # | Severity | Where | What |
|---|---|---|---|
| **B1** | **High** | `SystemPromptBuilder.java:118` | The prompt states the date without its weekday |
| **B2** | **Medium** | `ToolRegistry.java:88` | Tool errors drop their field detail |

**B1 — the prompt states the date without its weekday.** It emits
`- Today's date, in this business's timezone: 2026-09-10`. Its own comment three lines above calls
this *"the single most load-bearing fact in the prompt"*, because every "tomorrow" and "next
Tuesday" resolves against it — and then it gives the model no way to know that 2026-09-10 is a
Thursday.

On the first real conversation ever held with this system, asked *"what times do you have free on
Monday 14 September?"*, the model called `find_available_slots` for **2026-09-12**, a Saturday. It
got a perfectly correct `empty_reason: CLOSED` and told the customer that **Monday** was closed. The
Classic Flow, in the column beside it, was offering **nine times** that day.

The tool was right and the engine was right. Appending the day name is a one-line change. This is
the reason a Definition-of-Done box is unticked (§4.3, G3), and the reason a customer would have
gone elsewhere.

**B2 — tool errors drop their field detail.**

```java
return ToolResults.error(e.code().name(), e.getMessage());
```

The `ApiException`'s `fieldErrors` are discarded. For `VALIDATION_FAILED`, `getMessage()` is the
generic *"One or more fields are invalid."*, so the model learns that something is wrong and never
which thing. Observed: a customer gave the phone number `5550100`, which is not valid. The model
sent `create_appointment`, got the generic refusal, and **retried the identical payload three
times** before asking the customer to re-confirm a number that could never have worked. Three model
calls and three tool calls bought nothing, and the customer was blamed for it.

The HTTP API returns per-field detail on this exact failure. The tool boundary throws it away.

### 4.2 Frontend — found and fixed in this session

Mine, all of them. Recorded rather than quietly repaired because **every one was invisible in the
source and obvious in the product**, which is the argument for driving a screen rather than
reviewing it.

| # | Severity | What | How it was found |
|---|---|---|---|
| **F1** | **High** | Send was **32 px** on the composer. `size="sm"` — opting straight back out of issue #11's decision, on the one public thumb-operated surface it was made for, days after a handoff wrote that this button "inherits 44 px" | Measuring the rendered height at 375 px, not reading the class |
| **F2** | Medium | The "Start a new chat" button in the degradation banner was 32 px, same cause. It appears only when a customer is already stuck | Found by grepping for the other `size="sm"` once F1 surfaced |
| **F3** | Medium | `formatCents(0)` rendered `<$0.01`, which is false. `CostTracker.costCentsFor` rounds **up**, so any turn that reached the provider costs at least a cent — a zero means no model call was ever made | Reading `CostTracker` to check the claim before shipping it |
| **F4** | Low | `"1 rows"`. And it is the *common* row on that screen, not an edge case: a conversation opened and abandoned has exactly one | Reading the rendered table |
| **F5** | Low | The table header said "Messages" while its own cells said "N rows" — the mismatch was mine, introduced in the same file | Re-reading the file after writing it |
| **F6** | Low | The empty-state sentence carried **fifteen spaces** in the middle. A template literal wrapped across two source lines takes its own indentation into the string; HTML collapses it, so it looks fine and is still wrong | Reading `textContent` in the browser, where the `\n` was visible |
| **F7** | Low | `toLocaleString` on token counts tripped the ADR-0003 lint rule. A false positive for the rule's *purpose* — it targets dates — but a true positive for its *reason*: it would have grouped digits by the reader's locale. Replaced with a deterministic `group()` rather than suppressed | `pnpm lint` |
| **F8** | Low | Used `use(params)` on the transcript page where every other dashboard detail screen uses `useParams` | Comparing against `customers/[id]` before committing |
| **F9** | Low | A redundant fragment wrapper around a single child | Reading back the patched file |

F1 is High and not Low: 32 px is below the reliable thumb target on the screen where a stranger
books, and it silently reversed a decision the principal had made in the previous session.

### 4.3 Gaps — open, deliberate, and written into the phase document

These are unticked boxes, not defects. Each says what is missing where it sits, so a future reader
does not mistake it for an oversight.

| # | Where | What, and why it is open |
|---|---|---|
| **G1** | Phase doc, *Frontend* | **No inline tool activity**, which the phase asks for. `ChatReply` carries a reply, a status, a count and an appointment — there is no tool activity on the wire, because a turn is one non-streaming `POST` and streaming is out of scope for this phase. Naming a tool the panel never saw would be asserting a fact about a request it cannot see, which is the same class of mistake as reading a booking out of prose. Revisit **only** if a later phase ships streaming |
| **G2** | Phase doc, *Level 3* | **No live cancel or reschedule.** The corpus covers both only adversarially — a bulk cancel, a guessed id — so it proves the **guard** and never the **success**. Levels 1 and 2 cover the authorised path; no live model has walked it |
| **G3** | Phase doc, *DoD* | **"It never states a slot, price or policy that did not come from a tool" is not established.** B1 is why. Nothing was invented — every value came from a tool — but it was attributed to a date the customer named and the tool never saw, which is indistinguishable from invention to the person reading it |
| **G4** | Level 3 corpus | **There is no relative-date test.** "Next Monday" would have caught B1 and does not exist. Worth adding *with* the B1 fix, so the regression is a test rather than an anecdote |

### 4.4 Checked, and not defects

Recorded so nobody pays to investigate them twice.

- **A `401` in the browser console on the public booking page.** Expected and handled.
  `SessionProvider` wraps the root layout, so every page — public ones included — calls
  `/auth/me` once; a `401` is the "anonymous" answer and `session-context.tsx:44` explicitly
  declines to surface it. Not a leak and not a stray request.
- **The whole right-hand column appearing to vanish** after a turn. A stale screenshot.
  `getBoundingClientRect` showed the panel present at `left: 744, width: 384` — the 24 rem exactly.
  See T3.
- **`Confirmation` rendering three stacked cards inside a 384 px panel.** Looks heavy in the source
  and reads correctly in the product; the panel's own `max-h-[26rem]` scroller contains it.

### 4.5 Environment and harness traps

Not product defects. Each cost real time here.

| # | Trap |
|---|---|
| **T1** | **The Browser pane must be visible.** A hidden pane is 0×0, the page never paints, and `navigate` times out after a full **300 seconds**. It cost exactly that once, before the pane was revealed |
| **T2** | **Synthetic clicks and Enter did not submit the composer**, though the handler was attached and working. `form.requestSubmit()` did. Before concluding a control is broken, prove the handler that way — this is *not* the hydration trap, because the page had hydrated |
| **T3** | **Screenshots lag the DOM** badly enough to show an entire column as missing. Confirm layout with `getBoundingClientRect` before believing a screenshot that looks broken |
| **T4** | **`form_input` sets the DOM value without updating React state**, leaving the submit button disabled and the field looking filled. Use the native value setter plus a bubbling `input` event |
| **T5** | **The IDE-launched backend does not re-read `.env`.** Adding the key changed nothing until the principal restarted it — asserted by sending a turn and getting `AI_UNAVAILABLE` before trusting anything downstream |
| **T6** | **A green level-3 run proves nothing until `skipped` is checked.** `assumeTrue(properties.isConfigured())` makes the whole corpus pass green while running none of it |

### 4.6 Security

**S1 — the `OPENAI_API_KEY` was pasted into a chat transcript.** It was placed in `.env` only,
which is gitignored and untracked; `.env.example` still holds
`sk-local-dev-only-not-a-real-key`, and the staged diff was scanned for `sk-proj-` before both
commits — zero occurrences. **The key should nonetheless be treated as compromised and rotated.**
The principal was told. A future session should *check* whether that happened rather than assume it.


## 5. What is verified, and how

**Measured in the product, in a browser, against a live model:**

- **A booking, end to end by conversation.** Confirmation code `45HJR536`; in the database
  `CONFIRMED`, `source = AI`, `starts_at 2026-09-16 14:45+00`, customer phone normalised to
  `+12025550142`. The `BOOKING_CONFIRMATION` notification is `SENT` and the `REMINDER_24H` is
  `PENDING`, so phase 07's pipeline works from an AI booking and the card's promise was truthful.
- **The hallucination control, against its counterfactual.** `window.fetch` was intercepted for one
  turn: the model's prose was kept — *"I have booked your Consultation. Your confirmation code is
  ZZ999ZZ"* — and `appointmentCreated` was replaced with `null`. **A paragraph rendered and no card
  did**; the count of confirmation codes on the page stayed at 1, the real one. This is the phase's
  most important control and it is now measured rather than argued.
- **`sessionStorage`.** Twelve bubbles including the confirmation card resumed across a reload; a
  second tab opened empty with nothing under `reception.chat.` in either storage; `localStorage`
  untouched.
- **All four degradation codes**, against the running server: `503 AI_UNAVAILABLE` (conversation
  stays `ACTIVE`, question still persisted, cost `0`), `409 AI_LIMIT_REACHED`, `404 NOT_FOUND`, and
  the disabled-business path.
- **The `aiEnabled` switch** round-tripped to the database and back, and the panel disappeared from
  the public page while it was off.
- **Mobile at 375 px**: full width, Classic Flow first, no horizontal scroll, Send 44 px.

**Level 3: 9 tests, 0 failures, 0 errors, 0 skipped.** Counted by parsing the result XML, **not**
read off `BUILD SUCCESSFUL` — and `skipped: 0` is the number that matters, because it is what proves
`assumeTrue(properties.isConfigured())` let them run rather than passing quietly. It runs against
Testcontainers, so the verification tenant was never at risk.

**Not verified.** `pnpm build` — a dev server is running (§6). And the full backend suite was not
re-run, because no backend file was touched.

### 5.1 What verifying caught that reading would not

**Nine defects of mine, and both backend defects, came out of driving the product rather than
reviewing it** — see §4.2 and §4.1. Not one of them was visible in the source: a 32 px button reads
as `size="sm"`, a false cost label reads as a sensible fallback, and a sentence with fifteen spaces
in it reads as ordinary indentation. Each needed the rendered height, the rounding rule, or the
`textContent`.

This is the whole argument for the phase's browser pass, and it is worth restating because the
temptation next phase will be to skip it on the grounds that the gates are green. They were green
for all nine.

## 6. What a fresh session must not redo

- **Do not add a tool-activity indicator to the panel.** §3. There is nothing on the wire to render;
  it needs streaming first, which is out of scope for this phase.
- **Do not render the confirmation card from `reply`.** The whole control is that it comes from
  `appointmentCreated` and nothing else. §5's counterfactual is what proves it, and re-deriving the
  card from prose would silently undo it.
- **Do not pass `email={null}` to `Confirmation` from the chat panel.** It now has three states and
  `undefined` means *not known*. `null` means *they left it blank*, which is a claim the panel
  cannot make — the customer gave their address to the model, not to a form this component can read.
- **Do not put `Button`'s size back to `sm` in the panel.** F1 and F2. That is issue #11's decision
  being reversed on the exact surface it was made for, and it was already made once here by
  accident.
- **Do not assume `.env` and `.env.example` may both be edited.** The key belongs in `.env` only.
- **Do not trust `BUILD SUCCESSFUL` for level 3** — and specifically, do not trust a *green* level-3
  run without checking `skipped`. T6. It passes green while skipping every test when no key is set,
  which is by design and is exactly how "we ran it" could become false.
- **Do not run `pnpm build` while `next dev` is running.** Unchanged, and still true.
- **The Browser pane must be visible.** A hidden pane is 0×0, the page never paints, and `navigate`
  times out after a full 300 seconds. It cost that once here before the pane was revealed.
- **Synthetic clicks on the composer's Send did not submit** in the automation harness, though
  `form.requestSubmit()` did and a real click does. T2. If a future session finds the panel "not
  responding" under automation, that is the harness and not the panel — check with `requestSubmit`
  before hunting a bug that is not there. T3 and T4 are the neighbouring traps.
- **Do not re-investigate the `401` on the public booking page.** §4.4. It is `SessionProvider`
  asking `/auth/me` once from the root layout, and `session-context.tsx:44` deliberately does not
  surface it.
- **Do not tick the three open boxes in the phase document without doing the work.** G1, G2 and G3
  name exactly what is missing in each case.

---

## 7. Next steps, in order

### P0

1. **File B1 and B2** (§4.1). Neither is filed. Both are backend and both are small; B1 is a
   one-line change with a customer-facing consequence, so it is the one to file first.
2. **Push, and open the pull request.** `dev` is two commits ahead of `origin` and `main` is a phase
   behind again. Remember `main` requires branches to be up to date — a green PR still refuses to
   merge while `dev` is `BEHIND`, and `gh pr view --json mergeStateStatus` is the field that says so.
3. **Rotate the `OPENAI_API_KEY`** (S1), and check it was done rather than assuming.

### P1

4. **Fix B1, add G4 in the same commit, then re-run level 3.** The corpus has no relative-date test;
   "next Monday" would have caught B1 and does not exist. Adding it alongside the fix is what makes
   the regression a test rather than an anecdote.
5. **Fix B2.** Passing `fieldErrors` through to the tool result is what stops a model retrying an
   identical bad payload three times.
6. **A live cancel and reschedule test** (G2). The corpus proves the *guard* and never the
   *success*.

### P2

7. **Phase 10** — calendar, analytics, polish. Phase 09 is done and nothing blocks it.
8. **Retention.** Unchanged: nothing deletes an `ai_message`, and nothing is meant to yet.

### Done here, and off the list

**Level 3**, which three handoffs carried as "the largest unverified thing in the project". It has
now run, and it is green.

---

## 8. Carried, and still carried

- **The advisory lock's cost under ordinary load is unmeasured.** Unchanged.
- **There is no "find my booking" page.** Phase 10 or 11.
- **The value half of `PublicFieldAllowListTest` still only catches the four planted strings.**
- **The sweep only sees paths the fixture actually produces.**
- **`sessionStorage` holds a transcript containing a customer's name and number.** Tab-scoped and
  gone when the tab closes, which is the right trade for a resumable conversation — but it is
  personal data in browser storage and worth stating rather than discovering.

---

## 9. Files, and what changed

New:
```
frontend/src/app/book/[slug]/receptionist-panel.tsx        the chat panel
frontend/src/lib/conversations/{types,api,index}.ts        the owner's wire types and paths
frontend/src/app/(dashboard)/conversations/page.tsx        the list
frontend/src/app/(dashboard)/conversations/conversations-screen.tsx
frontend/src/app/(dashboard)/conversations/[id]/page.tsx   the transcript
frontend/src/app/(dashboard)/conversations/[id]/transcript.tsx
frontend/src/app/(dashboard)/settings/faqs/receptionist-switch.tsx   aiEnabled + daily cap
```

Modified:
```
frontend/src/app/book/[slug]/page.tsx           19rem → 24rem; #classic-flow anchor
frontend/src/app/book/[slug]/business-panel.tsx placeholder → the real panel, gated on aiEnabled
frontend/src/app/book/[slug]/confirmation.tsx   email gains a third state; onBookAnother optional
frontend/src/app/(dashboard)/dashboard-shell.tsx        Conversations in the sidebar
frontend/src/app/(dashboard)/settings/faqs/page.tsx     the switch above the FAQs
docs/phases/phase-09-ai-receptionist.md                 68 boxes ticked, 3 left with reasons
```

No backend file was modified. `.env` gained a real key and is untracked.

---

## 10. Commands, and what they last returned

```bash
# Level 3. From backend/. ~48s. The key must be in the environment; the task reads it from .env.
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test -PincludeTags=llm
```
`BUILD SUCCESSFUL in 48s` — and, counted from `build/test-results/test/*.xml`: **9 tests, 0
failures, 0 errors, 0 skipped.** `-PincludeTags=llm` runs them *and nothing else*; without it,
`excludeTags("llm")` applies.

```bash
# Frontend gates. From frontend/. NOT pnpm build — a dev server is running.
pnpm lint && pnpm typecheck && pnpm format:check
```
All three clean.

```bash
# The database, from the repo root. Note the container name.
docker exec -e PGPASSWORD=reception reception-postgres-1 psql -U reception -d reception -c '…'
```
There are **three** Postgres containers on this machine. A `docker ps | grep postgres | head -1`
picks the wrong one.

---

## 11. Confidence

**High — measured against a counterfactual.** §5. The forged reply and the real one were rendered by
the same component; one produced a card and one did not.

**High — measured in the product.** The booking, the resume, the new-tab isolation, the four error
codes, the toggle, and the 44 px composer are all read out of a live browser or a live database
rather than inferred from source.

**High — counted, not believed.** Level 3's 9/0/0/0, parsed from XML, with `skipped` checked
explicitly because that is the number that could have made a green run meaningless.

**Moderate.** That the three unticked boxes are the *only* gaps. The screens were driven through the
paths a customer would take and the ones that fail loudly; a long adversarial session against the
panel itself was not run.

**None — not verified at all.** `pnpm build`, because a dev server is running. And the backend suite,
because no backend file was touched — which is a reason to expect it green, not evidence that it is.

---

## 12. The verification tenant

**`Phase 06 Scratch` was written to, deliberately, and this is the first handoff where that is
true.** Verifying a conversation requires having one.

```
appointments | businesses | ai_conversations | ai_messages | schema_version
          36 |          3 |                5 |          53 |              7
```

Appointments rose from 32 to 36 and the four new ones have `source = AI`. Five conversations and 53
messages exist where there were none. One conversation was forced to `CLOSED` by hand to exercise
the terminal ceiling path — it is the 09:21 row, and it is test data rather than a real ending.

`ai_enabled` was toggled off and back on for `phase-06-scratch`; it is **on**, and
`ai_daily_cost_cap_cents` is untouched at `500`. The other two businesses were not opened.

The backend was restarted by the principal in IntelliJ to pick up the key. The frontend on 9082 was
left running and was not restarted.
