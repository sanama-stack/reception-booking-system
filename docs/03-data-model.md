# 03 — Data Model

PostgreSQL 16. Flyway migrations, plain SQL, never generated from entities.

## 1. Schema-wide decisions

| Decision | Choice | Reasoning |
|---|---|---|
| Primary keys | **UUID v7**, generated in the application | Time-ordered, so B-tree locality is close to a sequence's, unlike v4. Chosen over `bigserial` because ids appear in public URLs and sequences leak tenant volume |
| Timestamps | `timestamptz`, always stored as UTC instants | See [ADR-0003](./adr/0003-wall-clock-rules-utc-instants.md) |
| Recurring rules | `time` + `day_of_week`, **never** instants | 09:00 must remain 09:00 across a DST change |
| Enums | `varchar` + `CHECK` constraint | Native PG enums make `ALTER TYPE` a migration hazard; strings are readable in `psql`. Mapped with `@Enumerated(EnumType.STRING)` |
| Deletion | Hard delete where safe; `active` flag where history references the row | Services and employees are referenced by appointments forever; deleting them would rewrite history |
| Audit fields | `created_at`, `updated_at` on every table | Plus `appointment_events` for the one entity whose transitions matter |
| Money | `numeric(12,2)` + ISO-4217 `currency` | Never floating point |
| Tenancy | `business_id` on every tenant-owned table | Plus composite FKs, below |
| Day of week | ISO-8601 `1 = Monday … 7 = Sunday` | Matches `java.time.DayOfWeek.getValue()`; no off-by-one translation layer |

### Composite foreign keys — the isolation backstop

Every tenant-owned table declares `UNIQUE (business_id, id)`. Cross-entity references then use a
**composite** foreign key:

```sql
ALTER TABLE appointments
  ADD CONSTRAINT fk_appt_employee
  FOREIGN KEY (business_id, employee_id)
  REFERENCES employees (business_id, id);
```

The database now refuses to attach an employee from Business A to an appointment in Business B. Application
bugs cannot produce cross-tenant rows. This costs one redundant unique index per table and is the cheapest
insurance in the schema.

---

## 2. Entities

### `users`

Authenticated principals.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `email` | citext | `UNIQUE`, stored lowercase |
| `password_hash` | varchar(100) | BCrypt, cost 12 |
| `full_name` | varchar(120) | |
| `created_at` / `updated_at` | timestamptz | |

Not tenant-owned: a user exists before any membership. **Never** carries `business_id` directly — the
membership is the link, so multi-business users need no migration later.

### `memberships`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `user_id` | uuid FK → users | |
| `business_id` | uuid FK → businesses | |
| `role` | varchar(20) | `CHECK IN ('OWNER','ADMIN','STAFF')` |
| `created_at` | timestamptz | |

`UNIQUE (user_id, business_id)`. Index on `(user_id)`.
MVP creates exactly one `OWNER` membership per user; the shape supports more without change.

### `refresh_tokens`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `user_id` | uuid FK → users | |
| `token_hash` | varchar(64) | SHA-256; the raw token is never stored |
| `family_id` | uuid | Rotation lineage; replay revokes the whole family |
| `expires_at`, `revoked_at`, `created_at` | timestamptz | |
| `user_agent`, `ip` | varchar | For the user's session list later |

Index `(user_id, revoked_at)`, `(token_hash)`.

### `businesses`

The tenant root.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `name` | varchar(120) | |
| `slug` | varchar(140) | `UNIQUE`, lowercase, URL-safe |
| `description` | text | |
| `address_line`, `city`, `country` | varchar | `country` is ISO-3166-1 alpha-2, used for phone normalisation |
| `phone`, `email`, `website` | varchar | |
| `timezone` | varchar(64) | IANA id, validated |
| `currency` | char(3) | ISO-4217 |
| `slot_interval_minutes` | int | default 15, `CHECK IN (5,10,15,20,30,60)` |
| `min_lead_time_minutes` | int | default 60, `CHECK 0..10080` |
| `max_advance_days` | int | default 60, `CHECK 1..365` |
| `cancellation_window_hours` | int | default 24, `CHECK 0..168` |
| `cancellation_policy` | text | Free text shown to customers and given to the Receptionist |
| `ai_enabled` | boolean | default true |
| `ai_additional_info` | varchar(2000) | Bounded on purpose — it enters every system prompt |
| `ai_daily_cost_cap_cents` | int | default 500 |
| `created_at` / `updated_at` | timestamptz | |

