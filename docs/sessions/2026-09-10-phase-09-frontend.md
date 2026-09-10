# Session handoff — 2026-09-10 — The frontend half of phase 09, and the first live model

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand; §2 is the two decisions the principal made; §3 is what the panel does not do and why; §4 is
> the two backend defects the finished screens found; §6 is what a fresh session must not redo.
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
| `dev` | `3ae9df1` before this session's commits; **uncommitted at the time of writing** |
| Backend | **798 tests**, unchanged — **no backend file was modified this session** |
| Level 3 | **9 tests, 0 failures, 0 errors, 0 skipped.** First execution ever |
| Frontend gates | lint, typecheck, `format:check` all green. **`pnpm build` not run** — a dev server is up |
| Migrations | **V7**, unchanged. No migration |
| Issues open | Zero at the start. **Two found here and not yet filed** — §4 |
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
wrong price" is answerable, and §4.1 is a case of it working.

**If a future phase ships streaming, this is the thing to revisit** — not before, because there is
nothing to render until then.

---

## 4. Two backend defects, found by driving the finished screens

Neither is fixed. Both are backend, and this session's scope was the frontend. **Both were found by
using the product, not by reading it** — which is worth recording, because both had survived 798
tests.

### 4.1 The system prompt states the date but never the weekday

`SystemPromptBuilder.java:118` emits:

```
- Today's date, in this business's timezone: 2026-09-10
```

Its own comment, three lines above, calls this *"the single most load-bearing fact in the prompt"*
because every "tomorrow" and "next Tuesday" resolves against it. But the model must **derive** that
2026-09-10 is a Thursday in order to resolve "Monday".

On the first real conversation ever held with this system, it did not. Asked *"what times do you
have free on Monday 14 September?"* it called `find_available_slots` for **2026-09-12** — a
Saturday — received a perfectly correct `empty_reason: CLOSED`, and told the customer that Monday
was closed. The Classic Flow, in the column beside it, was offering **nine times** that day.

The tool was right. The engine was right. The model's arithmetic was wrong, and the prompt gave it
no way to check itself. Appending the day name is a one-line change.

**This is why the "never states a slot, price or policy that did not come from a tool" box is
unticked.** Nothing was invented — every value came from a tool — but it was attributed to a date
the customer named and the tool never saw. To the person reading it, that is indistinguishable from
invention.

### 4.2 Tool errors drop their field detail

`ToolRegistry.java:88`:

```java
return ToolResults.error(e.code().name(), e.getMessage());
```

The `ApiException`'s `fieldErrors` are discarded. For `VALIDATION_FAILED`, `getMessage()` is the
generic *"One or more fields are invalid."*, so the model is told that something is wrong and never
which thing.

Observed: a customer gave the phone number `5550100`, which is not a valid number. The model sent
`create_appointment`, got the generic refusal, and **retried the identical payload three times**
before asking the customer to re-confirm a number that could never have worked. The HTTP API
returns per-field detail here; the tool boundary throws it away.

The transcript screen is what made this legible — an obviously-bad `customer_phone` sitting beside
a result that never names it.

---

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

### 5.1 Three defects of mine, caught by verifying rather than by reading

Recorded because each was invisible in the source and obvious in the product:

| | |
|---|---|
| **Send was 32 px** | `size="sm"` on the composer's button — opting straight back out of issue #11's decision, on the one public thumb-operated surface it was made for. The previous handoff had even written that the composer "inherits 44 px". Found by measuring the rendered height at 375 px, not by reading the class |
| **"1 rows"** | No plural. And it is the *common* row on that screen, not an edge case — a conversation opened and abandoned has exactly one |
| **A sentence with fifteen spaces in it** | A template literal wrapped across two source lines carries its own indentation into the string. HTML collapses it, so it looks fine and is still wrong |

---

## 6. What a fresh session must not redo

- **Do not add a tool-activity indicator to the panel.** §3. There is nothing on the wire to render;
  it needs streaming first, which is out of scope for this phase.
- **Do not render the confirmation card from `reply`.** The whole control is that it comes from
  `appointmentCreated` and nothing else. §5's counterfactual is what proves it, and re-deriving the
  card from prose would silently undo it.
- **Do not pass `email={null}` to `Confirmation` from the chat panel.** It now has three states and
  `undefined` means *not known*. `null` means *they left it blank*, which is a claim the panel
  cannot make — the customer gave their address to the model, not to a form this component can read.
- **Do not put `Button`'s size back to `sm` in the panel.** §5.1. That is issue #11's decision being
  reversed on the exact surface it was made for.
- **Do not assume `.env` and `.env.example` may both be edited.** The key belongs in `.env` only.
- **Do not trust `BUILD SUCCESSFUL` for level 3** — and specifically, do not trust a *green* level-3
  run without checking `skipped`. It passes green while skipping every test when no key is set, which
  is by design and is exactly how "we ran it" could become false.
- **Do not run `pnpm build` while `next dev` is running.** Unchanged, and still true.
- **The Browser pane must be visible.** A hidden pane is 0×0, the page never paints, and `navigate`
  times out after a full 300 seconds. It cost that once here before the pane was revealed.
- **Synthetic clicks on the composer's Send did not submit** in the automation harness, though
  `form.requestSubmit()` did and a real click does. If a future session finds the panel "not
  responding" under automation, that is the harness and not the panel — check with `requestSubmit`
  before hunting a bug that is not there.
- **Do not tick the three open boxes in the phase document without doing the work.** Each names what
  is missing: live cancel/reschedule coverage, the weekday in the prompt, and tool activity.

---

## 7. Next steps, in order

### P0

1. **File the two defects in §4.** Neither is filed. Both are small, both are backend, and 4.1 in
   particular is a one-line change with a visible customer-facing consequence.
2. **Commit and open the pull request.** `dev` carries this session's work and `main` is a phase
   behind again.

### P1

3. **The weekday fix (§4.1), then re-run level 3.** The corpus does not currently contain a
   relative-date test — "next Monday" would have caught this and does not exist. Worth adding
   alongside the fix, so the regression is the test rather than the anecdote.
4. **A live cancel and reschedule test.** The corpus proves the *guard* and never the *success*.

### P2

5. **Phase 10** — calendar, analytics, polish. Phase 09 is done and nothing blocks it.
6. **Retention.** Unchanged: nothing deletes an `ai_message`, and nothing is meant to yet.

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
