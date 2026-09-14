# Session handoff — 2026-09-14 — the name nothing had to use

> **Candidate 7 from [the review][prev1], the last of the seven and the only one graded
> Speculative.** The charge: *"reads have `use-resource`; writes have twelve hand-rolled copies, and
> the coverage gate keys on the read module's name so it cannot see them."*
>
> **One third of it was exactly right and it was the third that mattered.** The gate discovered
> screens by asking whether a file calls `useResource`. It could not see **four** components that
> read without the hook — two server pages, the Manage Link flow, and the `/auth/me` every dashboard
> screen waits on — and it could not see **twenty-eight** that write. Rule 7 asks every screen for
> empty, loading and error states; half the application was never asked.
>
> **The count was wrong and its direction was wrong.** Not twelve copies but twenty-eight writers —
> and what repeats is not a state machine waiting to be extracted. **The parts that could be shared
> already are**: `week-editor` for the two week grids, `active-toggle` for four bookability screens,
> `ErrorState` for the one refusal that belongs on the page. What is left differs on purpose, in
> six ways, several of them documented where they differ. §4.
>
> **What the copies do agree about is the class attribute** — seventeen occurrences of one
> byte-identical string across sixteen files — and a thing they all drop. §4.1.
>
> **There was a defect underneath, and the gate's blindness is why nobody had met it.** Every screen
> decides what to say by asking whether the cause is an `ApiError`. Twenty-six modules write that
> branch; **login and register answer it with `null`, which renders nothing at all.** The branch was
> reachable: a `2xx` whose body is not JSON threw a bare `SyntaxError` out of the client, and
> `Content-Length` does not close it. §3.
>
> **The suite had watched a read fail eight times and a write fail never.** That is what the new
> ratchet starts at, minus the one taken. §5.

[prev1]: ./2026-09-13-the-handler-the-receptionist-never-reached.md
[prev2]: ./2026-09-14-the-list-copied-from-the-wrong-parent.md

---

## 1. Where the project stands

| | |
|---|---|
| `dev` | **`0ab2c65`.** Two commits this sitting. Working tree clean |
| **Pushed** | **No. Eleven commits local, CI has seen none of them** — including a job added three sittings ago that has never run on a runner |
| Frontend | **Green**: `typecheck`, `lint`, `prettier --check`, **110 tests in 28 files** (from 94 in 26) |
| Backend | **Not touched and not run.** Nothing here reaches it |
| Checks | `check-docs` **5 of 5**, `check-pipeline` **5 of 5** |
| Production code | **One `try`/`catch` in `lib/api/client.ts`.** Everything else is tests and the catalogue they read |
| E2E | **Not run. An eighth sitting** |
| Review | **7 of 7 candidates done.** The review is closed |
| Gaps | **G48** and **G49** still open, both wanting decisions |

```text
4e06039  Reject with an ApiError when a success body cannot be read
0ab2c65  Discover screens from the api surface, not from one hook's name
```

Split in that order deliberately: the second names a test the first adds, so **reverting the gate
leaves the defect fix standing** and not the other way round. The first was verified alone — the
gate's files stashed, 103 tests green — rather than assumed to be independent.

---

## 2. What the gate could not see, counted

Discovery was `/useResource[<(]/` over `.tsx` files. Against the tree as it stood:

| | found | missed |
|---|---|---|
| Components that read | 30 | **4** |
| Components that write | 0 | **28** |

The four reads are not exotic. `app/book/[slug]/page.tsx` and `app/manage/[token]/page.tsx` are
server components that await their data; `manage-flow.tsx` re-reads after a refusal it thought
impossible; and `lib/auth/session-context.tsx` is the `/auth/me` whose `status` every dashboard
screen's first paint depends on. All four are now catalogued — two as `NO_LOAD`, because a component
that never renders without its data owes no loading state, and the session as `ELSEWHERE`, because
it owns the states and `auth-guard.tsx` renders them.

**The extension had already bent something around itself.** `src/test/fixtures.ts` carried a comment
explaining that it was not a `.tsx` *deliberately*, because a fixture matching either pattern "would
be asked to classify itself as a screen". Discovery now skips `src/test/` outright — the suite's own
scaffolding is not a screen — and that comment says so instead.

### 2.1 Why the hook's name was the wrong question

`useResource` is a convention. Nothing requires a component to read through it, and four do not.
What *is* required is the client: every request in the application goes through `api.get`, `.post`,
`.put`, `.patch` or `.delete`, and the domain wrappers in `lib/*/api.ts` are the only things that
call it.