`UNIQUE (id)` restated as `UNIQUE (business_id …)` is unnecessary here; this *is* the tenant root.

### `business_hours`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id` | uuid FK | |
| `day_of_week` | smallint | `CHECK 1..7` |
| `opens_at`, `closes_at` | time | `CHECK opens_at < closes_at` |

`UNIQUE (business_id, day_of_week, opens_at)`. Multiple rows per day are permitted and unioned by the
engine (split shifts); the MVP UI writes one. **A day with no row is closed** — absence is meaningful.

`closes_at > opens_at` means hours cannot cross midnight. Documented limitation; see
[future/future-features.md](./future/future-features.md).

### `business_closures`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id` | uuid FK | |
| `starts_at`, `ends_at` | timestamptz | `CHECK starts_at < ends_at` |
| `reason` | varchar(200) | |

Index `(business_id, starts_at, ends_at)`. Stored as instants, computed from business-local dates at
creation, so the availability engine subtracts closures with exactly the same range algebra as appointments.

### `business_faqs`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id` | uuid FK | |
| `question` | varchar(300) | |
| `answer` | varchar(1000) | |
| `sort_order` | int | |

`CHECK` at application level: ≤ 50 rows per business. These are rendered into the system prompt verbatim.

### `services`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id` | uuid FK | |
| `name` | varchar(120) | |
| `description` | text | |
| `duration_minutes` | int | `CHECK 5..1440 AND duration_minutes % 5 = 0` |
| `buffer_before_minutes`, `buffer_after_minutes` | int | default 0, `CHECK 0..240` |
| `price_amount` | numeric(12,2) | `CHECK >= 0` |
| `currency` | char(3) | |
| `active` | boolean | default true |
| `created_at` / `updated_at` | timestamptz | |

`UNIQUE (business_id, lower(name))`, `UNIQUE (business_id, id)`.
Index `(business_id, active)`.

### `employees`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id` | uuid FK | |
| `user_id` | uuid FK → users, **nullable** | The seam for staff login; unused in MVP |
| `full_name` | varchar(120) | |
| `email`, `phone`, `job_title` | varchar | Never exposed on public endpoints |
| `active` | boolean | default true |
| `created_at` / `updated_at` | timestamptz | |

`UNIQUE (business_id, id)`, `UNIQUE (user_id)` where not null.
Index `(business_id, active)`.

**An employee is not a user.** See [ADR-0006](./adr/0006-employee-separated-from-user.md).

### `employee_services`

| Column | Type | Notes |
|---|---|---|
| `business_id` | uuid | Part of both composite FKs |
| `employee_id` | uuid | |
| `service_id` | uuid | |

`PRIMARY KEY (employee_id, service_id)`.
`FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id)`.
`FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id)`.

Those two composite FKs are what make a cross-tenant assignment impossible at the storage layer.
Index `(business_id, service_id)` for "who can perform this service".

### `employee_schedules`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id`, `employee_id` | uuid | Composite FK to employees |
| `day_of_week` | smallint | `CHECK 1..7` |
| `starts_at`, `ends_at` | time | `CHECK starts_at < ends_at` |

`UNIQUE (employee_id, day_of_week, starts_at)`. Multiple intervals per day permitted and unioned.

### `employee_time_off`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id`, `employee_id` | uuid | Composite FK |
| `starts_at`, `ends_at` | timestamptz | `CHECK starts_at < ends_at` |
| `reason` | varchar(200) | |

Index `(business_id, employee_id, starts_at, ends_at)`.

### `customers`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id` | uuid FK | |
| `full_name` | varchar(120) | |
| `phone` | varchar(20) | E.164, normalised using the business's country |
| `email` | varchar(254) | Nullable |
| `created_at` / `updated_at` | timestamptz | |

`UNIQUE (business_id, phone)` — the identity key. `UNIQUE (business_id, id)`.
Index `(business_id, lower(full_name))` for dashboard search.

The same human at two businesses is two rows. Tenants must not share customer data, and there is no global
customer identity to leak.

### `appointments`

