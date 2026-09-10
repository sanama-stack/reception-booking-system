package dev.reception.ai.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Writes the two files {@code tools/receptionist-probe/probe.py} replays against: the system prompt
 * the loop actually builds, and the tool JSON it actually publishes.
 *
 * <p><strong>This exists so that nobody hand-writes either of them.</strong> A probe with a
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

    @Test
    void dumps_the_prompt_and_the_tools_the_loop_would_send() throws Exception {
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

        Files.createDirectories(OUTPUT);
        Files.writeString(
                OUTPUT.resolve("tools.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(tools));
        Files.writeString(OUTPUT.resolve("prompt.txt"), prompts.build());

        System.out.println("Probe fixtures written to " + OUTPUT.toAbsolutePath());
    }
}
