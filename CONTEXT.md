# Booking System

A multi-tenant SaaS where service businesses configure their staff, services and hours, and their customers
book appointments through an AI receptionist backed by a deterministic scheduling engine.

## Language

### Tenancy and people

**Business**:
The tenant. Every other record in the system belongs to exactly one Business.
_Avoid_: Tenant, organization, company, account, shop

**Business FAQ**:
A question and its answer, owned by a Business. Folded into the Receptionist's system prompt rather
than exposed as a Tool or rendered on the public booking page, so it grounds what the Receptionist
says without being a page a Customer reads.
_Avoid_: Knowledge base, help article, canned answer, policy

**User**:
An authenticated principal who signs in to the dashboard. Belongs to a Business through a Membership.
_Avoid_: Account, admin, staff

**Membership**:
The link between a User and a Business, carrying that user's role.
_Avoid_: Assignment, role record

**Employee**:
A bookable resource owned by a Business — someone who performs Services and has a working schedule.
An Employee is *not* a login; it may optionally be linked to a User, but need not be.
_Avoid_: Staff member, provider, resource, worker

**Customer**:
A person who books appointments with a Business. Scoped to one Business and identified by phone number.
Has no password and no account; the same human is a separate Customer at each Business.
_Avoid_: Client, guest, end user, patient

### Scheduling

**Service**:
Something a Business offers that a Customer can book, carrying a duration and price.
_Avoid_: Treatment, procedure, offering, product

**Appointment**:
A confirmed reservation of one Employee's time for one Service on behalf of one Customer.
_Avoid_: Booking, reservation, visit, slot

**Slot**:
A candidate start time that availability calculation has proven bookable. A Slot is not stored; it is computed.
_Avoid_: Opening, availability, free time

**Business Hours**:
The wall-clock times a Business is open, per day of week. Independent of any Employee.
_Avoid_: Opening hours, operating hours, schedule

**Working Schedule**:
The wall-clock times an Employee is available to perform Services, per day of week.
_Avoid_: Shift, roster, employee hours, availability

**Buffer**:
Padding before or after a Service that blocks an Employee's time without being part of the Appointment
the Customer sees. Buffers block overlap; they are not required to fit inside Business Hours.
_Avoid_: Gap, padding, turnaround, cleanup time

**Business Closure**:
A date range during which a Business is closed regardless of its Business Hours.
_Avoid_: Holiday, blackout, shutdown

**Time Off**:
A date range during which one Employee is unavailable regardless of their Working Schedule.
_Avoid_: Leave, vacation, absence, PTO

**Booking Horizon**:
The window in which Slots may be offered — no earlier than the minimum lead time, no later than the
maximum advance limit.
_Avoid_: Booking range, lookahead, window

**Cancellation Window**:
The period immediately before an Appointment during which a Customer may no longer cancel or reschedule
it themselves. The Business is never bound by it.
_Avoid_: Cutoff, deadline, grace period

### Customer authorization

**Confirmation Code**:
A short random code issued with an Appointment. A Customer proves an Appointment is theirs by presenting
their phone number together with this code.
_Avoid_: Reference number, booking ID, PIN

**Manage Link**:
A signed, expiring URL sent in the confirmation email that grants its holder authority over one Appointment
without any login.
_Avoid_: Magic link, token link, self-service link

### AI

**Receptionist**:
The customer-facing AI conversational interface on a Business's public booking page.
_Avoid_: Bot, chatbot, assistant, agent

**Tool**:
A named, validated backend capability the Receptionist may invoke. Tools are the *only* way the Receptionist
can read or change Business state.
_Avoid_: Function, action, skill, command

**Conversation**:
One exchange between one Customer and the Receptionist, from the moment the chat panel opens a
session until it is closed or hits a ceiling. It carries its own budget, its message count, and the
set of Appointments it has proven it may act on — nothing else in the system grants that authority,
and no Tool can write it.
_Avoid_: Chat, session, thread, dialogue

**Transcript**:
The ordered record of a Conversation: every Customer message, every Receptionist reply and every Tool
call with its arguments and its result. It is what an owner reads when the Receptionist has done
something surprising, and it is **deleted ninety days after the Conversation's last activity** — the
Conversation itself is kept, because it holds what the Receptionist cost and no free text.
_Avoid_: History, log, messages, chat record

**Classic Flow**:
The deterministic, non-conversational booking path (service → employee → date → slot → confirm) that calls
the same endpoints the Receptionist's Tools call.
_Avoid_: Manual booking, fallback, traditional flow

### Notifications

**Notification**:
A single intended message to one Customer about one Appointment, recorded before it is sent and drained
by a poller. Its record is the source of truth for what was sent, not the mail server.
_Avoid_: Email, message, alert, reminder job

**Outbox**:
The Notification table read as a queue — rows written in the same transaction as the Appointment that
caused them, then claimed and sent by a poller. The pattern is the reason a confirmation cannot be
lost by a booking that succeeded, and cannot be sent by one that rolled back.
_Avoid_: Queue, job table, spool, mail queue
