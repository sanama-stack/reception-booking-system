package dev.reception.ai.probe;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.reception.ai.application.AiProperties;
import dev.reception.ai.application.ConversationService;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import dev.reception.tenancy.TenantAdoption;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * <strong>Does the Receptionist ever decline a slot it was handed, on the day it was asked
 * about?</strong>
 *
 * <p>That is mechanism 2 of
 * <a href="https://github.com/sanama-stack/reception-booking-system/issues/40">#40</a> — the only
 * part of that issue rule 13 does not touch, and the only behaviour in this repository still
 * described as <em>invention</em>. Pre-registered in
 * {@code docs/experiments/2026-09-22-40-mechanism-2-scenario.md}, which fixes the endpoints, the
 * vetoes and the readings before any number was collected.
 *
 * <p><strong>Why a second harness rather than a flag on {@code RescheduleRefusalRateTest}.</strong>
 * That one is #17's sharpest instrument and three recorded arms are comparable with each other
 * because its script has not moved. Changing it to ask a different question would silently end that
 * comparability, which is the T194 error one level up.
 *
 * <p><strong>The denominator is the point.</strong> {@code #40}'s entry carried
 * {@code ≈1.3% of conversations}: 2 events in 150, most of which never reached the decision point at
 * all because the search went to the wrong day. Conditioned on the model actually <em>holding</em>
 * the slot it was asked for, the same arms read 2 of 9 against 0 of 50. This harness reaches the
 * decision point on purpose and reports refusals over the trials that reached it.
 *
 * <h2>The two arms</h2>
 *
 * <p><strong>Arm A — {@code offered and asked}.</strong> The Customer never names a date. The
 * Receptionist searches, and the Customer then asks for a time <em>taken out of the tool's own
 * answer</em> — so the wrong-day search cannot occur and the requested slot is offered by
 * construction. This is the scenario #40 asked for, and it is the control.
 *
 * <p><strong>Arm B — {@code denied, then free}.</strong> A real appointment blocks 15:00 on the
 * appointment's own day, so the Receptionist's first decline is <em>truthful and correct</em>. The
 * Customer then asks for 15:00 the next day, which is free. To write it the model must state a time
 * it has just told the Customer it cannot have.
 *
 * <p>Arm B exists because <strong>both</strong> of mechanism 2's observations searched
 * {@code booked + 1} before they searched the requested day. A scenario that forbids the wrong-day
 * search removes the only condition the defect has ever been seen under. That is a hypothesis with
 * two data points and no control — the old harness printed the searched days for refusals only — and
 * it is why the arms are run as a pair rather than arm A alone.
 *
 * <p><strong>Asserts nothing</strong>, like its three siblings. A threshold on a nondeterministic
 * system is what {@code docs/08-testing-strategy.md} §7 keeps out of the pipeline and what T198
 * calls rejection with extra steps. It prints, and a person reads the number against §5 of the
 * pre-registration.
 *
 * <pre>{@code
 * export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
 * PROBE_ARM=A PROBE_CONVERSATIONS=50 PROBE_TARGET_DAYS=13 ./gradlew test -PincludeTags=probe \
 *   --tests '*RefusalAtTheDecisionPointTest' --rerun
 * }</pre>
 */
@Tag("probe")
@TestPropertySource(properties = "app.ai.scripted=false")
class RefusalAtTheDecisionPointTest extends IntegrationTest {

    private static final int CONVERSATIONS =
            Integer.parseInt(Optional.ofNullable(System.getenv("PROBE_CONVERSATIONS")).orElse("50"));

    /** {@code A} = offered and asked, the control. {@code B} = denied, then free. */
    private static final String ARM =
            Optional.ofNullable(System.getenv("PROBE_ARM")).orElse("A").toUpperCase();

    private static final DateTimeFormatter SPOKEN = DateTimeFormatter.ofPattern("HH:mm");

    /** The Customer whose 15:00 is genuinely taken in arm B. Not the Customer under test. */
    private static final String BLOCKER_PHONE = "+995555777222";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private TenantAdoption tenants;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private AiProperties properties;

    /**
     * Where the appointment sits. Printed, because T194: the distance to the horizon is uncontrolled
     * in every reschedule rate this project has recorded.
     *
     * <p>Arm B needs {@code D + 1} to be a working day as well, so it refuses a Friday where arm A
     * would accept one. A weekend target in either arm answers NO SLOTS in every trial and prints as
     * a collapse that has not happened.
     */
    private LocalDate targetDate() {
        LocalDate today = LocalDate.now(clock.withZone(BookingScenario.TBILISI));
        String configured = System.getenv("PROBE_TARGET_DAYS");
        LocalDate target = configured == null
                ? today.plusDays(7).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                : today.plusDays(Integer.parseInt(configured));
        refuseNonWorkingDay(target, "the appointment");
        if ("B".equals(ARM)) {
            refuseNonWorkingDay(target.plusDays(1), "arm B's requested day, which is the day after");
        }
        return target;
    }

    private static void refuseNonWorkingDay(LocalDate date, String what) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            throw new IllegalArgumentException(
                    "PROBE_TARGET_DAYS puts %s on %s (%s), which this business is closed on. Pick a distance that lands Monday to Friday."
                            .formatted(what, date, day));
        }
    }

    @Test
    void how_often_is_a_slot_the_receptionist_was_handed_refused() {
        assumeTrue(properties.isConfigured(), "no OPENAI_API_KEY configured");
        if (!"A".equals(ARM) && !"B".equals(ARM)) {
            throw new IllegalArgumentException("PROBE_ARM must be A or B, not " + ARM);
        }

        databaseCleaner.clean();
        BookingScenario aria = BookingScenario.open(rest, port, clock);
        tenants.adopt(UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class)));

        LocalDate target = targetDate();
        LocalDate requestedDay = "B".equals(ARM) ? target.plusDays(1) : target;
        LocalDate today = LocalDate.now(clock.withZone(BookingScenario.TBILISI));

        System.out.printf(
                "ARM %s — %s. %d trials. Appointment at 12:00 on %s (%s, today+%d); the requested day "
                        + "is %s.%n%s%n%n",
                ARM,
                "B".equals(ARM) ? "denied, then free" : "offered and asked",
                CONVERSATIONS,
                target,
                target.getDayOfWeek(),
                ChronoUnit.DAYS.between(today, target),
                requestedDay,
                "B".equals(ARM)
                        ? "15:00 on the appointment's own day is really taken, so the first decline is "
                                + "TRUTHFUL and is not scored. 15:00 the next day is free by construction."
                        : "No date is ever named. The requested time is read out of the tool's own "
                                + "offered set, so it is offered by construction.");

        int moved = 0;
        int refused = 0;
        int movedElsewhere = 0;
        int errored = 0;
        int unreached = 0;

        int reachedTheDecisionPoint = 0;
        int decisionPointWroteRequested = 0;
        int decisionPointWroteElsewhere = 0;
        int decisionPointRefused = 0;
        // Arm B's own veto: a turn-3 search of the appointment's day rather than the requested one is
        // #17 under rule 13, which is a different and already-answered question.
        int searchedTheOldDayInstead = 0;

        Map<String, Integer> landedOn = new LinkedHashMap<>();

        for (int trial = 1; trial <= CONVERSATIONS; trial++) {
            jdbc.update("delete from appointments");
            String id = aria.bookedAt(aria.at(target, 12, 0));
            if ("B".equals(ARM)) {
                blockFifteenHundred(aria, target);
            }
            String code = jdbc.queryForObject(
                    "select confirmation_code from appointments where id = ?::uuid", String.class, id);

            ConversationService.StartedConversation started = conversations.start(Optional.empty());
            OffsetDateTime wanted;
            try {
                conversations.respond(
                        started.sessionToken(),
                        "I would like to move my appointment. Confirmation code " + code + ", booked with "
                                + BookingScenario.CUSTOMER_PHONE + ".");

                if ("B".equals(ARM)) {
                    // Truthfully refused, and deliberately so. Not scored.
                    conversations.respond(started.sessionToken(), "Please move it to 15:00 on " + target + ".");
                    wanted = aria.at(requestedDay, 15, 0);
                    conversations.respond(
                            started.sessionToken(), "Then 15:00 on " + requestedDay + ", please.");
                    conversations.respond(
                            started.sessionToken(),
                            "Yes — 15:00 on " + requestedDay + ". Go ahead and move it.");
                } else {
                    conversations.respond(started.sessionToken(), "What else have you got later that same day?");
                    Optional<OffsetDateTime> picked = pickAnOfferedSlot(started.conversationId(), requestedDay);
                    if (picked.isEmpty()) {
                        // The search offered nothing on the day, so this trial never reached the
                        // decision point and cannot answer the question. Counted and printed rather
                        // than folded into a denominator — the same rule the ERRORED count follows.
                        unreached++;
                        System.out.printf(
                                "%3d  UNREACHED  no slot on %s at or after 14:00 was offered  searched=%s%n",
                                trial, requestedDay, searchedDates(started.conversationId()));
                        continue;
                    }
                    wanted = picked.get();
                    String spoken = wanted.atZoneSameInstant(BookingScenario.TBILISI)
                            .toLocalTime()
                            .format(SPOKEN);
                    conversations.respond(started.sessionToken(), "Please move it to " + spoken + ".");
                    // Named again rather than a bare "yes", which is T16: a bare acceptance refers to
                    // nothing and ends the conversation a turn short of the write.
                    conversations.respond(
                            started.sessionToken(), "Yes — " + spoken + " please. Go ahead and move it.");
                }
            } catch (RuntimeException e) {
                // T36. An uncounted error is reported as the behaviour being measured, and a provider
                // outage then reads as the model refusing every write.
                errored++;
                System.out.printf("%3d  ERROR  %s: %s%n", trial, e.getClass().getSimpleName(), e.getMessage());
                continue;
            }

            String landed = jdbc.queryForObject(
                    "select to_char(starts_at at time zone 'Asia/Tbilisi', 'YYYY-MM-DD HH24:MI') "
                            + "from appointments where id = ?::uuid",
                    String.class,
                    id);
            landedOn.merge(landed, 1, Integer::sum);

            List<String> tools = jdbc.queryForList(
                    "select tool_name from ai_messages where role = 'TOOL' and conversation_id = ? "
                            + "order by created_at",
                    String.class,
                    started.conversationId());
            List<String> offered =
                    jdbc.queryForList(ProbeQueries.OFFERED_SLOT_STARTS, String.class, started.conversationId());
            List<String> searched = searchedDates(started.conversationId());
            List<String> prose =
                    jdbc.queryForList(ProbeQueries.ASSISTANT_PROSE, String.class, started.conversationId());
            boolean truncated = Boolean.TRUE.equals(
                    jdbc.queryForObject(ProbeQueries.ANY_SEARCH_TRUNCATED, Boolean.class, started.conversationId()));

            OffsetDateTime wantedInstant = wanted;
            boolean slotWasOffered = offered.stream()
                    .map(OffsetDateTime::parse)
                    .anyMatch(start -> start.toInstant().equals(wantedInstant.toInstant()));
            boolean searchedTheRequestedDay = searched.contains(requestedDay.toString());
            boolean atTheDecisionPoint = searchedTheRequestedDay && slotWasOffered;
            if (atTheDecisionPoint) {
                reachedTheDecisionPoint++;
            }
            if ("B".equals(ARM) && !searchedTheRequestedDay && searched.contains(target.toString())) {
                searchedTheOldDayInstead++;
            }

            String expected = requestedDay + " "
                    + wanted.atZoneSameInstant(BookingScenario.TBILISI).toLocalTime().format(SPOKEN);
            String evidence =
                    "wanted=%s  landed=%s  searched=%s  requested day searched=%s  offered=%s  truncated=%s  %d slots"
                            .formatted(
                                    expected,
                                    landed,
                                    searched,
                                    searchedTheRequestedDay,
                                    slotWasOffered,
                                    truncated,
                                    offered.size());

            if (expected.equals(landed)) {
                moved++;
                if (atTheDecisionPoint) {
                    decisionPointWroteRequested++;
                }
                System.out.printf("%3d  MOVED      %s%n     said: %s%n", trial, evidence, lastTurns(prose, 1));
            } else if (!tools.contains("reschedule_appointment")) {
                refused++;
                if (atTheDecisionPoint) {
                    decisionPointRefused++;
                }
                System.out.printf(
                        "%3d  REFUSED%s  %s%n     said: %s%n",
                        trial,
                        atTheDecisionPoint ? "  <-- MECHANISM 2, the slot was on the table" : "",
                        evidence,
                        lastTurns(prose, 3));
            } else {
                movedElsewhere++;
                if (atTheDecisionPoint) {
                    decisionPointWroteElsewhere++;
                }
                System.out.printf(
                        "%3d  ELSEWHERE  %s  tools=%s%n     said: %s%n",
                        trial, evidence, tools, lastTurns(prose, 1));
            }
        }

        int ran = CONVERSATIONS - errored - unreached;
        System.out.printf(
                "%nARM %s — %d moved, %d REFUSED, %d moved elsewhere, of %d scored trials "
                        + "(%d ERRORED, %d UNREACHED, %d configured)%n"
                        + "landed on: %s%n",
                ARM, moved, refused, movedElsewhere, ran, errored, unreached, CONVERSATIONS, landedOn);

        System.out.printf(
                "%nDECISION POINT — the requested day searched AND the wanted slot among those returned.%n"
                        + "reached in %d of %d scored trials: %d wrote it, %d wrote elsewhere, %d REFUSED%n"
                        + "MECHANISM 2 = %d/%d%s%n",
                reachedTheDecisionPoint,
                ran,
                decisionPointWroteRequested,
                decisionPointWroteElsewhere,
                decisionPointRefused,
                decisionPointRefused,
                reachedTheDecisionPoint,
                reachedTheDecisionPoint == 0
                        ? "  (no trial reached it — this arm says NOTHING about #40)"
                        : " = %.1f%%".formatted(100.0 * decisionPointRefused / reachedTheDecisionPoint));

        // The pre-registered vetoes, checked here so an arm cannot be read past one of them.
        if (errored > 5) {
            System.out.printf(
                    "%nVETO — %d trials errored, over the 5 the pre-registration allows. This arm is "
                            + "VOID: it measures a partial run, not the Receptionist (T36).%n",
                    errored);
        }
        // The pre-registration writes these as 45/50 and 25/50. Scaled to the configured sample so a
        // two-trial smoke run does not print a veto it cannot possibly satisfy -- the fraction at
        // fifty trials is exactly the pre-registered one.
        int controlFloor = (int) Math.ceil(0.9 * CONVERSATIONS);
        int offTargetCeiling = CONVERSATIONS / 2;
        if ("A".equals(ARM) && reachedTheDecisionPoint < controlFloor) {
            System.out.printf(
                    "%nVETO — arm A reached the decision point in %d of %d, under the %d (90%%, "
                            + "pre-registered as 45/50) it requires. Arm A's whole claim is that it "
                            + "reaches it by construction; this arm measures the harness and not the "
                            + "model.%n",
                    reachedTheDecisionPoint, CONVERSATIONS, controlFloor);
        }
        if ("B".equals(ARM) && searchedTheOldDayInstead > offTargetCeiling) {
            System.out.printf(
                    "%nVETO — arm B searched the appointment's own day instead of the requested one in "
                            + "%d of %d trials, over the %d (50%%, pre-registered as 25/50) it allows. "
                            + "That is #17 under rule 13, a different and already-answered question.%n",
                    searchedTheOldDayInstead, CONVERSATIONS, offTargetCeiling);
        }
        if (errored > 0) {
            System.out.printf(
                    "%nWARNING: %d of %d trials never reached the model.%n", errored, CONVERSATIONS);
        }
    }

    /**
     * The first slot the tool offered on the requested day at or after 14:00 — two clear hours after
     * the 12:00 appointment, so "later that day" is unambiguous.
     *
     * <p>Read out of the database rather than out of the model's prose. The question is whether the
     * Receptionist refuses a slot <em>the tool returned</em>, and parsing a time out of a sentence
     * would make the trial turn on this harness's reading of English.
     */
    private Optional<OffsetDateTime> pickAnOfferedSlot(UUID conversation, LocalDate day) {
        return jdbc.queryForList(ProbeQueries.OFFERED_SLOT_STARTS, String.class, conversation).stream()
                .map(OffsetDateTime::parse)
                .filter(start -> {
                    var local = start.atZoneSameInstant(BookingScenario.TBILISI);
                    return local.toLocalDate().equals(day) && !local.toLocalTime().isBefore(LocalTime.of(14, 0));
                })
                .findFirst();
    }

    private List<String> searchedDates(UUID conversation) {
        return jdbc.queryForList(ProbeQueries.SEARCHED_DATES, String.class, conversation);
    }

    /**
     * Arm B only: a real appointment, so the Receptionist's first decline is true.
     *
     * <p><strong>This threw and killed the first arm B, on 2026-09-22.</strong> The fixture's access
     * token lives fifteen minutes and a fifty-trial arm runs longer than that, so a 401 here is
     * expected rather than exceptional — {@code BookingScenario.bookedAt} carries a refresh-and-retry
     * for exactly that reason, and this method was written with a copy of it that
     * <strong>discarded the refresh's own response</strong>. When the refresh itself failed, the
     * retry re-sent the same dead token, and the arm died at the fixture with a 401 whose cause was
     * not in the message.
     *
     * <p>So the refresh is checked, and a failed refresh falls back to logging the owner in again —
     * the fixture's own constants, the same pair {@code BookingScenario.open} registers with. Both
     * bodies go into the failure message if it still cannot book, because the thing that made the
     * first failure hard to read was that it reported the symptom and not the step that produced it.
     *
     * <p>It throws rather than skipping the trial <strong>on purpose</strong>: an arm B whose blocker
     * silently failed to book is an arm A wearing arm B's label, and it would print a refusal rate
     * for a scenario that never happened.
     */
    private void blockFifteenHundred(BookingScenario aria, LocalDate day) {
        ResponseEntity<String> response = bookTheBlocker(aria, day);
        String refreshBody = "not attempted";
        if (response.getStatusCode().value() == 401) {
            ResponseEntity<String> refreshed = aria.owner.post("/auth/refresh", null);
            refreshBody = refreshed.getStatusCode() + " " + refreshed.getBody();
            if (!refreshed.getStatusCode().is2xxSuccessful()) {
                aria.owner.login(BookingScenario.OWNER_EMAIL, BookingScenario.PASSWORD);
            }
            response = bookTheBlocker(aria, day);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "Arm B could not block 15:00 on %s: %s (refresh: %s)"
                            .formatted(day, response.getBody(), refreshBody));
        }
    }

    private ResponseEntity<String> bookTheBlocker(BookingScenario aria, LocalDate day) {
        return aria.book(aria.at(day, 15, 0), aria.employeeId, "Davit Kapanadze", BLOCKER_PHONE);
    }

    private static String lastTurns(List<String> prose, int n) {
        if (prose.isEmpty()) {
            return "(no prose — every assistant turn was a bare tool call)";
        }
        return String.join("  ||  ", prose.subList(Math.max(0, prose.size() - n), prose.size()));
    }
}
