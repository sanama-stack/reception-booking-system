# Session handoff — 2026-09-07 — Session-aware public pages

> **Purpose.** A short session, and a defect fix rather than a phase. §3 is the part that matters
> most: it corrects something phase 02 shipped, and §5 retires a question the phase 02 handoff left
> open.

---

## 1. Where the project stands

Unchanged from the [phase 02 handoff](./2026-09-07-phase-02-authentication.md) except for one
commit. Phase 03 (Business Setup) still has not been started, and `dev` still has not been merged
to `main` — with this session's two commits it is **9 commits ahead**.

| | |
|---|---|
| Working branch | **`dev`** |
| Backend tests | 101, untouched — this session changed no backend code |
| Frontend | Type-checks, lints, formats, and `next build` passes with all five routes static |

```text
112b85f  Make the signed-out surfaces notice that you are signed in
```

---

## 2. What was wrong

Reported from a browser: register, land on `/dashboard`, then remove `/dashboard` from the URL —
and the landing page still offers "Create your business" and "Sign in" to someone who is signed in.

**It was not an authentication failure.** `SessionProvider` wraps every page from the root layout,
`/auth/me` was already being called on the landing page, and it was already answering
*authenticated*. Nothing on the page asked. `app/page.tsx` was a static server component with the
two buttons written into it. `/login` and `/register` had the same gap in reverse: they served
their forms to anyone, including someone already signed in.

Worth keeping in mind when reading a bug report about this app: *the signed-out screen appeared* is
much more often a screen that never asked than a session that failed.

---

## 3. An open redirect that was already there

Consolidating the `?next=` handling turned this up in phase 02's own code. The check was:

```ts
requestedNext?.startsWith('/') && !requestedNext.startsWith('//') ? requestedNext : '/dashboard'
```

`/\evil.example` satisfies both halves. URL resolution then normalises it:

```text
new URL('/\evil.example', 'http://localhost:9080').href  →  'http://evil.example/'
```

which is an off-site redirect out of the sign-in page — the shape credential harvesting uses. It
had been live since the login form was written.

`lib/auth/next-path.ts` rejects `\` alongside `/` in that position now. **If you write another
redirect that reads a caller-supplied path, use `safeNextPath` rather than a fresh check.** A
sanitiser that exists in two places is one that will eventually only be fixed in one, which is why
the login form and the guard now share this one.

It also rejects a `next` pointing back into the auth group. `/login?next=/login` would otherwise
redirect to itself indefinitely, since the guard's whole job is to send a signed-in visitor to
`next`.

---

## 4. What exists now

- `lib/auth/next-path.ts` — `safeNextPath` and `DEFAULT_SIGNED_IN_PATH`, the single definition of
  where a signed-in person belongs.
- `lib/auth/session-pending.tsx` — the pending screen both guards render, so a visitor being handed
  between them does not see it change appearance.
- `app/(auth)/guest-guard.tsx` — `GuestGuard`, the mirror of `AuthGuard`.
- `app/landing-cta.tsx` — the landing page's one session-dependent row.

`AuthGuard`, the login form and the register form were reduced onto these. The commit is a net
**−11 lines of logic** despite adding a guard.

---

## 5. Decisions, and one question retired

### GuestGuard belongs in the layout, not in the forms

It is mounted in `app/(auth)/layout.tsx`. The password-reset and invitation-acceptance screens later
phases add are therefore governed the day they are added, rather than the day someone remembers.
Same instinct as the `@TenantScoped` rule: arrive already governed.

### Not middleware, and not cookie presence — this one loops

The cheaper implementation is edge middleware testing whether the session cookie is present, which
decides before the page renders. It is also the one that breaks. Presence is not validity, so a
stale cookie sends the visitor to the dashboard, whose `AuthGuard` finds them unauthenticated and
sends them back here, which sends them there again. Both guards read `status` from the one
`SessionProvider`, so within a page load they cannot disagree and the loop is not expressible.

**Do not move this to the edge.** The comment saying so is in the file, because this is exactly the
optimisation that looks obviously correct from outside.

### The session is not readable server-side — question retired

The phase 02 handoff left open whether these screens should resolve the session during server
rendering instead, which would remove the pending flicker and put the call-to-action in the HTML.
They should not, and this is settled rather than deferred: a server component cannot set cookies
during render, so it cannot perform the rotating refresh. It would report *anonymous* for anyone
whose 15-minute access token had expired while their refresh token was still valid — and could not
fix it, because fixing it means writing a new cookie. Client-side resolution is the correct
architecture here, not a compromise forced by convenience.

The consequence to accept: **the primary call-to-action is no longer in the server-rendered HTML.**
For the current placeholder landing page that costs nothing. When the marketing surface arrives
(`02-product-architecture.md` §7 puts it in a `(marketing)` group), that page wants its CTA
prerendered — and it can have it, because a marketing CTA does not need to know who is signed in.
Keep the session-dependent part to its own row, as `LandingCta` does.

### A pending state, not a guess

While `status` is `loading`, the guards show `SessionPending` and the landing page a same-height
skeleton, rather than the signed-out screen. Guessing "anonymous" is right most of the time and
wrong in the way that reads as a bug — the button changes under the cursor. This is
`02-product-architecture.md` §7's "loading states are part of a screen's definition of done", applied
to the one piece of state that arrives after hydration.

---

## 6. Traps

### 6.1 A production build cannot be run against a working tree that has `pnpm dev` in it

Known already as §6.3 of the phase 02 handoff, but this session needed the build anyway — the
change is precisely the kind that §6.7 (`useSearchParams` without a `Suspense` boundary) breaks, and
CI runs `pnpm build`. The way through, without touching a dev server that belongs to the developer:

```bash
DST=/tmp/fe-build && rm -rf $DST && mkdir -p $DST
cd frontend
cp package.json next.config.ts tsconfig.json postcss.config.mjs eslint.config.mjs \
   next-env.d.ts pnpm-workspace.yaml pnpm-lock.yaml $DST/