So `test/screens/api-surface.ts` reads the verbs back out of those wrappers — `businessApi.patch` is
a write because the line under it says `api.patch` — and discovery asks about the surface. A new
endpoint wrapper joins it by existing. This is candidate 3's answer applied to the other half of the
repository: derive the surface once, from the thing that cannot be bypassed.

---

## 3. The defect the charge did not reach

`ErrorState` prints the server's code, `ApiError` carries it, and every screen branches on
`cause instanceof ApiError` to decide what to say. **The else-branch is written twenty-six times**
— by twenty-two of the twenty-eight writers, plus the two server pages and the session. All but two
answer it with something a person can see: a toast, an `ApiError` built on the spot, a rethrow into
Next's error boundary. Login and register write `setError(cause instanceof ApiError ? cause : null)`
— and `null` renders nothing.

`classic-flow.tsx` says why everyone assumed the branch was dead:

> *"Nothing reaches here through the client, which wraps even a dead connection as a
> `NETWORK_ERROR`."*

**True about the fetch, false about the parse.** `request` wrapped the `fetch` and left
`response.json()` bare, so a `2xx` carrying an upstream error page or a truncated reply threw a
`SyntaxError` at the caller. The guard above it does not help: `Content-Length` is absent on a
chunked response, and on a `Response` built from a string — checked in node, not assumed.

What that looked like on the most-used write in the product: the spinner stops, the button comes
back, and the page says exactly what it said before the press.

**Fixed at the one place rather than at the twenty-six.** The parse now throws `INTERNAL_ERROR` with a
sentence a person can read, so the promise the callers were already relying on is true.
`lib/api/client.test.ts` is this module's first direct test and asserts all three ways out — a dead
connection, a refusal the server described, a reply that could not be read. Removing the guard turns
two of its cases and the login screen's red.

---

## 4. Why not the hook the charge implies

A `useWrite` would have to cover, from the twenty-eight:

| disposition | screens | why it is not the others |
|---|---|---|
| A banner, plus the field messages | 17 | The form is still on screen and each message belongs beside the field it names |
| A toast | 6 | The row or dialog it was about has gone; there is nothing left to put a banner on |
| Handed to the page it sits in | 2 | `manage/` renders the refusal above the summary and re-resolves the appointment |
| A notice in the transcript | 1 | The Receptionist panel gives the server's sentence, and a restart when it says so |
| The shared `ErrorState` | 1 | Deleting a service is refused as a matter of course, and the refusal says what to do instead |
| Nothing, deliberately | 1 | Signing out ends the local session whether or not the server heard |

Several take two of these for two different failures — `closures-screen` banners an add and toasts a
removal — and two have no `finally` on purpose, because success unmounts them and setting state
afterwards would warn. A hook covering all of that would carry more options than it has callers.

**And the sharable parts are already shared.** `week-editor.tsx` is one write state machine serving
opening hours and working schedules; `active-toggle.tsx` is another serving four bookability
screens; `ErrorState` renders the delete refusal. The charge read a wide surface as a missing
abstraction, and the abstractions that fit were there.

### 4.1 What the copies do agree about

Seventeen occurrences of one byte-identical class string across sixteen files, plus one that differs
by `mt-4`. **Every one of them renders `error.message` and none renders `error.code`** — while
`ErrorState`, on the read path, prints the code in monospace underneath.

[The previous sitting][prev2] declined to narrow the `ErrorCode` union at the wire, and its reason
was that the code "is the one diagnostic a user can read back". That is true of a read and false of
every write, which is where `SLOT_UNAVAILABLE`, `VERSION_CONFLICT` and `SLUG_TAKEN` actually happen.
**Not acted on here** — whether an ordinary owner should see a code beside a sentence written for
them is a judgement, not a defect — but the asymmetry is now written down where the next reader of
that decision will find it.

---

## 5. The ratchet, and what it starts at

`WRITE_SCREENS` says, for each of the twenty-eight, what the user is shown when the write is
refused, and either names the tests that watch it or admits that none does. Prose rather than an
enum: with six dispositions and screens using two apiece, an enum would be wrong or longer than the list
it describes.

**`UNASSERTED_WRITE_FAILURES` began at twenty-eight.** No test in this suite had ever watched a write
fail — eight drive a failing *read*, and the only clicks that submit anything drive a success. It
stands at **27**: `login-form.test.tsx` asserts that a refusal shows the server's sentence, that the
button comes back, and that an unreadable reply still says something.

Lowering it is the work. The pattern to copy is in that file, and `serve({ kind: 'unreadable' })` is
now a harness mode.

---

## 6. Every plant, and what caught it

