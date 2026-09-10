package dev.reception.ai.probe;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.reception.ai.application.AiProperties;
import dev.reception.ai.application.ConversationService;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import dev.reception.tenancy.TenantAdoption;
import java.time.Clock;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <strong>How often does an authorised reschedule land on the date the Customer named?</strong>
 *
 * <p>The instrument for issue #17, which was filed on two failures in seven trials and explicitly
 * refused to call that a rate. This is the rate.
 *
 * <p>It is a sibling of {@link WeekdayResolutionRateTest} and works the same way: many real
 * conversations through the real {@link ConversationService}, counting a database outcome, asserting
 * nothing. The three-instrument table in {@code tools/receptionist-probe/README.md} explains why the
 * probe cannot answer this one — and here there is a second reason on top of saturation: **the probe
 * replays a single turn, and this defect needs three** (prove ownership, ask for the move, confirm).
 *
 * <p><strong>Three outcomes, not two.</strong> A trial that never writes is not a wrong write, and
 * pooling the two would flatter or damn the model depending on which way the model was failing that
 * day. {@code NO WRITE} is reported and kept out of the rate's denominator, exactly as
 * {@link WeekdayResolutionRateTest} treats a turn that asks a question instead of searching.
 *
 * <p><strong>{@code PROBE_DATE_STYLE} is the load-bearing knob.</strong> #17 was observed with the
 * Customer naming an explicit ISO date — {@code 2026-09-21} — which is *not* the weekday-resolution
 * question #15 is about. If the failure rate is the same when a weekday is named instead, the two
 * issues plausibly share a cause; if ISO fails and WEEKDAY does not, #17 is a distinct and sharper
 * defect, because an explicit date is the least ambiguous input a Customer can give. Measure ISO
 * first: it is the observed case.
 *
 * <pre>{@code
 * export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
 * PROBE_CONVERSATIONS=50 PROBE_DATE_STYLE=ISO ./gradlew test -PincludeTags=probe \
 *   --tests '*RescheduleDateFidelityRateTest' --rerun
 * }</pre>
 *
 * <p>Tagged {@code probe}: never in CI, never alongside the corpus. Each trial is three turns plus
 * tool loops, so it is several times slower per conversation than the weekday test — budget roughly
 * a quarter of an hour for fifty, and read the result out of the XML (Gradle does not stream it).
 */
@Tag("probe")
@TestPropertySource(properties = "app.ai.scripted=false")
class RescheduleDateFidelityRateTest extends IntegrationTest {

    private static final int CONVERSATIONS =
            Integer.parseInt(Optional.ofNullable(System.getenv("PROBE_CONVERSATIONS")).orElse("50"));

