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
 * <p>Two environment variables shape what is measured: {@code PROBE_CONVERSATIONS} is the sample
 * size, and {@code PROBE_WEEKDAY} is the day spoken — which is also the row of the seven-day list
 * the model has to find, and therefore part of the question rather than a detail of it.
 *
 * <pre>{@code
 * export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
 * ./gradlew test -PincludeTags=probe --tests '*WeekdayResolutionRateTest'
 * }</pre>
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

        databaseCleaner.clean();
        BookingScenario.open(rest, port, clock);
        tenants.adopt(UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class)));

        // The conditions, printed before the trials rather than after, so a run that is abandoned
        // half way still says what it was asking. today+1 is row 1 of the prompt's list, so the
        // row is simply the distance to the next occurrence of the day being spoken.
        LocalDate today = LocalDate.now(clock.withZone(BookingScenario.TBILISI));
        LocalDate target = today.with(TemporalAdjusters.next(EXPECTED));
        long row = ChronoUnit.DAYS.between(today, target);
        System.out.printf(
                "asking on a %s: \"%s\" resolves to %s, row %d of the seven-day list. %d trials.%n%n",
                today.getDayOfWeek(), UTTERANCE, target, row, CONVERSATIONS);

        int correct = 0;
        int searched = 0;
        for (int trial = 1; trial <= CONVERSATIONS; trial++) {
            // A fresh conversation each time. Sequentially, because TenantContext is thread-bound
            // and a parallel run would be measuring the fixture rather than the model.
            ConversationService.StartedConversation started = conversations.start(Optional.empty());
            try {
                conversations.respond(started.sessionToken(), UTTERANCE);
            } catch (RuntimeException e) {
                System.out.printf("%2d  ERROR %s%n", trial, e.getClass().getSimpleName());
                continue;
            }

            List<String> dates = jdbc.queryForList(
                    "select tool_arguments->>'date_from' from ai_messages "
                            + "where role = 'TOOL' and tool_name = 'find_available_slots' "
                            + "and conversation_id = ? order by created_at",
                    String.class,
                    started.conversationId());

            if (dates.isEmpty()) {
                // The model asked a question before searching. A legitimate turn, and not a
                // resolution either way, so it is reported and left out of the denominator's numerator.
                System.out.printf("%2d  NO SEARCH%n", trial);
                continue;
            }

            searched++;
            boolean right = dates.stream().allMatch(d -> LocalDate.parse(d).getDayOfWeek() == EXPECTED);
            if (right) {
                correct++;
            }
            System.out.printf("%2d  %-5s %s%n", trial, right ? "OK" : "WRONG", dates);
        }

        System.out.printf(
                "%n%d of %d conversations resolved %s correctly (%d searched at all)%n"
                        + "asked on a %s, where %s was %s — row %d of seven. A rate measured on a "
                        + "different weekday is a different measurement: do not compare it with "
                        + "this one, and do not pool them.%n",
                correct,
                CONVERSATIONS,
                EXPECTED,
                searched,
                today.getDayOfWeek(),
                EXPECTED,
                target,
                row);
    }
}