| | what was broken | what caught it |
|---|---|---|
| **the real one** | nothing | 16 green, and 5 of 5 on the repo's own checks |
| A | a new component that writes, uncatalogued | *lists every one of them* — naming the file |
| B | a catalogued file that does not write | the same check, **and** the ratchet, from both ends |
| C | `assertedIn` pointed at a test that does not render the file | *does not import* — the new check |
| D | a wrapper the derivation cannot read (`export const businessApi: Record<…> = {`) | *calls the client and this gate read no members out of it* |
| E | a read that skips the hook | *lists every one of them* — **the original hole, closed** |

**Plant D is the honesty check**, and it exists because the first version of that assertion would
have passed it. It iterated the declarations found by the same regex it was validating, so a wrapper
the regex could not read declared nothing, and a loop over nothing succeeds. It now asks the other
way round: a module whose source calls the client must yield at least one member. An emptied
derivation agrees with every catalogue there is.

---

## 7. What I got wrong

**7.1 My own discovery regex missed the two files that mattered most.** `api\.post\s*\(` does not
match `api.post<Session>(`, so the first run found 26 writers and not 28 — and the two it dropped
were login and register, the two carrying the defect. Caught by diffing the machine's list against a
census I had built by hand first, not by reading the regex again. The fix is `[<(]`, and the reason
is written beside it.

**7.2 A test failure that was mine, read for a moment as the client's.** `new Response('', { status:
204 })` cannot be constructed — a null-body status may not carry one — so my 204 case failed inside
the stub and surfaced as `NETWORK_ERROR`, which looks exactly like the client mishandling a delete.

**7.3 Nothing here met a browser or a server.** jsdom, a stubbed `fetch`, and the real client on the
path. The unreadable-body case is asserted against a `Response` built to be unreadable; that it is
also what Caddy or an upstream would produce is an argument, not a measurement.

---

## 8. The lessons, T187–T191

| | |
|---|---|
| **T187** | **Discovery by name is only as good as the name being compulsory.** The gate asked for `useResource`, which is a convention nothing enforces, and so asked about the half of the application that happened to follow it. Ask the chokepoint — the thing that cannot be bypassed — and the convention becomes one way in rather than the only one |
| **T188** | **A count in a charge is a hypothesis, and its direction is part of it.** "Twelve copies" was low by more than half *and* pointed at duplication; the parts worth sharing were already shared, and what remained differed on purpose |
| **T189** | **A guarantee two dozen callers branch on is a test, not a comment.** The client's was true about the fetch and false about the parse, and the two callers that trusted it hardest were the two that showed nothing |
| **T190** | **An emptied derivation agrees with every catalogue there is.** A check built on a regex must assert that the regex found something, and asserting it by iterating that same regex's output is the trap wearing the fix's clothes |
| **T191** | **A pointer to a test is read as coverage.** `assertedIn` now has to name a test that actually imports the file, because a stale pointer is worse than none |

---

## 9. Next steps, in order

1. **Push.** Eleven commits now, CI has seen none of them, and one of them adds a job that has never
   run on a runner. Still the only step that can fail in a way nothing local has tested.
2. **Run the backend suite with the dev servers stopped**, once, to put a number against `dev` again.
   Nothing here touches the backend; that is what makes it a clean baseline.
3. **`make e2e`**, or carry the risk explicitly into a ninth sitting — the filter button outside the
   `overflow-x-auto` wrapper on `/conversations` at 360 px.
4. **Use the probe.** Five sittings now with **no model measured at all**, on a product whose centre
   is a model. `fixtures/loop.json` needs a re-dump first and the probe refuses to start without it.
5. **Lower `UNASSERTED_WRITE_FAILURES`.** Twenty-seven, the pattern is in `login-form.test.tsx`, and
   the ones worth taking first are the ones a stranger meets: `classic-flow`, the two `manage/`
   cards, `register-form`.
6. **G48 and G49.** Both still decisions, not patches.
7. **The review is closed.** Seven candidates, six of them charges that named the right file and the
   wrong defect. Whatever comes next does not come from it.

---

## 10. Confidence

**High** that the client defect was real and is fixed. The `SyntaxError` was reproduced before it
was fixed, at two layers — the client's own test and the login screen's — and removing the guard
turns both red.

**High** about the discovery numbers. Four unseen reads and twenty-eight unseen writes are the
machine's count and match a census built by hand before the machine existed; the catalogue and the
scan agree in both directions or the gate fails.

**Moderate** about the catalogue's *prose*. Twenty-eight entries say what each screen shows when a
write fails, and that half is judgement I wrote after reading the handlers — the gate checks that
something is said, not that it is true. Twenty-seven of them are unasserted, which is exactly what
the count admits.

**Low**, again, about anything live. No model, no E2E, no runner, and the backend not run. Five green
sittings in a row looks like this either way.
