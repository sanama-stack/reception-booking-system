package dev.reception.ai.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.application.AiProperties;
import dev.reception.ai.application.ConversationLimits;
import dev.reception.ai.application.SystemPromptBuilder;
import dev.reception.ai.port.ToolSpec;
import dev.reception.ai.tools.ToolRegistry;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import dev.reception.tenancy.TenantAdoption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes the three files {@code tools/receptionist-probe/probe.py} replays against: the system
 * prompt the loop actually builds, the tool JSON it actually publishes, and the parameters the loop
 * is actually bounded by.
 *
 * <p><strong>{@code loop.json} is the newest and it closes a hand-sync.</strong> The probe used to
 * hold its own {@code MODEL} and its own {@code MAX_ROUNDS = 6}, the latter reconciled against
 * {@link ConversationLimits#MAX_TOOL_CALLS_PER_TURN} by a comment reading "this only has to be no
 * smaller" — a comparison between two numbers that were never in the same unit, since one counts
 * rounds and the other counts calls. Six rounds is forty-eight calls if the model asks for eight at
 * a time, which is measured in that file's self-test. The numbers are written here now and read
 * there, so there is no second copy of them to keep in step.
 *
 * <p><strong>This exists so that nobody hand-writes any of them.</strong> A probe with a
 * simplified tool set does not merely lose fidelity, it inverts results: a two-tool hand-written
 * probe scored 5 of 5 on the very prompt the faithful eight-tool one scored 2 of 5 on. The model
 * fills six arguments at once under a constrained decoder and that is where it goes wrong, so a
 * probe that removes the constraint is measuring a different system.
 *
 * <p>Tagged {@code probe} and excluded from the pipeline by {@code build.gradle.kts}, alongside
 * {@code llm}. It reaches no network and costs nothing, but it writes files, and a build task that
 * writes files into a working tree is a surprise nobody needs from CI.
 *
 * <pre>{@code
 * ./gradlew test -PincludeTags=probe --tests '*ProbeFixtureDumpTest'
 * }</pre>
 *
 * <p><strong>The tool payload is duplicated from {@code OpenAiChatModel.request}</strong> — the four
 * fields, and {@code strict: true} — because that method is private and making it visible for a
 * probe would be shaping production code around a diagnostic. The duplication is small and it is
 * real: if that method's shape changes, this must change with it, or the probe will confidently
 * measure a request the application never sends.
 */
@Tag("probe")
class ProbeFixtureDumpTest extends IntegrationTest {

    /** Beside the probe that reads them, and gitignored — they are outputs, not sources. */
    private static final Path OUTPUT = Path.of("tools/receptionist-probe/fixtures");

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
    private ToolRegistry registry;

    @Autowired
    private SystemPromptBuilder prompts;

    @Autowired
    private AiProperties aiProperties;

    @Test
    void dumps_the_prompt_the_tools_and_the_bounds_the_loop_would_use() throws Exception {
        databaseCleaner.clean();
        BookingScenario.open(rest, port, clock);
        tenants.adopt(UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class)));

        ObjectMapper json = new ObjectMapper();
        ArrayNode tools = json.createArrayNode();
        for (ToolSpec spec : registry.specs()) {
            ObjectNode entry = tools.addObject();
            entry.put("type", "function");
            ObjectNode function = entry.putObject("function");
            function.put("name", spec.name());
            function.put("description", spec.description());
            function.set("parameters", spec.parameters());
            function.put("strict", true);
        }

        ObjectNode loop = json.createObjectNode();
        loop.put("model", aiProperties.getModel());
        // The one hand-written fragment on this side, and it is written here rather than in the
        // probe so that the copy is in the file that already owes the reason. OpenAiChatModel
        // reaches the path through RestClient's base URL, so there is no constant to read; this is
        // the whole of what is restated, against three values that no longer are.
        loop.put("endpoint", aiProperties.getBaseUrl() + "/chat/completions");
        loop.put("max_tool_calls_per_turn", ConversationLimits.MAX_TOOL_CALLS_PER_TURN);

        Files.createDirectories(OUTPUT);
        Files.writeString(
                OUTPUT.resolve("tools.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(tools));
        Files.writeString(OUTPUT.resolve("prompt.txt"), prompts.build());
        Files.writeString(
                OUTPUT.resolve("loop.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(loop));

        System.out.println("Probe fixtures written to " + OUTPUT.toAbsolutePath());
    }
}
