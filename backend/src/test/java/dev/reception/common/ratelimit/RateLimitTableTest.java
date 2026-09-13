package dev.reception.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <strong>The limits table in docs/06-security.md §5 says what the code says.</strong>
 *
 * <p>Coverage has been derived since phase 11: a public endpoint no policy mentions fails the build
 * ({@link RateLimitCoverageTest}). <strong>The numbers were not.</strong> Change {@code refresh} to
 * ten an hour and §5 went on saying sixty, with nothing failing — the same defect one level down
 * from the hand-written endpoint list that test was written to replace, and the reason this class
 * exists.
 *
 * <p><strong>It found the drift it was written to prevent.</strong> Phase 09 added two chat
 * policies and §5 gained one row, reading <em>"20 / hour / conversation, 60 / hour / IP"</em>. The
 * sixty was right. The twenty belonged to {@code public-chat-session}, a policy with no row at all,
 * and was attributed to a mechanism that has no hourly limit — the conversation ceilings are five
 * tool calls a turn, forty messages and a twenty-message window, and they live in {@code
 * ConversationLimits}. One policy undocumented, one number filed under the wrong control, and two
 * sessions of this walk read past it.
 *
 * <p><strong>Why a test and not another {@code make check-*} target.</strong> Six sessions have now
 * asked whether the answer to every finding is one more bespoke gate. The shape that has held is:
 * derive it in a test where you can, probe the running system where you cannot, read configuration
 * only when neither is possible. This is the first category — {@link RateLimitProperties} is a plain
 * object and the truth can simply be asked for, rather than parsed out of Java by a documentation
 * tool that would then be fragile in the direction that matters.
 */
class RateLimitTableTest {

    /**
     * The table, located from the module rather than the working directory.
     *
     * <p>No other test in this suite reads a file outside {@code backend/}. This one must, because
     * the whole assertion is that two artefacts in different languages agree, and the alternative —
     * teaching the documentation tool to parse Java — replaces a derivation with a regex over source
     * code.
     */
    private static final Path SECURITY_DOC = Path.of("..", "docs", "06-security.md");

    /** {@code | `GET /health` | 60 / min / IP | … |}, with the emphasis §5 puts on one row. */
    private static final Pattern ROW = Pattern.compile(
            "^\\|\\s*`([A-Z]+) ([^`]+)`\\s*\\|\\s*\\*{0,2}(\\d+)\\s*/\\s*([^/|*]+?)\\s*/\\s*IP\\*{0,2}\\s*\\|");

    private static final Map<String, Duration> WINDOWS =
            Map.of("min", Duration.ofMinutes(1), "15 min", Duration.ofMinutes(15), "hour", Duration.ofHours(1));

    @Test
    @DisplayName("every policy in the code has a row in §5, with the same numbers")
    void the_table_documents_every_policy() {
        Map<String, String> table = tableRows();
        Map<String, String> code = codePolicies();

        Map<String, String> wrongOrMissing = new TreeMap<>();
        code.forEach((endpoint, budget) -> {
            String documented = table.get(endpoint);
            if (!budget.equals(documented)) {
                wrongOrMissing.put(endpoint, "code says " + budget + ", §5 says " + (documented == null ? "nothing" : documented));
            }
        });

        assertThat(wrongOrMissing)
                .as(
                        """
                        RateLimitProperties and docs/06-security.md §5 disagree. The code is the \
                        system and the table is the promise, so change whichever is wrong — but \
                        change one of them. A limit a reader cannot trust is worse than one they \
                        have to go and look up.""")
                .isEmpty();
    }

    @Test
    @DisplayName("and §5 documents no limit the code does not enforce")
    void the_table_promises_nothing_extra() {
        Map<String, String> table = tableRows();
        Map<String, String> code = codePolicies();

        Map<String, String> invented = new TreeMap<>();
        table.forEach((endpoint, budget) -> {
            if (!code.containsKey(endpoint)) {
                invented.put(endpoint, "§5 says " + budget + ", and no policy matches");
            }
        });

        assertThat(invented)
                .as(
                        """
                        These rows describe limits this application does not have. A documented \
                        limit that does not exist is the worst of the three states: it reads as \
                        protection, it survives review, and the endpoint is open.""")
                .isEmpty();
    }

    /**
     * The positive control, and the reason both assertions above are worth anything.
     *
     * <p>T89: each is that a map is empty, and one side of each is produced by a regular expression
     * over a Markdown file. Change the table's formatting — a column reordered, the backticks
     * dropped, the file moved — and the parse quietly returns nothing. {@link
     * #the_table_promises_nothing_extra()} then passes over an empty table, which is exactly the
     * shape of the vacuum this walk has met three times. These are the assertions that fail in that
     * world.
     */
    @Test
    @DisplayName("the table was actually read, and the parse still recognises its rows")
    void the_parse_still_sees_the_table() {
        Map<String, String> table = tableRows();

        assertThat(table)
                .as("§5's table, parsed out of %s", SECURITY_DOC.toAbsolutePath().normalize())
                .hasSizeGreaterThanOrEqualTo(15)
                .containsEntry("POST /auth/login", "10 per PT15M")
                .containsEntry("GET /health", "60 per PT1M");
    }

    /** Every row of §5's table, as {@code "METHOD /pattern"} to {@code "<capacity> per <window>"}. */
    private static Map<String, String> tableRows() {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : read()) {
            Matcher row = ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            Duration window = WINDOWS.get(row.group(4));
            if (window == null) {
                throw new AssertionError("§5 row '" + line.strip() + "' states a window this test cannot read: '"
                        + row.group(4) + "'. Use one of " + new TreeMap<>(WINDOWS).keySet()
                        + ", or teach WINDOWS the new spelling — do not leave the row unparsed, because a row "
                        + "this regex skips is a row nothing checks.");
            }
            rows.put(row.group(1) + " " + row.group(2), row.group(3) + " per " + window);
        }
        return rows;
    }

    private static Map<String, String> codePolicies() {
        Map<String, String> policies = new LinkedHashMap<>();
        for (RateLimitPolicy policy : new RateLimitProperties().policies()) {
            policies.put(
                    policy.method().name() + " " + policy.pathPattern(), policy.capacity() + " per " + policy.window());
        }
        return policies;
    }

    private static List<String> read() {
        try {
            return Files.readAllLines(SECURITY_DOC, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Deliberately not a skip. A test that cannot find the document it exists to check has
            // learned nothing, and reporting that as success is the failure this class is about.
            throw new UncheckedIOException(
                    "Cannot read " + SECURITY_DOC.toAbsolutePath().normalize()
                            + ". This test reads the repository's documentation and is therefore sensitive to the "
                            + "working directory, which Gradle sets to the backend module.",
                    e);
        }
    }
}
