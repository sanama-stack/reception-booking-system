# Recurring rules stored as wall-clock time; appointments as UTC instants

**Status:** accepted

A business that opens at 09:00 opens at 09:00 on both sides of a daylight-saving transition, but an
appointment booked for a particular moment is that moment regardless of what the clock later does. These
are two different kinds of time and storing them the same way corrupts one of them. Business hours,
working schedules and their day-of-week keys are stored as `LocalTime`; appointments, closures and time off
are stored as `timestamptz` UTC instants; the availability engine converts between them using the
business's IANA timezone.

## Consequences

- Every business carries a validated IANA zone id, and it is load-bearing from phase 03 onward.
- The availability engine builds local wall-clock windows for a date, then converts to instants — never the
  reverse. Slot grids are anchored at **local** midnight so slots stay on clean local times year-round.
- Two DST cases must be handled explicitly and are required tests: local times that **do not exist** on a
  spring-forward day are skipped (naive conversion silently shifts them), and the repeated hour on a
  fall-back day resolves to the earlier instant.
- Changing a business's timezone does not move stored appointments but does change every displayed time, so
  the UI confirms before saving.
- All times are displayed in the business timezone everywhere, including the owner's calendar. Using the
  browser's locale formatter anywhere in this application is a defect, which is why the frontend's time
  helpers require an explicit zone argument.
- Business hours cannot cross midnight (`closes_at > opens_at`). A documented limitation; overnight
  businesses are a V1.2 item.
