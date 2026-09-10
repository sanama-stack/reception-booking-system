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
import java.util.List;
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
 * <pre>{@code
 * export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
 * ./gradlew test -PincludeTags=probe --tests '*WeekdayResolutionRateTest'
 * }</pre>
 *
 * <p>To compare two prompts, run it, change the prompt, run it again, and compare with Fisher's
 * exact test — 42 of 50 against 48 of 50 is p = 0.046, and anything much closer than that over
 * fifty trials is not a result yet.
 */
@Tag("probe")
@TestPropertySource(properties = "app.ai.scripted=false")
class WeekdayResolutionRateTest extends IntegrationTest {

    /** Fifty separates 84% from 96%. Twenty does not, and sixteen is what missed it for a session. */
    private static final int CONVERSATIONS = 50;

    private static final String UTTERANCE = "What have you got free next Monday?";
    private static final DayOfWeek EXPECTED = DayOfWeek.MONDAY;

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
                "%n%d of %d conversations resolved %s correctly (%d searched at all)%n",
                correct, CONVERSATIONS, EXPECTED, searched);
    }
}
