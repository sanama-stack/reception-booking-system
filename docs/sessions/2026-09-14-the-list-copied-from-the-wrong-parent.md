# Session handoff — 2026-09-14 — the list copied from the wrong parent

> **Candidate 6 from [the review][prev1], and the fourth sitting running where the charge named the
> right file and the wrong defect.** The charge: *"`ErrorCode` is declared twice in two languages and
> has already drifted — `UNSUPPORTED_MEDIA_TYPE` is emitted by `JsonOnlyWriteFilter:78` and absent
> from the TS union, and `client.ts:125` casts it in."*
>
> **It is declared three times, and the union was the wrong end to look at.**
> `docs/04-api-overview.md` §3 publishes a table of the same codes. The frontend union is that
> table's contents **in that table's order** — positions 1–4 and 6–28 of the table, with
> `UNAUTHENTICATED` lifted to the end and `INTERNAL_ERROR` and `NETWORK_ERROR` appended. **The
> union's parent is the document, not the enum**, so it inherited what the document was missing.
>
> **The document was missing four things and the review named none of them.** Two codes outright —
> `UNSUPPORTED_MEDIA_TYPE` and `INTERNAL_ERROR`, the second appearing **nowhere in `docs/` at all** —
> and **two wrong statuses**, which is the worse half: `INVALID_CONFIRMATION_CODE` is published `404`
> and ships `401`, `AI_LIMIT_REACHED` is published `429` and ships `409`. Both rows are
> **byte-identical to their phase-01 originals in `aa84b19`**, the same commit T39 caught under
> `07-mvp-scope.md`.
>
> **Every name already agreed.** A parity check on names — the check the charge implies — would have
> gone green over both status drifts. §3.
>
> **The fix the charge implies would have made the product worse.** Narrowing the union at the wire
> replaces a user's only diagnostic with a false one. §4.
>
> **`UNSUPPORTED_MEDIA_TYPE` is genuinely unreachable from this client**, and is added anyway. The
> absence the review found was correct, for a reason the review did not give. §4.

[prev1]: ./2026-09-13-the-handler-the-receptionist-never-reached.md
[prev2]: ./2026-09-14-the-charge-was-never-the-defect.md

---

## 1. Where the project stands

| | |
|---|---|
| `dev` | **`134d6d0`.** Working tree clean |
| **Pushed** | **No. Eight commits local, CI has seen none of them** — including a CI job added two sittings ago that has never run on a runner |
| Backend | **Not run, deliberately.** `compileJava` only — the change there is javadoc. §6.2 |
| Frontend | **Green**: `typecheck`, `lint`, `prettier --check`, **94 tests in 26 files** |
| Checks | `check-docs` **5 of 5**, `check-pipeline` 4 of 4 |
| Production code | **No behaviour change.** One TypeScript expression, same value |
| E2E | **Not run. A seventh sitting** |
| Review | **6 of 7 candidates done.** One remains, Speculative |
| Gaps | **G48** and **G49** still open, both wanting decisions |

---

## 2. Three lists, and which one the union descends from

| | holds | count before | authority? |
|---|---|---|---|
| `ErrorCode.java` | name + `HttpStatus` | 30 | **Yes, by construction** — both error paths write `code.status()`, so what is declared there is what reaches the wire |
| `docs/04-api-overview.md` §3 | name + status + meaning | 28 | No. A published copy |
| `client.ts`'s union | name | 30 | No. A copy of the copy |

The counts hide it: **30 and 30, differing by one in each direction.** The union lacks
`UNSUPPORTED_MEDIA_TYPE` and adds `NETWORK_ERROR` — which is not drift at all, it is what `request`
throws when the fetch itself failed, `status` 0 and no body to read a code from.

**Order is what proved the lineage.** The set difference says "one missing". The declaration order
says where it came from:

```
doc §3:   … INVALID_CREDENTIALS, UNAUTHENTICATED, TOKEN_EXPIRED, … AI_LIMIT_REACHED
union:    … INVALID_CREDENTIALS,                  TOKEN_EXPIRED, … AI_LIMIT_REACHED,
                                                    UNAUTHENTICATED, INTERNAL_ERROR, NETWORK_ERROR
```

Twenty-seven positions in the document's own order, with the one the document later inserted at
position 5 sitting at the end of the union instead. A list copied from the enum would not look like
this. **The union was never the enum's copy, so calling its contents drift pointed the fix at the
wrong file.**

---

## 3. What the document was missing, and why names alone would not have found it