cp -R src public $DST/
ln -s "$PWD/node_modules" $DST/node_modules
cd $DST && npx next build
```

`next.config.ts` falls back to the `.env.example` ports when it cannot find `.env`, so the copy
builds without one. Confirm the route table still shows `○` for every route: a `useSearchParams`
that has escaped its boundary shows up there as a route that has silently gone dynamic, which is a
weaker signal than a build failure and easy to miss.

### 6.2 `useSearchParams`' Suspense boundary belongs inside the component that needs it

`GuestGuard` exports a wrapper whose only job is the boundary, with the hook one level down. Putting
it in the layout instead would work and would then be deleted by the next person who tidies the
layout, taking the build with it. The requirement should travel with the component that creates it.

### 6.3 `noUncheckedIndexedAccess` is on

`something.split(...)[0]` is `string | undefined`, and `tsc --noEmit` will say so. This is worth
knowing before you assume a type error is a real bug.

---

## 7. What is not covered by a test

Unchanged in kind from §8 of the phase 02 handoff — there is still no frontend test runner, by
design ([08-testing-strategy.md](../08-testing-strategy.md) §10) — but specifically:

- **Neither guard's redirect is automated.** `GuestGuard` and `AuthGuard` were exercised by hand.
- **`safeNextPath` has no test file**, and it is the one piece of this change that is security
  relevant. It was verified by compiling the real source and running twelve cases through it —
  ordinary path, query preserved, absent, empty, absolute URL, protocol-relative, backslash, the
  three loop variants, a prefix that only looks like an auth path, and root. **That verification
  lives nowhere but this paragraph.** When phase 11 brings Playwright, this function deserves the
  cheapest possible real test; it is pure, so it needs no framework beyond a runner existing.

---

## 8. Open items

Everything in §7 of the phase 02 handoff still stands. Added:

- **The signed-in branches were not seen by the agent that wrote them.** Verifying them needs a
  session, and registering an account to obtain one was declined; the project owner confirmed the
  behaviour in their own browser. If something is wrong with the signed-in rendering specifically,
  that is where it would be.
- **The phase 11 E2E flow does not currently include this.** Step 1 registers an owner; a case
  asserting that a signed-in visitor to `/login` lands on the dashboard would cost one line there
  and would cover both guards.
