# The Receptionist's writes are checked against Offered Slots, and never refused by the check

**Status:** accepted
**Decided:** 2026-09-13

A Customer asks for "the Monday after next"; the Receptionist writes an Appointment on a different day
about 10.6% of the time, rising to 55.2% when the date is phrased relatively
([#17](https://github.com/sanama-stack/reception-booking-system/issues/17)). Every layer below the model
behaves correctly while it happens: ownership is proven, the Slot is real, the engine returned it, and the
exclusion constraint has nothing to object to. **The day is the part that is wrong, and it is the part
nothing downstream can check** — because nothing in the runtime holds the relation between the date a
resolver handed back, the window that was searched, and the time that was finally written.

That absence is not only #17's. It is why the fourth candidate's experiment could not decide its own veto:
the harness logged `find_available_slots` and no other tool, so whether a wrong landing came from the
resolver or from nowhere was unknowable after the money had been spent (gap **G17**). The instrumentation
has since been fixed, but it is fixed in *SQL the probe runs afterwards* — `ProbeQueries` reconstructs from
`ai_messages` a link the application never formed. A question that costs a funded fifty-conversation arm to
ask is a question the system is not answering.

**We record the Slots the Receptionist actually quoted, compare every write against them inside the turn,
and count the mismatches on the Conversation. We do not refuse anything.**

The quoted Slots are the new glossary term, **Offered Slot**. They are not stored in their own right: they
are read back from the Transcript, and so are gone when it is. What survives the Transcript's ninety-day
deletion is two integers on `ai_conversations` — writes made, and writes matching no Offered Slot.

## Why the check does not refuse

The obvious design is a guard, and a guard was already built and rejected — #17's third candidate, recorded
in `docs/sessions/2026-09-11-the-guard-that-locked-in-the-drift.md`. `reschedule_appointment` gained a
required `requested_date` and refused the write when `new_starts_at` did not fall on it. Wrong writes went
**28.0% → 44.0%**, the pre-registered primary failed at p = 0.9700, and never-wrote *fell* rather than
rising — so whatever the parameter did, it was not catching wrong writes and holding them.

Its lesson is **T25 — a guard is only as good as the intent it is given. Cross-checking two model-authored
fields against each other proves they agree, not that either is right; and where they agree on the wrong
answer, the check enforces it.**

**T25 does not reach this decision, and the distinction is the whole reason to record one.** Candidate 3
compared `requested_date` against `new_starts_at`: both authored by the model, both capable of being wrong
together. An Offered Slot is authored by the **availability engine** — it exists because a Tool returned it
— so the comparison is between what the server offered and what the server was asked to write. That is a
different check, not a rehabilitated version of the same one.

Two residual risks from candidate 3 *do* transfer, and are why this stops at detection:

- **Its harm came from requiring the model to declare something new**, which made it commit to a date early
  and then held it there. Nothing here asks the model for anything; we record what already happened.
- **A candidate that improves its primary by refusing to act is not a fix** — the experiment's own §4 veto,
  triggered by never-wrote rising. A detector cannot trigger it, and a refuser would have to be measured
  against it in a pre-registered arm before it could ship.

So refusing stays available and stays unbuilt. The check runs inline, in the turn, precisely so that
promoting it later is a change to one branch rather than a new mechanism.

## Considered options

- **Refuse the write.** Above. Not rejected on principle — T25 does not forbid it — but it is a behaviour
  change requiring its own pre-registered arm, and shipping it behind an architecture change would smuggle
  an unmeasured mechanism in. Deferred, deliberately, with the seam placed so it remains cheap.
- **Echo the offered times back into the model's context and say no more.** *Already shipped, and already
  insufficient.* `FindAvailableSlotsTool` has put `searched_from` and `searched_to` into every result since
  phase 09 — commit `7d99200`, the first AI commit — and #17 happens anyway. Recording a fact into the
  model's context, with nothing comparing it to anything, is the control this codebase already has.
- **Derive the verdict after the turn, from the Transcript.** Cheaper on the hot path, but it puts the rule
  in a second place that must re-derive which Tool calls were writes, and leaves a window in which the
  verdict is simply absent. The lookback is Conversation-wide either way, so both shapes pay for a
  Transcript read; only one of them has a single place that knows the rule.
- **Store the resolved dates and searched windows on the Conversation** so they outlive the Transcript.
  Rejected: V10's header records as a principal decision that the parent row holds counters and a cost
  estimate while the Transcript holds what was said. Dates a Customer asked about are closer to content
  than to cost, and two integers answer the question that matters at that horizon.
- **Compare against the searched window rather than against Slots.** The window is what was *searched*; a
  Slot is the only thing the server ever **offered**. The experiment's primary endpoint is window coverage
  for a different reason — it measures whether the search was aimed correctly, upstream of any write — and
  borrowing it here would pass a write to a time inside the window that was never quoted.

## Consequences

- **`CONTEXT.md` gains `Offered Slot`**, defined as a Slot the Receptionist quoted during a Conversation,
  read back from the Transcript and gone when it is.
- **`ai_conversations` gains two counters**, so a mismatch rate is computable at any horizon, including
  after the Transcript is purged. A boolean would not have been derivable back into a rate; the reverse is.
- **The denominator is `create_appointment` *and* `reschedule_appointment`.** A booking to a time the
  Customer was never shown is the same defect — `reasonNotBookable` refuses an *unbookable* time, and a
  perfectly bookable one that was never quoted passes every check there is. `cancel_appointment` carries no
  time and is not counted. **The rate this produces is therefore not comparable to #17's recorded figures**,
  which measure reschedules alone; that is an accepted cost, not an oversight.
- **The counts read slightly high, knowably.** `find_available_slots` caps its results at `MAX_SLOTS` and
  sets `truncated`; a real Slot truncated out of the answer and then written counts as unoffered. The flag
  is recorded beside the verdict rather than loosening the comparison, so the overcount can be measured
  instead of assumed away.
- **Nothing the model sees changes**, and no turn can fail because of this check. It is observation, and a
  bug in it must not be able to refuse a Customer's booking.
- **The probe harnesses stop being the only thing that can answer the question.** They keep their SQL —
  they read detail this does not persist — but the production rate no longer requires a funded arm.
- **This does not close #17.** The landing rate is unchanged. What changes is that the residual is
  measurable where it actually happens.
