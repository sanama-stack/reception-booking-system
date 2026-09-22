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
 * <p><strong>That closure was retracted the next day and this paragraph said otherwise until
 * 2026-09-22.</strong> It read <em>"this test therefore measures #17, and #40 is closed"</em>. #40
 * was reopened on 2026-09-17 on the condition its own closing comment named — a refusal on a trial
 * that <em>did</em> search the requested day — and the baseline arm of the fifth candidate produced
 * two of them, both with 15:00 among the offered slots, one of them not truncated. So there are two
 * mechanisms behind a refusal and this test measures both:
 *
 * <ol>
 *   <li><strong>The wrong-day search</strong> — 21 of 23 pooled refusals, genuinely #17, removed by
 *       rule 13.
 *   <li><strong>A refusal of a slot the model was handed</strong> — 2 of 23, on the requested day,
 *       and the only part of #40 rule 13 does not touch.
 * </ol>
 *
 * <p>Its value is that it separates the three outcomes: a candidate that merely converts refusals
 * into wrong bookings moves the refusal rate and fixes nothing, and only a harness that counts them
 * apart can say so.
 *
 * <p><strong>Three things were added on 2026-09-22, before any arm was bought</strong>, for
 * {@code docs/experiments/2026-09-22-40-mechanism-2-scenario.md} §7. None of them costs a credit and
 * all three close holes that three paid arms had already been run through:
 *
 * <ul>
 *   <li><strong>Every trial prints its evidence</strong>, not just the refusals. {@code searched}
 *       and {@code offered} used to appear on the REFUSED branch alone, so the signature both
 *       mechanism-2 observations share — a wrong-day search <em>before</em> the right-day one — has
 *       no control in any arm run so far and cannot be recovered from them.
 *   <li><strong>The Receptionist's own words</strong>, via {@link ProbeQueries#ASSISTANT_PROSE}.
 *       Mechanism 2 had two observations and not one word of text, and <em>"that time is already
 *       booked"</em> and <em>"I cannot move it myself"</em> are different defects.
 *   <li><strong>The decision-point denominator</strong> — the requested day searched and 15:00
 *       returned — which no arm had ever printed. It is why #40's entry carried an unconditional
 *       {@code ~1.3%}: conditioned properly the same arms read 2 of 9 against 0 of 50.
 * </ul>
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

        // THE DECISION POINT, and the denominator #40 is actually about. A trial reached it if the
        // requested day was searched AND 15:00 came back among the slots -- that is, the model was
        // holding the thing it was asked for. No arm before 2026-09-22 printed this, which is why
        // #40's entry carried an UNCONDITIONAL rate: 2 events in 150 conversations reads as 1.3%,
        // but most of those conversations never reached the decision point at all because the
        // search went to the wrong day, so that fraction measures how often #17 fired as much as
        // anything of #40's. Conditioned properly the same arms give 2 of 9 against 0 of 50.
        // See docs/experiments/2026-09-22-40-mechanism-2-scenario.md §3.
        int reachedTheDecisionPoint = 0;
        int decisionPointWroteRequested = 0;
        int decisionPointWroteElsewhere = 0;
        int decisionPointRefused = 0;

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

            // EVERY TRIAL CARRIES ITS EVIDENCE, and until 2026-09-22 only the refusals did.
            //
            // `searched` and `offered` were printed on the REFUSED branch alone; MOVED printed the
            // word MOVED and nothing else. So the one signature both of #40's mechanism-2
            // observations share -- a wrong-day search BEFORE the right-day one -- has no control
            // anywhere in three arms and cannot be recovered from them: whether the trials that did
            // NOT refuse share it is simply unrecorded. T195, a signature consistent with two
            // explanations is evidence for neither, and the control cost nothing but this line.
            String evidence =
                    "landed=%s  searched=%s  target searched=%s  15:00 offered=%s  truncated=%s  offered=%d slots"
                            .formatted(
                                    landed,
                                    searched,
                                    searchedTheTargetDay,
                                    slotWasOffered,
                                    truncated,
                                    offered.size());

            // What the Receptionist SAID. #40's second mechanism has two observations and not one
            // word of text: "that time is already booked" is a false statement about a slot it was
            // handed, and "I cannot move it myself" is a refusal to use a tool it has. Different
            // defects, different fixes, and every arm so far classified a refusal without reading
            // it. Printed for every trial, one turn, so the refusals are not the only ones with
            // words -- a refusal gets one more turn below, because the decline may land in either
            // of the last two.
            List<String> prose =
                    jdbc.queryForList(ProbeQueries.ASSISTANT_PROSE, String.class, started.conversationId());
            // What the model SENT to the write tool. Applied to both rate harnesses at once and on
            // purpose: these two had near-identical resolver SQL by copy and a fix to one left the
            // other blind for as long as the resolver existed.
            List<String> writes =
                    jdbc.queryForList(ProbeQueries.WRITE_CALLS, String.class, started.conversationId());

            boolean atTheDecisionPoint = searchedTheTargetDay && slotWasOffered;
            if (atTheDecisionPoint) {
                reachedTheDecisionPoint++;
            }

            String expected = target + " 15:00";
            if (expected.equals(landed)) {
                moved++;
                if (atTheDecisionPoint) {
                    decisionPointWroteRequested++;
                }
                System.out.printf(
                        "%3d  MOVED      %s%n     wrote: %s%n     said: %s%n",
                        trial, evidence, callDump(writes), lastTurns(prose, 1));
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
                if (atTheDecisionPoint) {
                    decisionPointRefused++;
                }
                System.out.printf(
                        "%3d  REFUSED%s  %s%n     calls: %s%n     said: %s%n",
                        trial,
                        atTheDecisionPoint ? "  <-- MECHANISM 2, the slot was on the table" : "",
                        evidence,
                        callDump(jdbc.queryForList(
                                ProbeQueries.TOOL_CALLS_IN_CONVERSATION, String.class, started.conversationId())),
                        lastTurns(prose, 2));
            } else {
                // It wrote, but not where it was asked to. That is #17's territory, not #40's, and
                // pooling the two would make each look like the other.
                movedElsewhere++;
                if (atTheDecisionPoint) {
                    decisionPointWroteElsewhere++;
                }
                System.out.printf(
                        "%3d  ELSEWHERE  %s%n     calls: %s%n     said: %s%n",
                        trial,
                        evidence,
                        callDump(jdbc.queryForList(
                                ProbeQueries.TOOL_CALLS_IN_CONVERSATION, String.class, started.conversationId())),
                        lastTurns(prose, 1));
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

        // THE DECISION-POINT TABLE. Printed apart from the outcome counts above, because the two
        // denominators answer different questions and quoting one as the other is the correction
        // #40's entry carries: the unconditional rate is what a caller experiences, and the
        // conditional one is what this issue is about.
        System.out.printf(
                "%nDECISION POINT -- the requested day searched AND 15:00 among the slots returned, "
                        + "so the model was holding the thing it was asked for.%n"
                        + "reached in %d of %d trials that ran: %d wrote the requested slot, %d wrote "
                        + "elsewhere, %d REFUSED%n"
                        + "MECHANISM 2 = %d/%d%s -- this is #40's rate, and it is NOT the "
                        + "refusals-over-all-trials figure above.%n",
                reachedTheDecisionPoint,
                CONVERSATIONS - errored,
                decisionPointWroteRequested,
                decisionPointWroteElsewhere,
                decisionPointRefused,
                decisionPointRefused,
                reachedTheDecisionPoint,
                reachedTheDecisionPoint == 0
                        ? " (no trial reached the decision point -- this arm says NOTHING about #40)"
                        : " = %.1f%%".formatted(100.0 * decisionPointRefused / reachedTheDecisionPoint));

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

    /**
     * The last {@code n} things the Receptionist said, joined for one printed line.
     *
     * <p>Says {@code (no prose)} rather than printing nothing, because a trial whose every assistant
     * turn was a bare tool call is a real and readable outcome — the model searched and never spoke
     * — and a blank line reads as the harness having failed to look.
     */

    /**
     * Every tool call in the trial, one per line and indented, for a trial that needs explaining.
     *
     * <p>Not printed for a trial that landed exactly where it was asked to: a thirty-slot result
     * repeated fifty times buries the arm in its own log, and there is no question to answer.
     */
    private static String callDump(List<String> calls) {
        if (calls.isEmpty()) {
            return "(no tool call at all)";
        }
        return String.join("%n            ".formatted(), calls);
    }

    private static String lastTurns(List<String> prose, int n) {
        if (prose.isEmpty()) {
            return "(no prose — every assistant turn was a bare tool call)";
        }
        return String.join("  ||  ", prose.subList(Math.max(0, prose.size() - n), prose.size()));
    }
}