The critical table.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id` | uuid FK | |
| `employee_id`, `service_id`, `customer_id` | uuid | All via composite FKs |
| `starts_at`, `ends_at` | timestamptz | What the customer sees |
| `blocked_from`, `blocked_to` | timestamptz | Buffer-inclusive; what the constraint uses |
| `status` | varchar(20) | `CHECK IN ('CONFIRMED','CANCELLED','COMPLETED','NO_SHOW')` |
| `price_amount` | numeric(12,2) | **Snapshot** at booking |
| `currency` | char(3) | Snapshot |
| `confirmation_code` | varchar(12) | Crockford base32, 8 chars |
| `source` | varchar(20) | `CHECK IN ('AI','CLASSIC','DASHBOARD')` |
| `customer_note` | varchar(1000) | |
| `cancelled_at` | timestamptz | |
| `cancelled_by` | varchar(20) | `CHECK IN ('CUSTOMER','BUSINESS')` |
| `cancellation_reason` | varchar(500) | |
| `version` | bigint | Optimistic locking for concurrent reschedules |
| `created_at` / `updated_at` | timestamptz | |

**Constraints:**

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE appointments ADD CONSTRAINT appointments_no_overlap
  EXCLUDE USING gist (
    employee_id WITH =,
    tstzrange(blocked_from, blocked_to, '[)') WITH &&
  ) WHERE (status = 'CONFIRMED');

ALTER TABLE appointments ADD CONSTRAINT appointments_time_order
  CHECK (starts_at < ends_at AND blocked_from <= starts_at AND ends_at <= blocked_to);

ALTER TABLE appointments ADD CONSTRAINT appointments_code_unique
  UNIQUE (business_id, confirmation_code);

ALTER TABLE appointments ADD CONSTRAINT appointments_cancel_fields
  CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL));
```

The `'[)'` bound is deliberate: a 15:00–16:00 appointment and a 16:00–17:00 appointment must both be
allowed. Using `'[]'` would silently forbid back-to-back bookings.

The partial `WHERE status = 'CONFIRMED'` is what lets a cancelled appointment's time be rebooked.

**Indexes:**

```sql
CREATE INDEX ON appointments (business_id, starts_at);
CREATE INDEX ON appointments (business_id, status, starts_at);
CREATE INDEX ON appointments (business_id, customer_id, starts_at DESC);
CREATE INDEX ON appointments (business_id, employee_id, starts_at);
```

Every index is led by `business_id`, because every query is tenant-scoped. An index that does not begin
with `business_id` is a design smell in this schema.

### `appointment_events`

Append-only audit of every transition.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id`, `appointment_id` | uuid | |
| `event_type` | varchar(30) | `CREATED`, `RESCHEDULED`, `CANCELLED`, `COMPLETED`, `NO_SHOW` |
| `actor_type` | varchar(20) | `CUSTOMER`, `USER`, `AI`, `SYSTEM` |
| `actor_id` | uuid | Nullable |
| `payload` | jsonb | Old/new times, reason |
| `created_at` | timestamptz | |

Index `(appointment_id, created_at)`. Never updated, never deleted.

### `notifications`

The outbox.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id`, `appointment_id` | uuid | |
| `type` | varchar(30) | `BOOKING_CONFIRMATION`, `REMINDER_24H`, `CANCELLATION`, `RESCHEDULE` |
| `channel` | varchar(20) | `EMAIL` in MVP |
| `recipient_email` | varchar(254) | |
| `subject`, `body_html`, `body_text` | text | Rendered at enqueue time so templates can change safely |
| `scheduled_for` | timestamptz | |
| `status` | varchar(20) | `PENDING`, `SENT`, `FAILED`, `CANCELLED` |
| `attempts` | int | default 0 |
| `last_error` | text | |
| `sent_at`, `created_at` | timestamptz | |

```sql
CREATE INDEX ON notifications (scheduled_for) WHERE status = 'PENDING';
CREATE UNIQUE INDEX ON notifications (appointment_id, type)
  WHERE status IN ('PENDING','SENT')
    AND type IN ('BOOKING_CONFIRMATION','REMINDER_24H');
```

The partial unique index makes duplicate confirmations and reminders impossible even if enqueue logic
runs twice.

**The type predicate is load-bearing, and was added in phase 07.** Written over every type, the index
also forbids the second `CANCELLATION` or `RESCHEDULE` row — and a customer who moves an appointment
twice is owed two emails. Confirmations and reminders are owed once per appointment for its whole
life; a cancellation and a reschedule are *events*, and an event that happens twice is two facts
rather than one fact repeated. `V6__notifications.sql` carries the full argument.

