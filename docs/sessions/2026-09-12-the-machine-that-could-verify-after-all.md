# Session handoff — 2026-09-12 — the machine that could verify after all

> **Purpose.** `ai_message` retention is built, and it is the oldest carried item in the project —
> the only open one touching data the system keeps about real people. §2 is the purge; §3 is the
> decision the principal made and the screen it changed; §4 is the part worth keeping, which is
> that **[the previous handoff][previous]'s §9.1 was wrong about this machine** and the whole
> session ran locally as a result.
>
> **A second piece of work followed, on the principal's decision: §12 of the security document was
> a control no file implemented, and the call was that the file moves.** §9 is that — the compose
> tightening, the measurement that made it more than a guess, and the gate that now holds it.
>
> **§10 is the third piece: phase 11's last Documentation row, done as a target rather than as an
> event — and what it found.**
>
> **§11 is the fourth, and the one to read if you read only one.** Merging it required a
> branch-protection decision, and taking that decision exposed **a fresh instance of G26 that I had
> written myself, in this session, in the file that would have to enforce it.**
>
> **Merged.** Ten authored commits, PR [#34], `main` at **`6d01bf8`**. All five required checks
> green.

[previous]: ./2026-09-12-the-audit-that-found-nothing-in-the-file.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`** — PR [#34] merged. Was `1db8a49` |
| `dev` = `main` | **level**, nothing unpushed, tree clean. `dev` deliberately not deleted — it is long-lived |
| CI | **green on `c3694bf`, five checks, zero failing.** The required set gained a fifth member this session (§11) |
| Backend | **937 tests, 0 failed, 102 classes** — the full suite, run here in 5m42s |
| Frontend | **23 files, 80 tests** — was 22/76. One new file, four new tests |
| Migrations | **`V10__ai_message_retention.sql`.** New ADR: none |
| Issues | [#17] and [#15] open, untouched. **No model was called**; no credit check was made |
| Gates | **two new**: `make check-bindings` (§9) and `make check-docs` (§10). Both run in CI |
| Phase 11 | **47 boxes ticked, 25 open.** Four ticked here, and the **Documentation section is closed** — zero open rows. *Walk 06-security.md* is still open but §12 of it is done and gated (§9) |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#34]: https://github.com/sanama-stack/reception-booking-system/pull/34

---

## 2. The purge

`TranscriptPurgeJob` (hourly timer) drives `TranscriptPurge` (one bounded batch), split for the
reason `NotificationPoller` is split from `NotificationDispatcher`: a `@Scheduled` method calling a
`@Transactional` one on `this` bypasses the proxy and runs with no transaction at all, which would
release the `FOR UPDATE SKIP LOCKED` locks statement by statement and commit the delete separately
from the mark.

**Ninety days was not chosen here.** [05-ai-architecture.md](../05-ai-architecture.md) §11 has
carried it since phase 09 and deferred only the job — *"the purge job itself is V1.1"*. This closes
that sentence. Nothing about the window was open to decide.

### 2.1 The cutoff is the conversation's last activity

Not each message's own `created_at`, and this is the load-bearing choice. Anchored per message, a
conversation straddling the boundary loses its opening turns and keeps the rest — a transcript
beginning mid-sentence, which is exactly the half-scrubbed state phase 11 rejected when it chose
purging over redaction.

The honest statement of the window is therefore **"ninety days after a conversation's last
activity"** rather than "ninety days after a message was written". The two differ by the length of
one conversation, which is capped at forty messages inside a single browsing session.

### 2.2 `messages_purged_at`, and why a nullable column beat an inference

`message_count` is denormalised onto `ai_conversations` and the purge does not decrement it, so
`count > 0 AND no messages` **does** identify a purged conversation. The column exists anyway, for
two reasons that turned out to be the same reason:

- Without it the claim query has no way to exclude what it has already purged, so every run
  re-selects every conversation it has ever emptied, finds nothing to delete, and reports zero — a
  cost that grows with the whole history and is **invisible in the row counts the job logs**. The
  index `ai_conversations_purge_idx` is partial on this column and shrinks as the purge works.
- The screen cannot tell the truth about a state the API cannot name (§3).

### 2.3 What the tests do

`TranscriptRetentionTest`, nine tests. **Every deletion assertion is paired with a survival
assertion**, and `purgeBatch()` returns its own row count, so neither a purge that does nothing nor
one that empties the table can pass.

**Shown red three ways**, each plant reverted and `cmp`-verified byte-identical:

| Plant | Red |
|---|---|
| Anchored on each message's `created_at` instead of the conversation's activity | **2** — the anchor test, and the empty-conversation test |
| `messages_purged_at IS NULL` removed from the claim | **2** — idempotence, and the empty-conversation test |
| `purgeBatch()` made a no-op | **6** |

The three that stay green under a no-op are the three whose job is to assert an *absence*. That is
correct, and it is also the reason the other six exist.

---

## 3. The decision, and the screen it changed

**Put to the principal and answered: messages only, the conversation row stays.** It carries
counters and a cost estimate and no free text, and it is the record of what the Receptionist cost
the business.

What made it more than bookkeeping is what it does to `/conversations/{id}`. The transcript screen
already had an empty state — *"This conversation was opened but nothing was ever said in it"* —
written when that was the only way to reach it. A purged conversation would have rendered that
sentence **directly underneath a Rows fact of 8**. A contradiction, and a lie.

`messagesPurgedAt` is now on the wire and the screen renders the date. **It does not name the
ninety days**, deliberately: that number is enforced only in `ConversationLimits`, and a copy in
TypeScript with nothing checking the two agree is precisely the coupling [the previous
handoff][previous]'s §4.2 spent an audit on. The date is a fact about this conversation; the window
is policy, and the server is the only place it is true.

---

## 4. The previous handoff was wrong about this machine

**This is the reusable part of the session.**

[The previous handoff][previous] §9.1 said retention *"is the first item this machine cannot
verify"* — it needs Java and a migration, the E2E images cannot be rebuilt here (T62), so the
assertion would have to be written first and ticked by CI, and *"that is the whole shape of the
next session rather than a footnote in it"*.

It was a careful piece of reasoning and it was **wrong**, because T62 is about the *containerised
stack* and the backend suite does not use it. The suite uses Testcontainers, which needs
`postgres:16-alpine`, `axllent/mailpit:v1.21` and `testcontainers/ryuk:0.14.0` — and
`docker images` had all three already. Nothing was pulled and nothing was built.

937 tests ran here, including the nine new ones and their three planted counterfactuals. **The plant
loop is the thing that would have been lost**: shown-red-on-demand costs three runs and twenty
seconds locally, and in CI it is three pushes.

**One real constraint was found instead, and it is unrelated.** Gradle 8.14 cannot run on this
machine's default JDK — `java -version` is Temurin **25**, and `./gradlew` fails with a bare
`* What went wrong: 25.0.4.1`, which names no cause at all. JDK 21 is installed. Every Gradle
command in §8 carries an explicit `JAVA_HOME`.

---

## 5. Thirteen traps

**T69 — `./gradlew` on this machine needs `JAVA_HOME` pointed at JDK 21.** The default `java` is
25, Gradle 8.14 does not support it, and the failure prints the version string as though it were
the error message. Nothing about it says "toolchain".

**T70 — a reverted plant makes Gradle report `BUILD SUCCESSFUL in 1s` without running a test.**
Restoring the file restores the task's input hash, so the *previous* successful run is reused and
the up-to-date result is printed as a pass. The last thing that actually executed was the red run.
`--rerun-tasks`, or the green tick is a cached one from before the experiment.

**T71 — "this machine cannot verify X" is a claim about a mechanism, not about a machine.** T62
grounds a real limit on BuildKit and Docker Hub, and §9.1 generalised it to a suite that reaches
neither. Check which mechanism a verification actually needs before inheriting a blanket
impossibility — the cost of being wrong is a whole session run at arm's length.

**T72 — a claim query that joins its children can never see a childless parent.** Found by a plant,
not by design: the message-age variant selected conversations *through* `ai_messages`, so a
conversation that was opened and never spoken in was never claimed, never marked, and would have
sat in the candidate set forever. A cleanup job's hardest case is the row with nothing to clean up.

**T73 — pgjdbc will not bind a `java.time.Instant`.** *"Can't infer the SQL type to use"*, on every
insert at once. The suite's existing fixtures use `java.sql.Timestamp.from(…)`; `OutboxFixture` is
the precedent.

**T74 — Compose APPENDS sequences when it merges overlays, so `ports: []` removes nothing.** It
reads as "this topology publishes nothing" and leaves the base file's mappings exactly where they
were. `!reset` discards the key; `!override` replaces it. **And `!reset` ignores any payload after
it** — `ports: !reset` followed by a list publishes nothing at all, which is how the Mailpit UI
briefly disappeared from the E2E topology here. Both were settled against `docker compose config`
rather than against the documentation, and `make check-bindings` is shown red against the
`ports: []` version specifically, because that is the one that looks right.

**T75 — a port bound to `0.0.0.0` is reachable from the network even when `ufw` denies it.** Docker
writes its own rules in the `DOCKER-USER` chain, consulted below the firewall's. Not a Docker bug:
publishing a port is a request to make it reachable. The default when no host is given is every
interface, which is why the `127.0.0.1:` prefix is the whole content of those lines.

**T76 — one probe cannot tell "bound to loopback" from "not running".** Both refuse. The
measurement in §9 means something only because the *old* binding was answering on the same LAN
address at the same moment. This is T64 again — the rate-limit probe that needed a second real
client — in a different costume, and it will keep recurring: **an assertion that something is
unreachable needs a control that is reachable.**

**T77 — a checker's own false positive looks exactly like a finding, and the tempting fix is to
edit the corpus.** The heading regex required a period after the section number, so `### 5.5 X`
was invisible and a valid reference was reported dangling. **Validate the matcher against known
input before acting on what it says** — and note that a plant proving it *fires* says nothing about
whether it *misfires*. Those are two different controls.

**T78 — a gate over documentation will eventually be tripped by documentation about the gate.** The
consistency tool's README failed the consistency tool, for a broken-link example and a deliberately
dangling reference. The fix belongs in the checker, not in the prose: fenced blocks are stripped now.
A gate that forces people to write worse documentation to keep it quiet is a gate that will be
switched off.

**T79 — a comment asserting a control that is not configured is G26 in the file that would have to
enforce it.** Mine said a CI job "is a required check" when the required set did not contain it
(§11.2). A workflow file cannot see branch protection, a Makefile cannot see a firewall, and a
security document cannot see a compose file — **whenever prose names a control that lives in another
system, the claim is unverifiable from where it is written.** That is the whole of G26, and I
committed a fresh instance of it in the session that filed it.

**T80 — branch protection matches a check by the job's `name:` string, and a rename removes the
requirement silently.** Not an error, not a warning: a green merge on a check that never ran. One
value in two places where only one of them is in the repository — the third such coupling this
project has found.

**T81 — every `dev` → `main` pull request starts `BEHIND`, structurally.** `main` is `strict: true`
and each merge commit is a merge *of* `dev`, so `dev` never contains it. Merge `origin/main` into
`dev` before merging the PR. This is not drift and it will recur every time.

**Carried T1–T68. New: T69–T81.**

---

## 6. Every open item

### 6.1 Nothing is outstanding

**Merged.** Ten authored commits, all five required checks green on `c3694bf`, `main` at `6d01bf8`,
`dev` level with it and deliberately not deleted. The two jobs a local suite could not stand in for
both passed on the real change: the **Compose smoke test**, which brought up the topology whose port
mappings moved and ran `make check-bindings` inside it, and **End-to-end**, which reads
`/conversations/{id}` with its new field and asserts against a Mailpit mapping that is now loopback
and, under `up-all`, the only one Mailpit publishes.

**No pull request is open and no branch is ahead of another.**

### 6.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**.

**G26 gains a third instance, from this session's own work.** `AI_RETENTION_ENABLED=false` turns
the purge off and **nothing anywhere would say so** — no health check, no startup warning, no test.
[deployment.md](../deployment.md) names it in prose, which is exactly the shape G26 is about: a
documented control with nothing checking it holds. It is named rather than fixed, because the fix
is a decision about where such a check belongs.

**G26's second instance is closed, and the gap is not.** §9 implements
[06-security.md](../06-security.md) §12 and puts `make check-bindings` behind it, so that sentence
can no longer drift from the files. **Every other sentence in that document still has nothing
checking it** — which is the gap, and it is what *Walk 06-security.md and verify each control* is
going to keep finding. Two instances are now fixed (headers, bindings) by two bespoke gates. A third
will want the same, and at that point the question is whether the pattern deserves one mechanism.

### 6.3 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage`
holds a customer's name and number; rate-limit buckets are in memory; no key rotation; no backups.

**No longer carried: nothing deletes an `ai_message`.** It had been on that list since phase 09.

### 6.4 Environment

**The E2E stack is still up** on ports 9180–9185, now ~8 hours old. **Its backend image is no
longer faithful to `HEAD`** — this session changed `backend/src` and added a migration, which is
the thing [the previous handoff][previous] §8.5 said would stop being true the moment anyone
touched Java. It has now been touched. That stack's database has no `messages_purged_at`.

---

## 7. Next steps, in order

Nothing from this session is half-finished, no pull request is open, and `dev` and `main` are level.

1. **The security block** — the largest remaining group. Rate limits per public endpoint, the
   log-redaction test, the `prod` default-secret refusal **test** (the guard exists; nothing proves
   it fires), the full-history secret scan, error-response leakage. *Walk 06-security.md and verify
   each control* is the row that contains them, and **§12 of that document is the shape to expect**:
   the walk's job is to find claims no file implements, and it found one on its first section.
2. **Observability**, all four rows — and the *health endpoint covering database and mail* row that
   [the previous handoff][previous] §7 deliberately left unticked because nothing tests it. The code
   was read, not tested; ticking it on a reading is T61.
3. **G26, if it is ever to be closed rather than enumerated.** Three instances are now fixed by three
   bespoke gates — headers, bindings, and a corrected comment — and §11 added a fourth instance that
   no gate could have caught. At some point the question stops being "add another check" and becomes
   whether claims-about-controls deserve one mechanism.
4. **The principal's**: credits for [#17]'s remaining arm; [#15]'s title.

## 8. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# The retention tests alone. --rerun-tasks, or a restored plant reports a cached pass (T70).
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.ai.application.TranscriptRetentionTest'
```

```bash
# The frontend suite. About five seconds, no Docker, no network.
cd frontend && pnpm test
```

```bash
# The documentation's consistency. No containers, no build — a checkout and Python.
make check-docs
```

```bash
# The published bindings, all three topologies. Also no containers.
make check-bindings
```

---

## 9. The compose tightening

**The decision.** [The previous handoff][previous] §3.2 found that
[06-security.md](../06-security.md) §12 claimed the database port is exposed to the host *"only in
the `local` compose profile"* and that **no file implemented any such conditionality**. It named the
mismatch and did not fix it, because fixing it is a decision about which side moves. The principal's
call: **the file moves.** The sentence was not weakened; the control was built.

### 9.1 What changed

- `docker-compose.yml` binds Postgres and both Mailpit ports to `127.0.0.1`. Caddy stays on all
  interfaces, deliberately and now with a comment saying why — it is the origin.
- `docker-compose.apps.yml` removes the database mapping with `!reset` and replaces Mailpit's with
  `!override`, keeping only the UI. In the deployed topology **nothing on the host reaches the
  database at all**; the backend is a container and resolves `postgres:5432`.
- The Mailpit UI stays published, on loopback, in every topology — a developer reads it after
  `make up-all` and the E2E flow asserts every confirmation email against it.

`make migrate`, `make seed` and `make psql` are unaffected: the first two run against the base file
alone, and `psql` goes through `docker compose exec`.

### 9.2 The measurement, and the control that made it mean something

Two stacks side by side on this machine — the one that had been running since before the change,
with the old unqualified binding, and a throwaway project brought up from the new file:

| Probe | Old binding | New binding |
|---|---|---|
| Mailpit UI on the host's **LAN address** | **`200`** — every email the system has ever sent | refused |
| Postgres on the host's **LAN address** | **connection accepted** | refused |
| Both on `127.0.0.1` | reachable | reachable |

**The first column is the one that is easy to skip**, and without it the second proves nothing: a
refusal is equally consistent with the service not running. T76.

### 9.3 `make check-bindings`

The control is asserted rather than described — because a fix without a gate leaves the *next*
such sentence exactly as unguarded, which is G26. It reads `docker compose config`, needs no
containers, runs in under a second, and **prints every published mapping it measured** before
passing.

**All three topologies**, and the third is the T66 lesson repaid: `check-ports` had guarded one
coupling at `make up`, a target CI never invokes, so the shape that deploys never met the gate.
`up`, `up-all` and `up-e2e` all depend on this one, and CI runs it in the Compose smoke test.

**Shown red three ways**, each reverted and `cmp`-verified: an unqualified Postgres mapping, a
loopback-bound Caddy (which would take the origin off the network), and — the useful one —
`ports: []` in the overlay, which *looks* like it removes a mapping and, because Compose appends
sequences, does not.

### 9.4 What it did not do

**`up-all` was not run here** and could not be: it builds both application images, which is T62.
The merged configuration was verified with `docker compose config` for all three topologies and the
loopback behaviour was measured against real containers from the same base file — but *"the
five-container topology still comes up"* is CI's Compose smoke test to prove, not this machine's.

---

## 10. The documentation consistency pass

Phase 11's last Documentation row. **Most of the work is `docs/tools/consistency` rather than the
edits**, because a pass run once is stale the next time somebody renumbers a section. `make
check-docs`, four checks, its own CI job, no containers and no build.

### 10.1 What the checks are, and what they refuse to do

None reads prose, and **none checks a document against itself** — each compares one document against
something capable of contradicting it.

| Check | Population |
|---|---|
| Relative links resolve | 269 |
| `<doc> §N` points at a section that exists | 265 |
| `docs/adr/` matches the list in `docs/agents/domain.md` | 11 |
| `db/migration/` matches the table in `03-data-model.md` | 10 |

**The section check is the one that earns its place.** This repo cites sections *by number from
code* — `application.yml`, `V7__ai.sql` and a dozen Java classes all carry
`docs/06-security.md §N` — so renumbering a document invalidates references living in files its
author never opens. Nothing else would find those.

### 10.2 Every check prints its population, and zero is a failure

Not a flourish. **An anchor-link check was written first and deleted.** It reported that every
anchor link resolved. That was true, and meaningless: this corpus contains **zero** anchor links
across ninety files, because it cross-references by `§N` instead. A check that has quietly lost its
subject reports exactly what a passing check reports — so an empty population now fails.

### 10.3 What the pass actually found

Three things, and the first is the one that matters:

- **`GET /calendar` was absent from [04-api-overview.md](../04-api-overview.md) entirely** — not in
  §1's surface table, not in §5. Phase 10 shipped the endpoint and the document never learned about
  it. Found by diffing the controllers' mappings against the document, which also surfaced
  `/health` — genuinely outside all three surfaces, now named as such with a pointer to phase 01.
- **[02-product-architecture.md](../02-product-architecture.md) §1 contradicted itself in one
  paragraph**: *"only Caddy's is published"* followed immediately by Mailpit's published port, plus
  a `9080`–`9085` published block for a topology that now publishes two ports. §9 made the first
  half true and the rest wrong.
- **[CONTEXT.md](../../CONTEXT.md) defined none of Conversation, Transcript, Business FAQ or
  Outbox.** The design documents use those four about **220 times between them**, three back tables
  that have existed since phase 06 or 07, and `CLAUDE.md` calls that file binding on class, table
  and endpoint names. Established by diffing the schema's table list against the glossary's terms,
  not by taste.

### 10.4 Two false positives, both mine, both worth keeping

**The first heading regex required a period after the number**, so `### 5.5 Configuration…` did not
register and a valid reference into a phase-01 session doc was reported dangling. **The checker was
wrong, not the corpus** — and I nearly edited a correct file to satisfy it. Validating a matcher
against known input is not optional, and the plant that proves it fires is not the same as the
control that proves it does not misfire.

**The tool's own README then failed the tool**, twice: it documented a broken-link example and a
deliberately-dangling section reference. That is the checker working, and the fix was in the
checker — fenced code blocks are now stripped before either scan, because a document explaining
link syntax must be able to show link syntax. Inline code spans are deliberately **not** stripped:
most real references here backtick the filename and leave the number outside, and stripping those
would shrink the population silently.

---

## 11. The merge, and the G26 I wrote myself

**This is the part of the session I would most want a fresh reader to have.**

### 11.1 What blocked the merge, and why that was useful

`main` requires branches to be **up to date** (`strict: true`) and `dev` was `BEHIND` by the merge
commits of PRs #32 and #33 — commits `dev` never contained, because each was a merge *of* `dev`.
`origin/main` was merged into `dev` (clean), all three local gates re-run, and the merge proceeded.

**Every dev→main PR from now on starts `BEHIND` for the same structural reason.** It is not drift and
it is not a mistake; it is what a long-lived branch merged by merge commits looks like under a strict
requirement. Expect the update step.

`enforce_admins` is **false**, so an admin can bypass the required checks. Not used, and worth knowing
exists.

### 11.2 The finding

Adding the docs job to the required set meant reading the actual branch-protection configuration for
the first time. It said:

```
Backend, Frontend, Compose smoke test, End-to-end
```

And the comment I had written above the new job — in this session, two commits earlier — said the job
**"is a required check"**. It was not. A red docs run would have been visible on the pull request and
would have blocked nothing.

**That is G26.** Prose describing a control that nothing implements. Filed in §6.2 of this very
handoff, about `06-security.md` §12, and then committed by me into `.github/workflows/ci.yml` — the
file that would have to enforce the claim and structurally cannot, because a workflow file cannot see
branch protection.

It was found by reading the setting next to the comment. **No gate caught it. `make check-docs` could
not have** — it establishes that what a document points at exists, never that what it claims is true.

### 11.3 What was done about it

Two commits, in this order and deliberately not collapsed:

1. **`7dc3666` corrected the comment to say the job was not required** — while that was still true.
2. The principal decided to make it required; `Documentation consistency` was added to the protection
   set, **verified surgical** by diffing the full protection object before and after (`strict`
   unchanged, `enforce_admins` unchanged, every other key byte-identical); then **`c3694bf` corrected
   the comment again.**

The final comment states the requirement **and names its own weakness**: the requirement lives in
branch protection, no workflow file can assert it, and if the set changes this paragraph is wrong
again with nothing to say so.

### 11.4 The footgun that comment records

**Branch protection matches a check by the job's `name:` string.** Renaming the job does not fail —
it silently removes the requirement, leaving a green merge on a check that never ran. The name is a
contract with a setting that lives somewhere else entirely, and nothing in the repository expresses
the coupling.

That is the same shape as the four duplicated ports [the previous handoff][previous] §4.2 found, and
as `SERVER_PORT` against the hardcoded fallback in `client.ts` that it named unguardable. **A third
instance of one value written in two places where only one of them is in the repository.**

---

## 12. Confidence

**High — that the purge deletes what is past the window and spares what is inside it.** Nine tests,
each deletion paired with a survival, and three planted breakages that each went red naming the
behaviour they broke. This is a measurement, not a reading.

**High — that the job is wired and switchable.** `TranscriptPurgeJob` is a `@Scheduled` bean, off
under the `test` profile by the same mechanism the notification poller uses, and the suite drives
`purgeBatch()` directly.

**High — that the screen tells the two empty states apart.** Four tests, and the discriminating one
was shown red against the screen exactly as it was before this session.

**Medium — that the hourly timer fires in a running application.** Never observed. Every test drives
the batch directly, and the `@Scheduled` annotation's interval has been read rather than watched.
The notification poller's identical arrangement has run in every topology since phase 07, which is
the reason this is medium rather than low.

**Low — that the first run on a database with years of transcripts behaves well.** Bounded at 200
conversations a tick by construction, but the largest batch anything has actually executed here is
three.

**High — that no service but Caddy is published beyond loopback, in all three topologies.** Read
out of `docker compose config` by a gate that prints what it measured, and shown red three times.

**High — that the loopback binding does what it is being relied on to do.** Measured against real
containers on this machine, with the old binding answering on the same address as the control.

**Medium — that `make up` and `make up-all` still come up.** The base topology's images were
started from this file during the measurement and were healthy; the five-container topology was
not run here (§9.4). CI's Compose smoke test is what settles it.

**High — that the documentation's links, section references and two inventories are consistent.**
Four checks over populations of 269, 265, 11 and 10, each shown red against a plant, each failing
if its population ever reaches zero.

**Low — that the documentation is *correct*.** Nothing in §10 reads a sentence. `make check-docs`
establishes that what a document points at exists, and says nothing about whether what it claims is
true. **G26 is exactly that gap, it is open, and both instances of it so far were found by a person
reading prose beside a file.**

**High — that CI is green and the work is merged.** Five required checks on `c3694bf`, zero
failing, read off the API rather than off a notification stream; `mergeStateStatus: CLEAN` and the
head matching `dev` were both confirmed before the merge was issued.

**High — that the branch-protection change was surgical.** The full protection object was captured
before and after and diffed: only `contexts` moved.

**Medium — that the required-checks claim in `ci.yml` stays true.** It is true today. Nothing can
check it from inside the repository, the job's name is the string it depends on, and T79 is the
record of it having been wrong once already.

**Low — that G26 is nearer to closed.** Three instances fixed, and §11 added a fourth from inside
this session. The rate at which this project finds them has not fallen.
