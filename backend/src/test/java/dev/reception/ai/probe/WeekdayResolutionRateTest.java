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
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * <strong>Measures a rate rather than asserting a result.</strong> Fifty real conversations through
 * the real {@link ConversationService}, counting how often a spoken weekday reaches the availability
 * engine as a date that is actually that weekday.
 *
 * <p>It exists because the other two instruments cannot answer the question. The level-3 corpus runs
 * each case once, so at a 4% failure rate it is a coin that lands heads twenty-four times running
 * and teaches you nothing. The probe in {@code tools/receptionist-probe} runs hundreds of trials
 * for pennies and is a screen rather than an estimator — on the change this test was written for it
 * reported 73% and 99% where this test measured 84% and 96%.
 *
 * <p><strong>Asserts nothing.</strong> A test that failed below some threshold would be a gate on a
 * nondeterministic system, which is the thing docs/08-testing-strategy.md §7 keeps out of the
 * pipeline. It prints, and a person reads the number. That is the whole contract.
 *
 * <p>Tagged {@code probe}, so it never runs in CI and never runs alongside the corpus. Fifty
 * conversations take about four minutes and cost a few cents.
 *
 * <p>Three environment variables shape what is measured. {@code PROBE_CONVERSATIONS} is the sample
 * size. {@code PROBE_WEEKDAY} is the day spoken and {@code PROBE_ASKED_ON} the day it is spoken on;
 * the distance between them is the row of the seven-day list the model has to find, which makes the
 * pair the question rather than a detail of it. The last of the three refuses the run when today is
 * not that day, because the wrong cell answers in the same format as the right one.
 *
 * <p>{@code make rebaseline-weekday} is the recorded row-4 arm — the cell both baselines were taken
 * in — and archives its own evidence. Prefer it to driving Gradle by hand.
 *
 * <pre>{@code
 * export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
 * ./gradlew test -PincludeTags=probe --tests '*WeekdayResolutionRateTest'
 * }</pre>
 *
 * <p><strong>It now sees {@code resolve_date}, and the last recorded rate predates it.</strong> The
 * 142/150 this issue carries was measured before the resolver existed, so it is a baseline for a
 * prompt this repository no longer ships. The resolver was aimed at #17 — days beyond the seven-day
 * list — and the day this harness asks about is <em>inside</em> that list, where the prompt says to
 * look the date up rather than resolve it. Whether the model calls it anyway is therefore an open
 * question and a reportable one: a rate that moved because the arithmetic left the model and a rate
 * that moved because the list got easier to scan are the same number with opposite meanings. Every
 * resolver call is logged with its arguments and its answer, and each wrong search is classified as
 * using a date the resolver GAVE or one it NEVER GAVE (<strong>G17</strong>). The projections are
 * {@link ProbeQueries}' and are proven by {@code ProbeInstrumentationTest}, which runs in CI.
 *
 * <p>To compare two prompts, run it, change the prompt, run it again, and compare with Fisher's
 * exact test, one-sided — 42 of 50 against 48 of 50 is p = 0.046, and anything much closer than
 * that over fifty trials is not a result yet. Every p in this project is one-sided; a bare number
 * here read as a contradiction of the same comparison stated elsewhere, which is why the tail is
 * now written down.
 */
@Tag("probe")
@TestPropertySource(properties = "app.ai.scripted=false")
class WeekdayResolutionRateTest extends IntegrationTest {

    /**
     * Fifty separates 84% from 96%. Twenty does not, and sixteen is what missed it for a session.
     *
     * <p>Fifty is not enough for every question, though, and {@code PROBE_CONVERSATIONS} raises it.
     * Against a 96% arm the failures are so rare that fifty trials cannot see an improvement at all
     * — 48 of 50 against a perfect 50 of 50 is Fisher p = 0.25 one-sided, which is no result. It is
     * 0.49 two-sided, and quoting that number here contradicted nothing while looking like it did.
     * Separating 96% from 100% takes about a hundred and fifty per arm, and twelve minutes. Read
     * the environment rather than a system property because Gradle hands the test JVM the former
     * and not the latter.
     */
    private static final int CONVERSATIONS =
            Integer.parseInt(Optional.ofNullable(System.getenv("PROBE_CONVERSATIONS")).orElse("50"));

