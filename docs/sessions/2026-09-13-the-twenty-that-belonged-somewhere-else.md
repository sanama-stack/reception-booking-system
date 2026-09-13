# Session handoff — 2026-09-13 — the twenty that belonged somewhere else

> **Purpose.** **G28**, named two sessions ago and deferred twice: *the §5 limits table and
> `RateLimitProperties` agree by hand.* Coverage has been derived since phase 11 — a public endpoint
> no policy mentions fails the build — but **the numbers were not**. Change `refresh` to ten an hour
> and §5 went on saying sixty, with nothing failing.
>
> **It found the drift it was written to prevent, and that is the whole of the finding.** Phase 09
> added two chat policies. §5 gained **one** row:
>
> > `| POST …/chat | 20 / hour / conversation, 60 / hour / IP | Paid calls |`
>
> The sixty is right. **The twenty belongs to `public-chat-session` — a policy with no row at all —
> and was filed against a mechanism that has no hourly limit.** The conversation ceilings are five
> tool calls a turn, forty messages and a twenty-message window, and they live in
> `ConversationLimits`, not in `RateLimitFilter`. One limit undocumented, one number under the wrong
> control, **and four sessions of this walk read past it** — including the two that rewrote the rows
> above and below it.
>
> **The change a reader will notice** is that §5's endpoint column no longer says `GET …/availability`.
> Abbreviated paths are readable and unreconcilable; they are now the patterns the code declares,
> which is what makes the check possible at all.
>
> **A test, not a seventh `make check-*` target** — §8.5's question, answered the way the last six
> sessions have answered it.
>
> **Committed, not pushed.** One commit plus this file, leaving `dev` **31 ahead of `origin/main`**
> and 29 ahead of `origin/dev`. 1036 backend tests, 0 failed. Phase 11 still at **62 of 72**.