The due index drops `status` from its columns because the partial predicate already restricts the
index to `PENDING` rows; carrying the column as well would store one constant value per entry. It is
also the only index in this schema not led by `business_id`, deliberately: the poller is the system
delivering mail for every tenant at once, and there is no per-tenant query against this table.

### `ai_conversations`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `business_id` | uuid FK | |
| `session_token_hash` | varchar(64) | SHA-256 of the client's session token |
| `customer_id` | uuid | Nullable; set once identity is known |
| `status` | varchar(20) | `ACTIVE`, `CLOSED`, `LIMIT_REACHED` |
| `message_count` | int | Enforces the turn ceiling |
| `prompt_tokens`, `completion_tokens` | int | Cost accounting |
| `estimated_cost_cents` | int | Feeds the daily cap |
| `authorized_appointment_ids` | uuid[] | Which appointments this conversation may act on |
| `started_at`, `last_message_at` | timestamptz | |

Index `(business_id, started_at DESC)`, `(session_token_hash)`.

`authorized_appointment_ids` is the persisted form of the Q3 authorization decision: it is appended to only
by a successful `create_appointment` or `lookup_appointment` in this conversation. **The model can never
write to it.**

### `ai_messages`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `conversation_id`, `business_id` | uuid | |
| `role` | varchar(20) | `USER`, `ASSISTANT`, `TOOL` |
| `content` | text | |
| `tool_name` | varchar(60) | |
| `tool_call_id` | varchar(80) | |
| `tool_arguments`, `tool_result` | jsonb | |
| `created_at` | timestamptz | |

Index `(conversation_id, created_at)`. The system prompt is **not** stored per message — it is rebuilt
deterministically from configuration, so it can never drift or be exfiltrated from the message log.

---

## 3. Relationships

```text
users ──1:N── memberships ──N:1── businesses
                                       │
      ┌──────────────┬─────────────┬───┴────────┬──────────────┬─────────────┐
      ▼              ▼             ▼            ▼              ▼             ▼
business_hours  business_     business_    services      employees      customers
                closures        faqs           │              │              │
                                               └──┬───────────┘              │
                                                  ▼                          │
                                          employee_services                  │
                                                                             │
   employees ──1:N── employee_schedules                                      │
   employees ──1:N── employee_time_off                                       │
                                                                             │
   appointments ──N:1── employees                                            │
   appointments ──N:1── services                                             │
   appointments ──N:1── customers ◄──────────────────────────────────────────┘
   appointments ──1:N── appointment_events
   appointments ──1:N── notifications

   businesses ──1:N── ai_conversations ──1:N── ai_messages
```

## 4. Where uniqueness matters, and why

| Constraint | Prevents |
|---|---|
| `users(email)` unique | Two accounts on one address; also the login lookup key |
| `businesses(slug)` unique | Two public pages on one URL |
| `memberships(user_id, business_id)` unique | Duplicate role rows with conflicting roles |
| `services(business_id, lower(name))` unique | Two "Haircut" services the owner cannot tell apart |
| `customers(business_id, phone)` unique | Duplicate customer records splitting one person's history |
| `appointments(business_id, confirmation_code)` unique | Two appointments answering to one code |
| `appointments` EXCLUDE | **Double booking** — the constraint the whole design rests on |
| `notifications(appointment_id, type)` partial unique | Duplicate confirmation or reminder emails |
| `employees(user_id)` unique | One login mapped to two employee records |

## 5. Migration plan

| Migration | Content |
|---|---|
| `V1__extensions.sql` | `citext`, `btree_gist` |
| `V2__users_and_auth.sql` | `users`, `memberships`, `refresh_tokens`, plus the **minimal** `businesses` and `business_hours` that registration must create atomically |
| `V3__businesses.sql` | `ALTER TABLE businesses` adding profile, booking-settings and AI columns; `business_closures`; `business_faqs`; the `business_hours` unique constraint |
| `V4__catalog_and_staff.sql` | `services`, `employees`, `employee_services`, `employee_schedules`, `employee_time_off` |
| `V5__customers_and_appointments.sql` | `customers`, `appointments` (+ exclusion constraint), `appointment_events` |
| `V6__notifications.sql` | `notifications` |
| `V7__ai.sql` | `ai_conversations`, `ai_messages` |

Migrations are additive and never edited after being applied anywhere. Each phase owns its migration; no
phase edits an earlier one.