    /**
     * How the Customer names the target date. {@code ISO} says {@code 2026-09-21}; {@code WEEKDAY}
     * says "the Monday after next". Both name <strong>the same date</strong>, so the two arms differ
     * only in phrasing and are directly comparable.
     *
     * <p><strong>Why not a bare weekday.</strong> "Monday" can only ever name a date within seven
     * days, and every observed failure of this defect had the model searching
     * {@code [tomorrow, tomorrow+6]} — so a bare weekday's target is <em>always inside the very
     * window the model wrongly substitutes.</em> The failure mode is unreachable by construction,
     * and a bare-weekday arm would come back near-perfect while proving nothing. That is a fact
     * about the defect's reachability, not about the model: <strong>#17 needs a target more than a
     * week out, which a bare weekday cannot express.</strong>
     *
     * <p><strong>The residual ambiguity is measured, not assumed away.</strong> "The Monday after
     * next" is read by some English speakers as the nearest Monday instead. That reading is a
     * different date, it is defensible, and scoring it as this defect would inflate the arm. It is
     * counted separately as {@code OTHER READING} — see the loop.
     */
    private static final String DATE_STYLE =
            Optional.ofNullable(System.getenv("PROBE_DATE_STYLE")).orElse("ISO").toUpperCase(Locale.ROOT);

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
    void how_often_does_a_reschedule_land_on_the_date_the_customer_named() {
        assumeTrue(properties.isConfigured(), "no OPENAI_API_KEY configured");

        databaseCleaner.clean();
        BookingScenario aria = BookingScenario.open(rest, port, clock);
        tenants.adopt(UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class)));

        String bookedAt = aria.monday + " 12:00";
        String wanted = aria.monday + " 15:00";
        String dayName = aria.monday.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String spokenDate = "ISO".equals(DATE_STYLE) ? aria.monday.toString() : "the " + dayName + " after next";

        // The nearest occurrence of the same weekday: the other defensible reading of the phrase
        // above, and never the same date as the target, because BookingScenario puts its Monday
        // more than a week out.
        java.time.LocalDate otherReading = java.time.LocalDate.now(clock.withZone(BookingScenario.TBILISI))
                .with(java.time.temporal.TemporalAdjusters.next(aria.monday.getDayOfWeek()));

        System.out.printf(
                "%d trials. Appointment on %s; the Customer asks for 15:00 and names the day as \"%s\" "
                        + "(PROBE_DATE_STYLE=%s).%n%n",
                CONVERSATIONS, bookedAt, spokenDate, DATE_STYLE);

        int wrote = 0;
        int correct = 0;
        int otherReadingCount = 0;
        int searchedTheNamedDate = 0;
        Map<String, Integer> landedOn = new LinkedHashMap<>();
        List<Integer> wrongTrials = new ArrayList<>();

        for (int trial = 1; trial <= CONVERSATIONS; trial++) {
            // A fresh diary each trial: the previous trial moved an appointment, and a diary that
            // fills up starts refusing 15:00 for reasons that have nothing to do with #17. Both
            // foreign keys into appointments cascade, so one delete is enough.
            jdbc.update("delete from appointments");
            String id = aria.bookedAt(aria.at(aria.monday, 12, 0));
            String code = jdbc.queryForObject(
                    "select confirmation_code from appointments where id = ?::uuid", String.class, id);

            ConversationService.StartedConversation started = conversations.start(Optional.empty());
            try {
                conversations.respond(
                        started.sessionToken(),
                        "I would like to move my appointment. Confirmation code " + code + ", booked with "
                                + BookingScenario.CUSTOMER_PHONE + ".");
                conversations.respond(started.sessionToken(), "Please move it to 15:00 on " + spokenDate + ".");
                // The time is named again: a bare acceptance ends the conversation a turn short of
                // the write, which is trap T16 and would show up here as NO WRITE rather than as
                // the defect being measured.
                conversations.respond(started.sessionToken(), "Yes — 15:00 please. Go ahead and move it.");
            } catch (RuntimeException e) {
                System.out.printf("%3d  ERROR    %s%n", trial, e.getClass().getSimpleName());
                continue;
            }

            String landed = jdbc.queryForObject(
                    "select to_char(starts_at at time zone 'Asia/Tbilisi', 'YYYY-MM-DD HH24:MI') "
                            + "from appointments where id = ?::uuid",
                    String.class,
                    id);
            List<String> searches = jdbc.queryForList(
                    "select tool_arguments->>'date_from' from ai_messages "
                            + "where role = 'TOOL' and tool_name = 'find_available_slots' "
                            + "and conversation_id = ? order by created_at",
                    String.class,
                    started.conversationId());

            // Every search's whole argument set, and the error code if it was refused. The first
            // smoke run showed find_available_slots refused with VALIDATION_FAILED on all five
            // trials, which no assertion would have surfaced and which may be the mechanism rather
            // than a detail: a model whose search is rejected has to fall back to something.
            List<String> calls = jdbc.queryForList(
                    "select concat(tool_arguments::text, ' -> ', coalesce(tool_result->>'error', 'ok'), "
                            + "case when tool_result->>'message' is null then '' "
                            + "else concat(': ', tool_result->>'message') end) "
                            + "from ai_messages where role = 'TOOL' and tool_name = 'find_available_slots' "
                            + "and conversation_id = ? order by created_at",
                    String.class,
                    started.conversationId());
            calls.forEach(call -> System.out.printf("        %s%n", call));
            if (searches.contains(aria.monday.toString())) {
                searchedTheNamedDate++;
            }

            if (bookedAt.equals(landed)) {
                // Never moved. Not a wrong write, and not evidence either way about the date.
                System.out.printf("%3d  NO WRITE searched=%s%n", trial, searches);
                continue;
            }

            wrote++;
            landedOn.merge(landed == null ? "GONE" : landed.substring(0, 10), 1, Integer::sum);
            if (wanted.equals(landed)) {
                correct++;
                System.out.printf("%3d  OK%n", trial);
            } else if (landed != null && landed.startsWith(otherReading.toString())) {
                // The other reading of "the <day> after next". Not this defect, and not scored as
                // one — but it is not a success either, so it is out of both counts and named.
                otherReadingCount++;
                System.out.printf("%3d  OTHER READING landed=%s searched=%s%n", trial, landed, searches);
            } else {
                wrongTrials.add(trial);
                System.out.printf("%3d  WRONG    landed=%s searched=%s%n", trial, landed, searches);
            }
        }

        System.out.printf(
                "%n%d of %d writes landed on the named date (%d trials, %d never wrote)%n"
                        + "of those writes, %d took the other reading of the phrase (%s)%n"
                        + "the search included the named date in %d of %d trials%n"
                        + "landed on: %s%n"
                        + "wrong at trials: %s%n"
                        + "conditions: appointment %s, wanted %s, day named as \"%s\", style %s%n",
                correct,
                wrote,
                CONVERSATIONS,
                CONVERSATIONS - wrote,
                otherReadingCount,
                otherReading,
                searchedTheNamedDate,
                CONVERSATIONS,
                landedOn,
                wrongTrials,
                bookedAt,
                wanted,
                spokenDate,
                DATE_STYLE);
    }
}
