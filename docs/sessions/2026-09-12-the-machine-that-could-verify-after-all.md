# Session handoff — 2026-09-12 — the machine that could verify after all

> **Purpose.** `ai_message` retention is built, and it is the oldest carried item in the project —
> the only open one touching data the system keeps about real people. §2 is the purge; §3 is the
> decision the principal made and the screen it changed; §4 is the part worth keeping, which is
> that **[the previous handoff][previous]'s §9.1 was wrong about this machine** and the whole
> session ran locally as a result.
>
> **Three commits on `dev`, not pushed. CI has not seen any of this.** Everything below is read off
> a local run.

[previous]: ./2026-09-12-the-audit-that-found-nothing-in-the-file.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`1db8a49`** — unmoved |
| `dev` | **`9b69b67`**, three commits ahead of `origin/dev`, **unpushed**. Tree clean |
| Backend | **937 tests, 0 failed, 102 classes** — the full suite, run here in 5m42s |
| Frontend | **23 files, 80 tests** — was 22/76. One new file, four new tests |
| Migrations | **`V10__ai_message_retention.sql`.** New ADR: none |
| Issues | [#17] and [#15] open, untouched. **No model was called** |
| Phase 11 | **46 boxes ticked, 26 open.** Three ticked here; the Security section is down to five rows |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

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

## 5. Five traps

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

---

## 6. Every open item

### 6.1 Not pushed

**Three commits sit on `dev` and CI has not seen them.** The compose smoke test and the E2E leg are
the two things a local suite does not cover, and the E2E reads `/conversations/{id}` — which now
carries a new field. Nothing suggests it breaks; nothing has run it either.

### 6.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**.

**G26 gains a third instance, from this session's own work.** `AI_RETENTION_ENABLED=false` turns
the purge off and **nothing anywhere would say so** — no health check, no startup warning, no test.
[deployment.md](../deployment.md) names it in prose, which is exactly the shape G26 is about: a
documented control with nothing checking it holds. It is named rather than fixed, because the fix
is a decision about where such a check belongs.

**No gap is closed here.**

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

1. **Push and open the PR.** Nothing else in this session is unfinished, and the two jobs a local
   run cannot stand in for are the compose smoke test and E2E.
2. **The final `/docs` consistency pass**, which closes phase 11's Documentation section. Its
   inputs were G26 and §3.2 of [the previous handoff][previous]; **G26 now has a third instance**
   (§6.2) and the `06-security.md` §12 mismatch still needs a decision before it needs an edit.
3. **The security block**: rate limits per public endpoint, log redaction, the `prod`
   default-secret refusal test, the full-history secret scan, error-response leakage.
4. **Observability**, all four rows — and the health-endpoint row that [the previous
   handoff][previous] §7 deliberately left unticked because nothing tests it.
5. **The principal's**: credits for [#17]'s remaining arm; [#15]'s title.

---

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

---

## 9. Confidence

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

**None — that CI is green on it.** Not pushed.