    /**
     * Which weekday to speak, and therefore which row of the seven-day list the model must find.
     *
     * <p>Configurable because the answer depends on it and no recorded measurement said so. Both
     * baselines in this project's history — 48 of 50, then 142 of 150 — were taken on a Thursday
     * asking about Monday, which is <strong>row 4</strong> of a seven-row list. That is one cell of
     * a 7×7 grid. The two observed failure modes are the FIRST row and the SATURDAY row, and
     * neither sits the same distance from row 4 as it does from row 1 or row 7, so a rate measured
     * on one starting weekday says nothing about the others. The list also shifts daily: the same
     * command run tomorrow asks a different question and answers it with the same-looking number.
     *
     * <p>Defaulted to MONDAY so the existing baseline stays comparable. Changing it produces a
     * <strong>new</strong> baseline rather than a better one — a rate taken on another weekday must
     * not be compared with 142/150, and the summary line below prints the conditions so it cannot
     * be by accident.
     */
    private static final DayOfWeek EXPECTED = DayOfWeek.valueOf(
            Optional.ofNullable(System.getenv("PROBE_WEEKDAY")).orElse("MONDAY").toUpperCase(Locale.ROOT));

    /**
     * The weekday the run must be taken <em>on</em>, refusing to measure rather than measuring the
     * wrong thing when it is not.
     *
     * <p>{@link #EXPECTED} says which day is spoken; this says which day it is spoken on, and the
     * pair is the cell of the 7×7 grid being measured. The row the model has to find is the
     * distance between them, so the asking day is not a detail of the run — it is half the
     * question. Both recorded baselines, 48 of 50 and 142 of 150, were taken on a THURSDAY asking
     * about MONDAY, which is row 4.
     *
     * <p>Unset by default, which measures whatever today is and prints it. Set it when a run is
     * meant to reproduce a specific baseline. The failure it prevents is not a wrong answer but a
     * <strong>right-looking</strong> one: a rate taken in the wrong cell comes out in the same
     * format, in the same range, with the same summary line, and the only thing separating it from
     * the number it will be compared against is a weekday nobody re-read.
     */
    private static final Optional<DayOfWeek> ASKED_ON = Optional.ofNullable(System.getenv("PROBE_ASKED_ON"))
            .map(day -> DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT)));

    /** Derived, so the day that is spoken and the day that is counted cannot drift apart. */
    private static final String UTTERANCE = "What have you got free next %s?"
            .formatted(EXPECTED.getDisplayName(TextStyle.FULL, Locale.ENGLISH));

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
    void how_often_does_a_spoken_weekday_reach_the_engine_as_that_weekday() {
        assumeTrue(properties.isConfigured(), "no OPENAI_API_KEY configured");

        // Checked before the fixture is built and before a single token is bought. The row is the
        // distance to the next occurrence of the spoken day, so the same command run a day later
        // asks a different question and answers it in an identical format.
        LocalDate today = LocalDate.now(clock.withZone(BookingScenario.TBILISI));
        ASKED_ON.ifPresent(required -> assumeTrue(
                today.getDayOfWeek() == required,
                ("PROBE_ASKED_ON=%s, but today is a %s. Nothing was measured: a rate taken on a "
                                + "different weekday cannot be compared with the baseline this run was "
                                + "meant to reproduce.")
                        .formatted(required, today.getDayOfWeek())));

        databaseCleaner.clean();
        BookingScenario.open(rest, port, clock);
        tenants.adopt(UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class)));

        // The conditions, printed before the trials rather than after, so a run that is abandoned
        // half way still says what it was asking. today+1 is row 1 of the prompt's list, so the
        // row is simply the distance to the next occurrence of the day being spoken.
        LocalDate target = today.with(TemporalAdjusters.next(EXPECTED));
        long row = ChronoUnit.DAYS.between(today, target);
        System.out.printf(
                "asking on a %s: \"%s\" resolves to %s, row %d of the seven-day list. %d trials.%n%n",
                today.getDayOfWeek(), UTTERANCE, target, row, CONVERSATIONS);

        int correct = 0;
        int searched = 0;
        // T36 — an uncounted error is reported as the behaviour you were measuring. The reschedule
        // harness learned this the day the account ran out of credits: it folded thirty-five failed
        // calls into "never wrote" and printed a total behavioural collapse that had not happened.
        // This loop used to `continue` on an exception without counting it, leaving the denominator
        // at CONVERSATIONS — so an outage here would have read as the resolution rate cratering.
        int errored = 0;
        // G17 — log the whole tool call, not the one tool the endpoint reads. The resolver arrived
        // after this harness was written and this harness could not see it, which is the same gap
        // that left the fourth candidate's veto unresolved in the experiment it was built for.
        int resolverWasCalled = 0;
        Map<String, Integer> resolverAsked = new TreeMap<>();
        int wrongOnADateTheResolverGave = 0;
        int wrongOnADateTheResolverNeverGave = 0;
        for (int trial = 1; trial <= CONVERSATIONS; trial++) {
            // A fresh conversation each time. Sequentially, because TenantContext is thread-bound
            // and a parallel run would be measuring the fixture rather than the model.
            ConversationService.StartedConversation started = conversations.start(Optional.empty());
            try {
                conversations.respond(started.sessionToken(), UTTERANCE);
            } catch (RuntimeException e) {
                // The message, not only the class name: "no credits remaining" and a read timeout
                // are both RuntimeException and mean entirely different things about the run.
                errored++;
                System.out.printf("%2d  ERROR %s: %s%n", trial, e.getClass().getSimpleName(), e.getMessage());
                continue;
            }

            List<String> dates =
                    jdbc.queryForList(ProbeQueries.SEARCHED_DATES, String.class, started.conversationId());

            // Every resolve_date call, as "MONDAY+0 -> 2026-09-14": the arguments AND the answer.
            // Recorded here even though the day asked about is INSIDE the seven-day list, where the
            // prompt tells the model to look the date up rather than resolve it. That makes a call
            // a deviation from the rule — and possibly a beneficial one, since this issue's defect
            // is mis-scanning the list and the resolver does the arithmetic in code. Either way it
            // must be visible, because a rate that moved for that reason and a rate that moved
            // because the list got easier to scan are the same number with opposite meanings.
            List<String> resolverCalls =
                    jdbc.queryForList(ProbeQueries.RESOLVER_CALLS, String.class, started.conversationId());
            resolverCalls.forEach(call -> resolverAsked.merge(call, 1, Integer::sum));
            if (!resolverCalls.isEmpty()) {
                resolverWasCalled++;
            }

            // The dates the resolver actually handed back in this conversation.
            List<String> resolverDates =
                    jdbc.queryForList(ProbeQueries.RESOLVER_DATES, String.class, started.conversationId());

            if (dates.isEmpty()) {
                // The model asked a question before searching. A legitimate turn, and not a
                // resolution either way, so it is reported and left out of the denominator's numerator.
                System.out.printf("%2d  NO SEARCH%s%n", trial, resolverSuffix(resolverCalls));
                continue;
            }

            searched++;
            boolean right = dates.stream().allMatch(d -> LocalDate.parse(d).getDayOfWeek() == EXPECTED);
            if (right) {
                correct++;
            } else {
                // The provenance question, decided per trial rather than inferred from a histogram:
                // did the model search a date the resolver handed it, or one that came from nowhere?
                // GAVE means the arithmetic moved into code and the wrong answer was asked for --
                // a wrong weekday or weeks_ahead. NEVER GAVE is this issue's original defect, the
                // model reading the wrong row of the list, intact.
                if (dates.stream().anyMatch(resolverDates::contains)) {
                    wrongOnADateTheResolverGave++;
                } else {
                    wrongOnADateTheResolverNeverGave++;
                }
            }
            System.out.printf("%2d  %-5s %s%s%n", trial, right ? "OK" : "WRONG", dates, resolverSuffix(resolverCalls));
        }

        // The errored count is printed on the SAME line as the sample size and never folded into
        // it. A run with errors is not a measurement of anything: read that number first.
        System.out.printf(
                "%n%d of %d conversations resolved %s correctly (%d searched at all, %d ERRORED)%n"
                        + "resolve_date was called in %d of %d trials; asked: %s%n"
                        + "of the wrong searches, %d used a date resolve_date GAVE and %d a date it NEVER GAVE%n"
                        + "asked on a %s, where %s was %s — row %d of seven. A rate measured on a "
                        + "different weekday is a different measurement: do not compare it with "
                        + "this one, and do not pool them.%n",
                correct,
                CONVERSATIONS,
                EXPECTED,
                searched,
                errored,
                resolverWasCalled,
                CONVERSATIONS,
                resolverAsked,
                wrongOnADateTheResolverGave,
                wrongOnADateTheResolverNeverGave,
                today.getDayOfWeek(),
                EXPECTED,
                target,
                row);
        if (errored > 0) {
            // Loud, and at the end where the rate is read. The outage that taught T36 printed a
            // plausible-looking number and the reader had to go hunting for the reason.
            System.out.printf(
                    "%nWARNING: %d of %d trials never reached the model. The rate above is NOT a "
                            + "measurement of the prompt — it is a measurement of a partial run.%n",
                    errored, CONVERSATIONS);
        }
    }

    /** Renders a trial's resolver calls inline, so the per-trial log says what the model asked. */
    private static String resolverSuffix(List<String> resolverCalls) {
        return resolverCalls.isEmpty() ? "" : "  resolve_date=" + resolverCalls;
    }
}