| | published | ships | witness |
|---|---|---|---|
| `UNSUPPORTED_MEDIA_TYPE` | **absent** | 415 | `JsonOnlyWriteFilter` |
| `INTERNAL_ERROR` | **absent from all of `docs/`** | 500 | the fallback on every unhandled 500 |
| `INVALID_CONFIRMATION_CODE` | `404` | **`401`** | `PublicAppointmentAuthorityTest` asserts `UNAUTHORIZED`, since the commit that added the constant |
| `AI_LIMIT_REACHED` | `429` | **`409`** | the constant's own javadoc — *"not transient and retrying will not help"*, and `RATE_LIMITED` is already the 429 |

**Both statuses were wrong while both names were right.** That is the whole argument for comparing
the status column: the check the charge implies — reconcile the two vocabularies — goes green here,
over the two defects that actually reach a caller. It is T176's shape at one remove: *ask what each
side says, not merely whether the same words appear on both.*

### 3.1 How they survived

`git show aa84b19:docs/04-api-overview.md` returns both rows byte-for-byte as they stand today.
They are **phase-01 design intent**. `INVALID_CONFIRMATION_CODE` was implemented in phase 08 and
`AI_LIMIT_REACHED` in phase 09, each with a status the table already contradicted, and neither
implementing commit touched the row. Phase 08 is the sharper one: it **rewrote this document's prose
about the confirmation code**, three sections down, and left the row alone.

`aa84b19` is the same commit an earlier sitting found under `07-mvp-scope.md`, where its Definition
of Done turned out to be a phase-01 scaffold — **T39, a gate is only as strong as the oldest
document it points at.** Same commit, same disease, a different table, and nothing had generalised
the lesson from one document to the other.

### 3.2 And a wrong status cost more than its row

`client.ts`'s `SESSION_IS_OVER` comment justified an explicit list by naming the 401s that must not
be treated as a lapsed session: *"Two other 401s are emphatically not this"*. **There are three.**
The enum has seven 401s: two are session-over, two are the refresh path, and three are neither —
`INVALID_CREDENTIALS`, `MANAGE_TOKEN_INVALID`, and `INVALID_CONFIRMATION_CODE`.

The behaviour was never wrong — the list is explicit, so the third was correctly excluded by not
being in it. **The justification undercounted**, which is candidate 5's shape exactly: a correct
value under a written claim that is false. And §3's table was no use as a second opinion, because it
published the third one as a `404`.

---

## 4. The fix the charge implies, and why it is not the fix

> *"and `client.ts:125` casts it in"* — `code: (problem.code as ErrorCode) ?? 'INTERNAL_ERROR'`

The cast is real and the reading of it is wrong. It does not cast `UNSUPPORTED_MEDIA_TYPE` in; **it
casts everything in.** Any string the server sends becomes a well-typed member, which is why the
union could never have reported its own drift from either direction.

The obvious repair is to narrow at the boundary: keep a runtime list, check the wire value against
it, fall back to a known member. **Following the value to the screen says no.** `ApiError.code` is
rendered — `ErrorState` prints it in monospace under the message, on every `ResourceGate` in the
dashboard — so it is the one diagnostic a user can read back to whoever is helping them. Mapping an
unheard-of code onto a known one would replace a true clue with a false one, and would do it exactly
when something new has gone wrong. **The narrowing that makes the type honest makes the product
worse**, so the value is still passed through verbatim and the assertion now says why it is an
assertion.

What did change at that line: `(problem.code ?? 'INTERNAL_ERROR') as ErrorCode`. `problem.code` is
`string | undefined`, and casting first told the type checker the value was non-nullable — so the
`??` beside it was **dead code to the checker and live at runtime**. Same value, either way.

### 4.1 The absence was correct, for a reason nobody had given

`UNSUPPORTED_MEDIA_TYPE` **cannot be provoked from this client**, established by complete
enumeration rather than by argument: `JsonOnlyWriteFilter` refuses exactly the three content types
an HTML form's `enctype` can produce, and `frontend/src` contains exactly **two** `fetch` calls, both
sending `application/json` or no `Content-Type` at all.

It is in the union anyway. Membership is the server's vocabulary, not a reachability claim — the
alternative is a list whose contents depend on a per-code argument nothing can check. That is
written on the union now, so the next reader does not delete it as unused.

---

## 5. Every plant, and what caught it

The check is a fifth in `docs/tools/consistency/check.py`, beside the ADR and migration inventories
it is modelled on.

