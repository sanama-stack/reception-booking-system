# An Employee is a bookable resource, not a login

**Status:** accepted

The brief used "employee" for two different things: a person who performs services and has a working
schedule, and a role that can sign in to the dashboard. Conflating them forces every future decision to
answer both questions at once — a barber who never touches a computer still needs a schedule, and an office
manager who never cuts hair still needs a login. We model `Employee` as a bookable resource owned by a
Business, and `User` as an authenticated principal, linked by a nullable `employee.user_id`.

## Consequences

- Owners can add staff and schedule them without creating accounts, inviting anyone, or handling passwords
  for people who will never log in. This is the common case in the target verticals.
- The MVP ships `OWNER` as the only working login. `ADMIN` and `STAFF` exist in the role enum and are
  treated as `OWNER` and unused respectively, so adding staff login later is a feature, not a migration.
- Appointments reference the Employee, never the User — so an employee's account can be created, changed or
  removed without touching appointment history.
- `users` deliberately carries **no** `business_id`; the `Membership` row is the link. A user belonging to
  two businesses later needs no schema change.
- Employees are deactivated, never deleted, because appointments reference them permanently.
