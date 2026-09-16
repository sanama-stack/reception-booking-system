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
import java.time.OffsetDateTime;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * <strong>How often does the Receptionist refuse an authorised reschedule to a slot that is
 * free?</strong>
 *
 * <p>Built for <a href="https://github.com/sanama-stack/reception-booking-system/issues/40">issue
 * #40</a> and now the sharpest instrument for
 * <a href="https://github.com/sanama-stack/reception-booking-system/issues/17">#17</a>, which
 * absorbed it: two arms proved the refusal is #17's wrong-day search taking a different branch, and
 * #40 was closed on 2026-09-16. <strong>The history below is kept because the retractions are the
 * point.</strong> When filed, #40 had <strong>one observation and no rate</strong>. On 2026-09-15 the level-3
 * corpus caught the Receptionist telling a Customer that a free 15:00 slot was "already booked" and
 * refusing to move an appointment it had already proven ownership of. On 2026-09-16 the same case
 * passed. One failure in two runs establishes that the behaviour is reachable and intermittent, and
 * nothing else — the corpus runs each case once and structurally cannot do better.
 *
 * <p><strong>The question is not only "how often".</strong> Two explanations fit that refusal and
 * they point in opposite directions:
 *
 * <ul>
 *   <li><strong>The slot was offered and the model refused anyway.</strong> The Receptionist
 *       contradicted its own tool — an invention, and the phase 09 Definition-of-Done box
 *       <em>"It never states a slot, price or policy that did not come from a tool"</em>.
 *   <li><strong>The slot was never offered.</strong> The model reported what it was told, and the
 *       defect is upstream in the search — a window, a filter, or {@code MAX_SLOTS} truncation.
 * </ul>
 *
 * <p>So every trial records <strong>whether 15:00 was among the slots the tool actually returned</strong>
 * ({@link ProbeQueries#OFFERED_SLOT_STARTS}) and <strong>whether any search came back truncated</strong>
 * ({@link ProbeQueries#ANY_SEARCH_TRUNCATED}, a flag {@code FindAvailableSlotsTool} sets itself). The
 * cross-tabulation of refusal against those two is the finding; the bare refusal rate is not.
 *
 * <p><strong>The truncation candidate has zero trials and this is what replaces the guess.</strong>
 * It was inferred from one transcript whose offered list stopped at 14:45, one slot short of the
 * time in question. That is a plausible story and it was never a measurement, which is the error
 * this repository has now paid for three times over on #17 (T195, T197).
 *
 * <p><strong>15:00 is free by construction.</strong> The fixture opens 09:00–17:00 and the diary is
 * emptied before each trial, so the only thing on the target day is the 12:00 this harness books.
 * A refusal is therefore always wrong, and no trial needs a judgement about whether the model was
 * right to decline.
 *
 * <p><strong>The first arm — 2026-09-16, fifty trials, 0 errored, target {@code today + 12}.</strong>
 *
 * <table border="1">
 *   <caption>Outcomes</caption>
 *   <tr><th>Outcome</th><th>n</th><th>Rate</th><th>Clopper-Pearson</th></tr>
 *   <tr><td>REFUSED — this issue</td><td>7/50</td><td>14.0%</td><td>[5.8%, 26.7%]</td></tr>
 *   <tr><td>ELSEWHERE — #17's wrong date</td><td>31/50</td><td>62.0%</td><td>[47.2%, 75.3%]</td></tr>
 *   <tr><td>Correct</td><td>12/50</td><td>24.0%</td><td>[13.1%, 38.2%]</td></tr>
 * </table>
 *
 * <p><strong>Of the seven refusals, ZERO had 15:00 among the offered slots.</strong> That is the
 * arm's finding and it contradicts the issue's own title: the Receptionist was <em>not</em> inventing
 * a conflict, it was reporting a slot it had genuinely not been given. Five of the seven followed a
 * search the tool marked {@code truncated}, each offering exactly thirty slots — {@code MAX_SLOTS}
 * on the nose. Two did not, offering twenty-nine with {@code truncated} false, so the cap is not the
 * whole story.
 *
 * <p><strong>The second arm settled it, and the answer closed #40 into #17.</strong> That arm added
 * the searched-day column the first one lacked — {@link ProbeQueries#SEARCHED_DATES}, which already
 * existed and had simply not been read, G17 for the third time in the instrument built to close it.
 * Fifty trials, 2 errored so the denominator is 48: refused 9/48 = 18.8%, wrong date 29/48 = 60.4%,
 * correct 10/48 = 20.8%, and wrong by either route <strong>38/48 = 79.2%</strong>, CI [65.0, 89.5].
 * Consistent with the first arm at p = 0.36.
 *
 * <p><strong>All nine refusals searched {@code booked + 1} and nothing else. Zero searched the day
 * the Customer named.</strong> So the refusal is #17's wrong-day search reaching the Customer as a
 * decline rather than as a wrong booking: on the wrong day the model either finds 15:00 and books it
 * there, or {@code MAX_SLOTS} cuts the list before 15:00 and it has nothing to offer. <strong>The cap
 * selects the failure mode; it does not cause it</strong> — the requested day was never searched, so
 * the requested slot could not have been offered under any cap. An earlier reading of this harness's
 * own first arm called truncation the likely mechanism, on five-of-seven; that was support for the
 * wrong proposition and is retracted.
 *
 * <p><strong>This test therefore measures #17, and #40 is closed.</strong> Its value is that it
 * separates the three outcomes: a candidate that merely converts refusals into wrong bookings moves
 * the refusal rate and fixes nothing, and only a harness that counts them apart can say so.
 *
 * <p><strong>#17 competes with #40 for the same scenario, and it wins most trials.</strong> Measured
 * on the two-trial smoke run that proved this harness: both trials wrote to {@code booked + 1} —
 * moving a 2026-09-28 appointment to 2026-09-29 at the requested 15:00 — which is #17's defect and
 * not this one. They are reported as {@code ELSEWHERE} and are deliberately kept <em>in</em> the
 * denominator, because a trial that wrote somewhere is a trial that did not refuse; folding them
 * out would inflate the refusal rate by exactly the amount #17 is firing.
 *
 * <p>The consequence is a power problem and it should be understood before an arm is bought. #17's
 * ISO arm ran at roughly three quarters wrong on 2026-09-15, and #40 was one failure in two corpus
 * runs. If those hold, most trials in an arm answer #17's question rather than this one, and a
 * fifty-trial arm may produce only a handful of refusals — enough to confirm the behaviour is
 * reachable, not enough for a tight interval. That is the same wall #15 hit, and it is a fact about
 * how rare the defect is rather than a flaw in the instrument. <strong>Decide the sample size
 * against that, and do not read a zero-refusal arm as evidence the defect is gone.</strong>
 *
 * <p><strong>Asserts nothing</strong>, like its two siblings. A threshold here would be a gate on a
 * nondeterministic system, which docs/08-testing-strategy.md §7 keeps out of the pipeline. It
 * prints, and a person reads the number.
 *
 * <pre>{@code
 * export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
 * PROBE_CONVERSATIONS=50 ./gradlew test -PincludeTags=probe \
 *   --tests '*RescheduleRefusalRateTest' --rerun
 * }</pre>
 *
 * <p>Tagged {@code probe}: never in CI. Three turns plus tool loops per trial, so budget in the
 * region of a quarter of an hour for fifty, and read the result out of the XML — Gradle does not
 * stream it, and {@code BUILD SUCCESSFUL} says nothing about whether a single conversation reached
 * the model.
 */
@Tag("probe")
@TestPropertySource(properties = "app.ai.scripted=false")
class RescheduleRefusalRateTest extends IntegrationTest {

    private static final int CONVERSATIONS =
            Integer.parseInt(Optional.ofNullable(System.getenv("PROBE_CONVERSATIONS")).orElse("50"));

    /**
     * How far out the appointment and the requested time sit.
     *
     * <p>Held configurable and <strong>printed</strong>, because T194: the distance to the horizon
     * is an uncontrolled variable in every reschedule rate this project has recorded, and two arms
     * taken on different weekdays are not comparable until it is stated. A weekend is refused
     * rather than measured — the business opens Monday to Friday, so every trial would answer
     * NO SLOTS and the summary would read as a collapse that had not happened.
     */
    private LocalDate targetDate() {
        LocalDate today = LocalDate.now(clock.withZone(BookingScenario.TBILISI));
        String configured = System.getenv("PROBE_TARGET_DAYS");
        LocalDate target = configured == null
                ? today.plusDays(7).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                : today.plusDays(Integer.parseInt(configured));
        DayOfWeek day = target.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            throw new IllegalArgumentException(
                    "PROBE_TARGET_DAYS puts the target on %s (%s), which this business is closed on. Pick a distance that lands Monday to Friday."
                            .formatted(target, day));
        }
        return target;
    }

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

    @Test
    void how_often_is_an_authorised_move_to_a_free_slot_refused() {
        assumeTrue(properties.isConfigured(), "no OPENAI_API_KEY configured");

        databaseCleaner.clean();
        BookingScenario aria = BookingScenario.open(rest, port, clock);
        tenants.adopt(UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class)));

        LocalDate target = targetDate();
        LocalDate today = LocalDate.now(clock.withZone(BookingScenario.TBILISI));
        OffsetDateTime wanted = aria.at(target, 15, 0);

        // Printed before the trials, so a run abandoned half way still says what it was asking.
        System.out.printf(
                "%d trials. Appointment at 12:00 on %s (%s, today+%d); the Customer asks for 15:00 "
                        + "the same day, which is free by construction.%n%n",
                CONVERSATIONS,
                target,
                target.getDayOfWeek(),
                java.time.temporal.ChronoUnit.DAYS.between(today, target));

        int moved = 0;
        int refused = 0;
        int movedElsewhere = 0;
        int errored = 0;

        // The cross-tabulation that is the actual finding. A refusal with the slot on the table is
        // an invention; a refusal without it is an upstream search defect. The bare rate cannot
        // tell them apart and would be quoted as if it could.
        int refusedWithSlotOffered = 0;
        int refusedWithSlotNotOffered = 0;
        int refusedAfterTruncatedSearch = 0;
        // Splits the two explanations for "15:00 was not offered": the cap hid it, or the search
        // never covered the day it was on.
        int refusedHavingSearchedTheTargetDay = 0;
        // The same question over EVERY trial, not only the refusals. The 2026-09-16 arms counted it
        // for refusals alone, which left the fifth candidate's pre-registration promising a metric
        // with no control to compare against. Named for the tally, so it cannot collide with the
        // per-trial boolean below.
        int trialsSearchingTheTargetDay = 0;
        Map<String, Integer> landedOn = new LinkedHashMap<>();

        for (int trial = 1; trial <= CONVERSATIONS; trial++) {
            // A fresh diary each trial: the previous trial may have moved an appointment onto
            // 15:00, and a diary that fills up starts refusing it for reasons that are not #40.
            jdbc.update("delete from appointments");
            String id = aria.bookedAt(aria.at(target, 12, 0));
            String code = jdbc.queryForObject(
                    "select confirmation_code from appointments where id = ?::uuid", String.class, id);

            ConversationService.StartedConversation started = conversations.start(Optional.empty());
            try {
                conversations.respond(
                        started.sessionToken(),
                        "I would like to move my appointment. Confirmation code " + code + ", booked with "
                                + BookingScenario.CUSTOMER_PHONE + ".");
                conversations.respond(started.sessionToken(), "Please move it to 15:00 on " + target + ".");
                // Named again rather than accepted with a bare "yes", which is T16: a bare
                // acceptance refers to nothing and ends the conversation a turn short of the write,
                // which would show up here as a refusal and is not one.
                conversations.respond(started.sessionToken(), "Yes — 15:00 please. Go ahead and move it.");
            } catch (RuntimeException e) {
                // T36. An uncounted error is reported as the behaviour being measured, and a
                // provider outage then reads as the model refusing every write.
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
            boolean slotWasOffered = offered.stream()
                    .map(OffsetDateTime::parse)
                    .anyMatch(start -> start.toInstant().equals(wanted.toInstant()));
            boolean truncated = Boolean.TRUE.equals(
                    jdbc.queryForObject(ProbeQueries.ANY_SEARCH_TRUNCATED, Boolean.class, started.conversationId()));

            // WHICH DAY WAS SEARCHED, without which "15:00 was not offered" is unreadable.
            //
            // The 2026-09-16 arm recorded offered/truncated and not this, and could not tell a slot
            // hidden by MAX_SLOTS from a slot that was never in range because the search was aimed
            // at the wrong day -- with #17 firing in 31 of 50 trials, the second is not a remote
            // possibility. G17 for the third time, in the instrument built to close it: the
            // projection already existed and was simply not read.
            List<String> searched =
                    jdbc.queryForList(ProbeQueries.SEARCHED_DATES, String.class, started.conversationId());
            boolean searchedTheTargetDay = searched.contains(target.toString());
            if (searchedTheTargetDay) {
                trialsSearchingTheTargetDay++;
            }

            String expected = target + " 15:00";
            if (expected.equals(landed)) {
                moved++;
                System.out.printf("%3d  MOVED%n", trial);
            } else if (!tools.contains("reschedule_appointment")) {
                // #40 exactly: authorised, asked twice, and no write attempted at all.
                refused++;
                if (slotWasOffered) {
                    refusedWithSlotOffered++;
                } else {
                    refusedWithSlotNotOffered++;
                }
                if (truncated) {
                    refusedAfterTruncatedSearch++;
                }
                if (searchedTheTargetDay) {
                    refusedHavingSearchedTheTargetDay++;
                }
                System.out.printf(
                        "%3d  REFUSED  15:00 offered=%s  truncated=%s  offered=%d slots  "
                                + "searched=%s  target searched=%s%n",
                        trial, slotWasOffered, truncated, offered.size(), searched, searchedTheTargetDay);
            } else {
                // It wrote, but not where it was asked to. That is #17's territory, not #40's, and
                // pooling the two would make each look like the other.
                movedElsewhere++;
                System.out.printf("%3d  ELSEWHERE  landed=%s  tools=%s%n", trial, landed, tools);
            }
        }

        // The errored count on the same line as the sample size, never folded into it.
        System.out.printf(
                "%n%d moved, %d REFUSED, %d moved elsewhere, of %d trials (%d ERRORED)%n"
                        + "the requested day was searched at all in %d of %d trials%n"
                        + "of the refusals: %d had 15:00 among the offered slots, %d did not, "
                        + "%d followed a search the tool marked truncated, and %d actually searched "
                        + "the target day at all%n"
                        + "landed on: %s%n"
                        + "target %s, today+%d. A rate taken at a different distance is a different "
                        + "measurement (T194): do not compare it with this one.%n",
                moved,
                refused,
                movedElsewhere,
                CONVERSATIONS,
                errored,
                trialsSearchingTheTargetDay,
                CONVERSATIONS - errored,
                refusedWithSlotOffered,
                refusedWithSlotNotOffered,
                refusedAfterTruncatedSearch,
                refusedHavingSearchedTheTargetDay,
                landedOn,
                target,
                java.time.temporal.ChronoUnit.DAYS.between(today, target));

        if (refusedWithSlotOffered > 0) {
            System.out.printf(
                    "%n%d refusal(s) happened with 15:00 ON THE TABLE — the Receptionist contradicted "
                            + "its own tool. That is the invention #40 describes, and it is not "
                            + "explained by truncation.%n",
                    refusedWithSlotOffered);
        }
        if (errored > 0) {
            System.out.printf(
                    "%nWARNING: %d of %d trials never reached the model. The rates above are NOT a "
                            + "measurement of the Receptionist — they are a measurement of a partial run.%n",
                    errored, CONVERSATIONS);
        }
    }
}