[prev]: ./2026-09-13-the-sweep-that-rejected-the-fix.md
[previous]: ./2026-09-13-the-sweep-that-rejected-the-fix.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. Still no pull request |
| `dev` | **31 ahead of `origin/main`**, 29 ahead of `origin/dev`. The work is **`c456826`** |
| CI | **has still seen none of it.** Nine sessions |
| Backend | **1036 tests, 0 failed, 118 classes** — was 1033 / 117 |
| Frontend | **82 tests**, untouched and not run |
| Migrations | **`V10`**, unchanged. No new ADR |
| Issues | [#17] and [#15] open, untouched. **No model was called** — **G32 is still unanswered** |
| Gates | `make check-docs` green. **No new gate**, deliberately — §4 |
| Phase 11 | **62 ticked, 10 open**, unchanged. **G28 closed** |
| E2E stack | **down, and possibly recoverable — see §6.** Not attempted |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

Production code changed in **no file**, for the third session running.

---

## 2. What the table actually said

Sixteen rows against seventeen policies. Fifteen rows were correct. The reconciliation, done by hand
before any code was written:

| | |
|---|---|
| Policies in `RateLimitProperties` | **17** |
| Rows in §5 | **16** |
| Rows correct | **15** |
| Policies with no row | **1** — `public-chat-session`, `POST /public/businesses/*/chat/session`, 20 / hour |
| Rows describing a limit that does not exist | **1** — *"20 / hour / conversation"* |

Both defects are the same row, which is why neither was obvious: the twenty **was** in the table, on
the line above where it belonged, wearing the name of a different mechanism. A reader checking
whether the chat limits were documented would have found a number that matched and moved on. That is
what four sessions did.

### 2.1 Why the number was plausible

`ConversationLimits` really does contain a twenty — the **twenty-message window**. So *"20 / hour /
conversation"* reads like a slightly garbled reference to a real constant, which is the most
expensive kind of wrong: it survives review because the reader recognises it.

The conversation ceilings are now stated where they belong, as five tool calls a turn, forty messages
and a twenty-message window, with a sentence saying plainly that they are not rate limits.

---

## 3. The abbreviated paths, and why they had to go

§5 wrote endpoints as `GET …/availability`, `POST …/appointments`, `POST …/chat`. Readable, and
**impossible to reconcile**: an ellipsis matches everything and nothing, so no test could tell which
policy a row was about.

They now carry the code's own patterns — `GET /public/businesses/*/availability` and the rest. Two
rows also had patterns that were simply *wrong* rather than short: `GET /public/businesses/{slug}*`
where the code says `/public/businesses/**`, and `POST /public/appointments/{id}/*` where it says
`/public/appointments/*/**`.

This is the visible cost of the change and it is worth stating: the table is longer and slightly less
elegant. It is also now the only version of these numbers that cannot quietly stop being true.

---

## 4. No new gate, and the question that is now six sessions old

[Two sessions back][prev] and three before that, the standing question has been whether one more
bespoke `make check-*` target is the answer to every finding. The shape that has held:

> **Derive it in a test where you can; probe the running system where you cannot; read configuration
> only when neither is possible.**

This is squarely the first category. `RateLimitProperties` is a plain object with no Spring
dependency — the truth can be *asked for*. The alternative, teaching `docs/tools/consistency` to
parse `RateLimitProperties.java`, would replace a derivation with a regular expression over Java
source, and would be fragile in the direction that matters: a parse that finds nothing reports
agreement.

The count stands at **six `make check-*` targets and no new one for three sessions.** The shape now
has eight data points and still no counter-example. It remains the principal's to rule on, but at
this point the rule appears to be working.

---

## 5. Every plant

| # | Plant | Result |
|---|---|---|
| 1 | `refresh` changed to ten an hour — **G28's own worked example** | **Red.** *"code says 10 per PT1H, §5 says 60 per PT1H"* |
| 2 | A row added for `POST /auth/reset-password`, a limit nobody implemented | **Red** on the other direction |
| 3 | A policy added in code with no row — **phase 09's drift exactly** | **Red** |
| 4 | The table reformatted so the parse matches nothing | **See below** |

### 5.1 T89, for the fourth time in this walk

Plant 4 stripped the backticks from the endpoint column — the kind of thing a formatter or a tidy-up
does. The parse then matched **zero rows**, and *"§5 documents no limit the code does not enforce"*
**passed**, over an empty table.

Fourth time, same shape, and it is worth writing the general form down now that the walk is over:

> **An assertion that a set is empty is satisfied by an instrument that has stopped seeing
> anything.** "No CORS header", "no tenant's rows leaked", "no request refused", "no undocumented
> limit" — each is true of a healthy system and equally true of a probe that has died.

The control is always the same: assert, in the same run, that the instrument still sees something it
must see. Here that is `the_parse_still_sees_the_table`, which pins two known rows and a floor on the
row count.

---

## 6. The E2E stack may be recoverable, and this was not the session to find out

Checked, not attempted. Recording it because it changes the picture for whoever is next:

- **Docker is running**, and `reception-postgres-1`, `reception-mailpit-1` and `reception-caddy-1`
  are **up** and healthy. It is the application containers that are absent.
- The four-session-old reason is **network, not code**: three `make up-e2e` failures, every one a
  large binary tarball over a phone hotspot. The backend layer is cached; only the frontend install
  remains.
- **The network is currently responsive** — `services.gradle.org` answered in 1.2 s and GitHub in
  0.9 s, and it was `services.gradle.org` that timed out the first time.
- **The link is still the hotspot** (`172.20.10.1`), and the handoff that tore the stack down left an
  explicit instruction: *do not retry on a metered or tethered link.* A failed attempt costs roughly
  fifteen minutes and the principal's mobile data.

So the blocker has moved from "impossible" to "the principal's call about data". Worth one attempt on
any unmetered connection, because it unblocks **eight of the ten** remaining phase-11 items and
`make check-access-log` has still never executed anywhere.

---

## 7. Every open item

### 7.1 Committed, not pushed

**Thirty-one commits, nine sessions, no CI.**

### 7.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**, **G33**, **G35**, **G36**.

**G28 is closed.** §2.

**G32 is unchanged and is the oldest unanswered question in the project.** The system prompt changed
four sessions ago; the level-3 corpus has not been run against it. It has now been listed as the
principal's call in four consecutive handoffs. **If the answer is "not now", that is a fine answer
and should be recorded as a decision rather than carried as a gap** — the cost of leaving it open is
that every handoff repeats it.

### 7.3 Carried

Unchanged from [the previous handoff][prev] §7.3.

### 7.4 The E2E stack

See §6. Materially different from "still down".

---

## 8. Next steps, in order

1. **Push, and open a pull request.** Thirty-one commits, nine sessions. Deferred five times.
2. **Try the E2E stack on an unmetered connection.** §6 — the blocker is now data, not feasibility,
   and it unblocks most of phase 11.
3. **Answer G32**, in either direction.
4. **G33 / G35 / G36**, the remaining derivation-scope gaps. All doable at a keyboard, none urgent.
5. **The full-history secret scan.** Needs a scanner installed.
6. **The end-of-phase gates**, which want the stack from item 2.
7. **The principal's**: **G32**, G30, G31, G33, G35, G36, G29; credits for [#17]; [#15]'s title.

---

## 9. Traps

- **T121 — a documented number that matches a real constant somewhere else is the hardest kind of
  wrong.** *"20 / hour / conversation"* was recognisable, because `ConversationLimits` does contain a
  twenty. Familiarity is what let four readers pass over it. §2.1.
- **T122 — an abbreviation in a table is a promise that cannot be checked.** `GET …/availability`
  cannot be matched to a policy by anything but a human. If a table is worth reconciling, its key
  column has to be exact. §3.
- **T123 — a reformat is a plausible way for a parser-based check to go blind**, and it will not look
  like a test change. Pin what the parse must see. §5.1.
- Carried and re-confirmed: **T89** (fourth time — §5.1 states the general form), **T104**/**T118**,
  **T105**, **T109**, **T113**, **T116**, **T120**, **T69**, **T70**.

---

## 10. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# This session's class. It reads ../docs/06-security.md, so the working directory matters.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.ratelimit.RateLimitTableTest'
```

```bash
# §6: the E2E stack. NOT on a tethered link. The backend layer is cached; the frontend install is
# what failed three times.
make up-e2e
```

---

## 11. Confidence

**High** on the finding. It is arithmetic — seventeen policies against sixteen rows — and the
misattributed twenty was confirmed against `ConversationLimits` rather than inferred from the row's
wording.

**High** on the test. Shown red four ways including G28's own example, and the parse has a positive
control because plant 4 proved it needed one.

**Medium on the reconciliation's reach, and it is worth naming.** It compares **method, pattern,
capacity and window**. It says nothing about *order*, which §5's prose calls significant and
`RateLimitPolicyOrderTest` checks separately — so a table whose rows are shuffled into a misleading
order still passes here. That is deliberate rather than overlooked; mentioning it because the row
order in §5 is now decorative and a future reader may assume otherwise.

**Unchanged and unhappy on the deployed picture.** Nine sessions, no CI, and §6 is the first movement
on that front in four — but it is movement in what is *possible*, not in what has been *done*.