| | what was broken | what caught it |
|---|---|---|
| **the real one** | both files reverted to the tree as it stood | **5 of 30**, naming all five real defects and nothing else |
| A | a phantom row in §3's table | 1 — *declared nowhere in `ErrorCode.java`* |
| B | a phantom member in the union | 1 — *neither a server code nor declared `CLIENT_ONLY`* |
| C | `HttpStatus.I_AM_A_TEAPOT` on a constant | 3, the first being *unknown to this check* |

**The first row is the one that matters.** Run against the pre-fix tree the check reports exactly
the five defects and no others — four of which the review did not name, and the fifth of which was
its whole charge.

**Plant C is the honesty check.** A status the script cannot score does not pass and does not crash:
it reports, and the constant drops out of the enum set, so the two downstream comparisons fail too.
An empty population already fails rather than passing, which is this file's existing rule.

---

## 6. What I got wrong

**6.1 An overclaim, caught before it was committed.** I wrote into `client.ts` that its 401 comment
*"counted two for as long as it has existed"*. It did not: `fd0b339` is an ancestor of `cfe36e8`, so
the comment was **correct when written** and phase 08 falsified it the next day. The corrected line
says that, and says the more useful thing — nothing links a new enum constant to a sentence in
another language that counts them. Caught by `git merge-base --is-ancestor`, not by thinking harder.

**6.2 I clobbered my own edit with a counterfactual's revert.** Plant C modified `ErrorCode.java`,
which I had already edited; reverting the plant with `git checkout HEAD --` discarded the javadoc
fix along with it. Caught by reading `git status` afterwards rather than assuming the revert was
surgical. **T186.**

**6.3 The backend suite was not run and is not claimed.** Two `pnpm dev`, a `next dev`, an
IDE-launched backend and **load average 271** — the condition [the previous handoff][prev2] names as
having produced a false red on `ConcurrentPollerTest`. The backend change is a javadoc comment and
`compileJava` executed it (`1 actionable task: 1 executed`, not up-to-date). A suite run under that
load would have been evidence of the load, not of the change.

---

## 7. The lessons, T182–T186

| | |
|---|---|
| **T182** | **A copied list's parent is not always the thing it describes.** The set difference said "one missing"; the *declaration order* said the union descends from the published table, not from the enum. Establish lineage before deciding which end drifted — the answer changes which file the fix belongs in |
| **T183** | A parity check over names goes green on a drift in the column beside them. Both defects here were statuses under names that already agreed |
| **T184** | T39 again, a different table: **a document that was the design's output becomes the implementation's fiction unless something compares them.** Two implementing phases edited prose *around* these rows and never the rows |
| **T185** | **Follow the value to the screen before narrowing a boundary type.** The honest-looking repair — validate at the wire, fall back to a known member — would have replaced the only diagnostic a user can read back with a false one |
| **T186** | Planting a defect in a file you have already edited and reverting with `git checkout HEAD --` discards your work with the plant. Save the fixed copy first, restore from it, and read `git status` before believing the revert |

---

## 8. Next steps, in order

1. **Push.** Eight commits now, CI has seen none of them, and one of them adds a job that has never
   run on a runner. Still the only step that can fail in a way nothing local has tested.
2. **Run the backend suite with the dev servers stopped**, once, to put a number against `dev`
   again. Nothing here should move it; that is the point of running it.
3. **`make e2e`**, or carry the risk explicitly into an eighth sitting — the filter button outside
   the `overflow-x-auto` wrapper on `/conversations` at 360 px.
4. **Use the probe.** Four sittings now with **no model measured at all**, on a product whose centre
   is a model. `fixtures/loop.json` needs a re-dump first and the probe refuses to start without it.
5. **Candidate 7**, the last one, and Speculative: reads have `use-resource`, writes have twelve
   hand-rolled copies, and the coverage gate keys on the read module's name so it cannot see them.
6. **G48 and G49.** Both still decisions, not patches.

---

## 9. Confidence

**High** that the four documentation defects were real and are fixed: each has a witness that is not
the document — a test for `INVALID_CONFIRMATION_CODE`, the constant's own argument for
`AI_LIMIT_REACHED`, and the enum itself for the two absences. The check reports exactly those five
against the pre-fix tree and nothing against the fixed one.

**High** that no behaviour changed. One TypeScript expression, same value on every input; the
backend change is a comment.

**Moderate** about the check's reach. It compares three lists that exist today. It does not know
about a fourth, and it cannot tell whether a documented *meaning* is still true — only the name and
the status.

**Low**, again, about anything live. No model, no E2E, no runner, and the backend suite deliberately
not run. Four green sittings in a row looks like this either way.
