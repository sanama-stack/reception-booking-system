# Session handoff — 2026-09-09 — Phase 08, Public Booking, backend half

> **Purpose.** Enough context to continue without re-reading this session. §1 says what is done;
> §4 is where decisions had to be taken; §5 will save you the most time.
>
> **§4.1 and §4.2 are the two to read.** Both were open questions three handoffs carried, both were
> put to the principal, and both were answered — the answers shape the DTOs the frontend half is
> about to consume.

---

## 1. Where the project stands

**Phase 08's backend half is complete.** Every box in
`docs/phases/phase-08-public-booking.md` that a server can tick is ticked. A stranger with a slug can
read a business, its services, its staff and its availability; book; look the booking up with a
Confirmation Code and a phone number; open a Manage Link; and cancel or reschedule from either proof.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` |
| Working branch | **`dev`** |
| Backend tests | **720**, up from 674 — 46 new, all green |
| Frontend | One deletion only: the workaround in §4.1 is gone. Lint and typecheck clean |
| Migrations | **None.** Phase 08 adds no table and no column, by design |

**No database work at all**, which is itself the demonstration the phase document asks for: the public
surface reuses the dashboard's tables and its application services, so it adds no privileged path.
There is no query on the public side that the authenticated side does not already make.

**The frontend half is untouched and is what remains** — `/book/[slug]` and `/manage/[token]`. See §8.

---

## 2. Running it

Unchanged. Two things worth knowing before you drive the new surface by hand:

**Get the slug from `GET /api/business`** as the owner, or read it off the dashboard's Settings screen.
Everything public hangs off `/api/public/businesses/{slug}`.

**Rate limits are on in `local`.** The lookup endpoint gives you five attempts an hour per address, and
they are spent by attempts, not by failures. Set `RATE_LIMIT_ENABLED=false` in `.env` while building the
pages, or you will lock yourself out of your own booking page and it will look like a bug.

Gradle still needs `JAVA_HOME=$(/usr/libexec/java_home -v 21)` on this machine. Unchanged.

---

## 3. What exists now

**`dev/reception/publicapi/`** — nine classes where phase 06 left a `package-info.java` promising them.
Four controllers, two DTO holders, the authority resolver, and the package documentation, which is
worth opening first because it states the three rules the whole package holds to.

**Two classes in `tenancy/`**, because tenant resolution lives there and nowhere else:
`SlugTenantContextFilter` for the slug path, and `TenantAdoption` for the Manage Link path, which
cannot know its tenant until the capability has been verified. `TenantContextHolder.set` is still
package-private; `TenantAdoption` is the only public way to set a tenant in the application, and
`PublicAppointmentAuthority` is its only caller.

**`AppointmentDirectory`** — the second deliberately non-tenant-scoped repository in the codebase,
after `NotificationClaimRepository`, and separate from `AppointmentRepository` for the same reason: a
`@TenantScoped` repository carrying one cross-tenant method teaches the next reader that the rule has
exceptions.

**`RateLimitFilter` needed no changes.** Phase 02 built it correctly; phase 08 added seven policies to
the list it reads. That is the second time this phase found the previous one had left the seam in the
right place.

---

## 4. The decisions that will shape the frontend half

Both of these were open items carried by three consecutive handoffs, both were deliberately deferred to
this phase, and both were put to the principal rather than decided here.

### 4.1 The public booking body is nested, and `CustomerService` no longer names wire fields

**Two questions, and they turned out to be separable.**

The first: `docs/04-api-overview.md` §6 specifies a nested `customer: { fullName, phone, email }` for
the public endpoint, while the dashboard ships flat `customerName` / `customerPhone`. **Decision:
follow the published spec.** Two wire shapes exist and each matches its own contract; no shipped code
moved.

The second is the real defect. `CustomerService` — reached by the dashboard, the public page, the
Customers screen and phase 09's Tools — was emitting *one particular client's* wire vocabulary, and
inconsistently: `requiredName` reported `customerName` while `requiredPhone`, two lines below, reported
`phone`. A client looks each `errors[].field` up **by exact name** to decide which input to mark, so a
name it did not send matches nothing and the message is dropped.

**Decision: decouple.** `CustomerFieldNames` is passed in by the web layer, which is the only thing that
knows what the request called its fields — an argument rather than something the service fetches, for
the reason `Actor` and `Clock` are. `BookingService.BookingRequest` carries one; the dashboard passes
`DASHBOARD_BOOKING`, the public controller `PUBLIC_BOOKING` (dotted paths), the Customers screen
`CORRECTION`.

**Two consequences you will meet.** The dashboard's fallback —
`fieldErrors.customerPhone ?? fieldErrors.phone`, twelve lines of comment explaining itself — is
**deleted**, in `frontend/src/app/(dashboard)/appointments/new/page.tsx`. And a phase 06 test that
asserted the field arrived as `phone` was **pinning the defect**; it now asserts `customerPhone` and
carries a note saying why it changed.

**For the booking page: index errors by `customer.phone`, `customer.fullName`, `customer.email`.**

### 4.2 The reschedule grid is a separate, token-authorised endpoint

`GET /availability` could not exclude the appointment being rescheduled, which matters more than it
sounds: an appointment blocks its own hour *and its Buffers*, so an unexcluded grid refuses to offer a
10:00 booking a slot at 10:15 — the one move being asked for.

The plumbing already existed. `AppointmentImpact.blockedRangesFor(…, excludingAppointmentId)` and
`AvailabilityService.reasonNotBookable(…)` both took it; only `find` did not.

**Decision: two different answers for two different surfaces.**

- **`GET /availability?…&excludeAppointmentId=`** — the dashboard. Safe to accept from the caller
  because the endpoint is authenticated and tenant-scoped: another business's id excludes nothing,
  because those rows were never in scope. There is a test that borrows a *real* id from another tenant
  and asserts the grid is byte-identical.
- **`GET /public/appointments/manage/availability?token=…`** — the Customer. **The public availability
  endpoint deliberately does not take an exclusion at all.** An anonymous caller could pass any id, and
  diffing the grid with and without it would reveal that the appointment exists and what time it holds.
  Here the exclusion *is* the appointment the token authorises and there is no parameter that could
  make it anything else — a test passes `&excludeAppointmentId=` alongside the token and proves it
  changes nothing.

`find` takes the parameter explicitly rather than through an overload, matching `reasonNotBookable`:
the two must be asked about the same world, and an overload quietly meaning "count everything" is how
they would come to disagree.

### 4.3 The path id is checked against the proof, never used to find the appointment

On `POST /public/appointments/{id}/cancel` and `/reschedule`, the `authority` in the body resolves an
Appointment **on its own**, and the `{id}` in the path is then compared against it. An id in a URL is a
claim; the token is the evidence, so the evidence decides.

A Manage Link for appointment A presented on B's path is **`404`, not `403`** — matching the rule that
"does not exist" and "is not yours" are indistinguishable everywhere else. A distinct code would confirm
that B exists.

### 4.4 Lookup spans tenants, because it has to

`POST /public/appointments/lookup` carries no slug — a Customer reading a code off an email does not
know which slug it belongs to. So the Confirmation Code is queried across every Business, and the phone
number is compared per candidate.

**The phone cannot be normalised before the query runs.** It is stored E.164, and normalising what the
caller typed needs the Business's country — which is the *answer*, not a premise, and differs per row
when more than one candidate comes back. So `businessCountry` travels with each candidate and the
comparison happens once per row. A Georgian customer typing `555 12 34 56` is matched correctly; there
is a test.

Codes are unique *within* a Business, so the query returns a list and is ordered `by a.id desc` —
newest first. Two businesses issuing the same eight characters *to the same phone number* is a
coincidence worth about 10^-12, and if it happens the caller proved both, so either is authorised.

The comparison is a plain `equals`, deliberately. What is worth brute-forcing is the code, and the index
probe that found the candidate has data-dependent timing this code cannot do anything about — a
constant-time compare underneath it would be theatre. The control that bounds guessing is five attempts
per hour.

---

## 5. Traps already paid for

### 5.1 Flipping the last character of a base64url signature tampers with nothing

The test for "a tampered token is refused" passed a token that was **still perfectly valid**, and
returned `200`.

An HMAC-SHA256 is 32 bytes: 42 full base64url characters plus a 43rd carrying only four significant
bits. Four different final characters therefore decode to the same 32 bytes. `A` → `B` changed only the
padding.

Caught because the assertion failed loudly, but it would not have: written as "expect anything other
than 200" it would have gone green and stayed green if verification broke entirely. **Tamper in the
middle.** The test now does, with the arithmetic in a comment.

### 5.2 The problem+json key is `errors`, not `fieldErrors`

`ProblemDetails` writes `errors`. The frontend's `fieldErrors` is a *client-side* shape produced by its
own error parser. A test asserting `$.fieldErrors[*].field` fails with `PathNotFoundException`, which
reads like a missing field and is a wrong path.

### 5.3 First match wins in the rate-limit filter, and the shadowing is silent

`RateLimitFilter` takes the first policy whose pattern matches. `/public/businesses/**` also matches
`/public/businesses/{slug}/availability`, so ordering the general read above the tight one makes the
tight one **unreachable** — nothing fails, no request-counting test notices, and the endpoint you meant
to protect is guarded by the loose limit.

`RateLimitPolicyOrderTest` pins which policy each public path lands on, as a unit test. Proving it by
exhausting limits would take as many requests as the loosest budget and would still only cover the
paths somebody thought to try.

### 5.4 `TenantContextFilter` had to start clearing unconditionally

It cleared the MDC key only when *it* had resolved a tenant. Since this phase two other things resolve
one inside its chain, so a request it saw as anonymous can be carrying a tenant by the time it returns.
Both `MDC.remove` and `TenantContextHolder.clear` are now unconditional.

### 5.5 The ArchUnit "no entity returned" rule must be scoped to handler methods

Written against every method in `publicapi`, it fails on `PublicAppointmentAuthority.byManageToken` —
which returns an `Appointment` and is *supposed to*. Inside the package an entity is an ordinary value;
what must never be one is the thing that gets serialised. The rule is scoped by
`areMetaAnnotatedWith(RequestMapping)`, which covers every verb including any added later.

### 5.6 `AvailabilityEndpointTest.availability()` prepends its own `?`

Passing a query string that already starts with one produces `/availability??serviceId=…`, which parses
into a first parameter named `?serviceId` and returns a validation error. Two of my new tests did this;
one of them *passed*, because it compared two equally broken responses.

---

## 6. What is not covered by a test

- **Nothing asserts the `Retry-After` value is correct**, only that it is present. The arithmetic is
  Bucket4j's `getNanosToWaitForRefill`, and asserting on it would mean asserting on wall-clock timing.
- **The 60/min availability limits are never exhausted.** `RateLimitPolicyOrderTest` proves which policy
  each path lands on and the integration tests exhaust the two tight ones; sixty-one requests to prove
  the loose one fires was judged not worth the suite time. If you doubt it, it is one loop.
- **Nothing tests two public bookings racing the same slot concurrently.** `ConcurrentBookingTest`
  proves the exclusion constraint under twenty threads through the dashboard, and the public path calls
  the identical `BookingService.book`. The sequential `409` is tested here; the concurrent one is
  covered by inheritance rather than directly.
- **`PublicAppointmentAuthority.byLookup` is never exercised with two candidate businesses holding the
  same Confirmation Code.** Producing that state means forcing the generator, and the branch is three
  lines of a `for` loop. The cross-tenant *phone* mismatch is tested, which is the half an attacker
  would use.
- **No test covers a slug containing characters that need URL encoding.** `SlugService` only ever
  produces `[a-z0-9-]`, so this is unreachable today rather than untested.

### What *was* verified

46 tests. The five worth more than the rest:

**A stranger with no cookie jar books end to end**, and `PublicTestClient` has no cookie storage at all —
so nothing in the public suite can pass because a session leaked into it.

**The public availability grid is compared to the internal one with `isEqualTo` on the whole body.**
Not "both have slots": byte for byte. Two implementations of "when are you free" would eventually
disagree and the one a Customer saw would be the wrong one.

**Every public response's every key is collected and asserted to be a subset of a hand-written
allow-list**, across all eleven endpoints, plus real Employee contact details planted in the fixture and
asserted absent from every body. Keys alone are not enough — `phone` is legitimate on a Business and
unacceptable on an Employee — so the values are checked too.

**A booking cannot be assembled from two businesses' ids.** Aria's slug, Aria's service, Datos Auto's
employee: `404`, and both tenants still have zero appointments.

**A Manage Link for one appointment, presented on another's cancel path, is refused** — and the other
appointment is asserted to be still `CONFIRMED` afterwards, which is the assertion that actually
matters.

---

## 7. Open items

- **Nothing is unpushed at the time of writing beyond this session's own commit.** Branch from `dev`.
- **`Actor.system()` still has no caller.** The poller sends mail; it does not transition appointments.
  Phase 08 did not close this either — the public paths use `Actor.customer()`. A no-show sweep will be
  its first caller, and nothing has built one. **Two handoffs have now predicted this would close and
  been wrong; stop predicting it.**
- **`Actor.customer()` now has callers, and `ActorType.CUSTOMER` reaches `actor_type` for the first
  time.** The appointment history list will start rendering customer-sourced events on the dashboard's
  detail screen. Its copy was written in phase 06 and has never been seen with real data — **worth a
  look during the frontend half.**
- **`CLASSIC` as a source now reaches the database** and the detail page's copy for it will render.
  Also never seen.
- **The verification tenant is unchanged.** `Phase 06 Scratch` / `phase-06-scratch`, owner
  `scratch@example.com`, UTC/USD. Its data is verification wreckage, not a clean slate.
  **Nothing in this session was verified in a browser**, because the backend half ships no screen. The
  public API can be exercised with `curl` against any slug without signing in, which is worth doing
  once before building against it.
- **`aiEnabled` is published on the public profile and has no UI in Settings.** Phase 09 owes both.
- **CI's deprecation warnings are unchanged**: Node 20 notices plus `actions/setup-java@v4`. Still
  warnings, still green. Phase 11 owns the workflow file.
- **Gradle 8.14 cannot run on this machine's default JDK.** Unchanged.

---

## 8. Next: phase 08's frontend half

`/book/[slug]` and `/manage/[token]`. Five things this session leaves you:

1. **Index field errors by `customer.phone`, not `phone`.** §4.1. The server now reports the name your
   request used, and the dashboard's old two-name fallback has been deleted rather than copied.
2. **Use `GET /public/appointments/manage/availability` for the reschedule grid**, never the ordinary
   public availability endpoint. §4.2. The ordinary one will not offer the customer their own time back,
   and the reschedule will appear broken for the most common move there is.
3. **`canCancel` and `canReschedule` are on every `ManagedAppointment`**, already combining the
   Cancellation Window with the appointment's status, and `business.cancellationPolicy` is beside them.
   That is what the phase document's "rendered as the business's policy text, not a raw error" needs.
   The endpoint re-checks regardless: a field in a response is a hint, never a control.
4. **The `409` experience is yours entirely.** The server gives you `SLOT_UNAVAILABLE` with a clean
   body; refreshing the grid and keeping the customer's entered details is the page's job, and the
   phase document calls a silent failure or a raw error a defect.
5. **Turn the rate limits off while you build.** §2. Five lookups an hour is correct in production and
   will make your afternoon confusing.
