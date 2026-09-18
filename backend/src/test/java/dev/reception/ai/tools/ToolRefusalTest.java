package dev.reception.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * What the model is told when a tool throws — the one thing {@link ToolRegistry} decides.
 *
 * <p>The tool is a stub that throws on demand, because the failures under test are the ones a real
 * tool produces only under genuine concurrency. {@code ToolExecutionTest} books a taken slot
 * sequentially and gets {@code SLOT_UNAVAILABLE} from the <em>availability re-check</em> — a
 * different branch entirely, and the reason this gap survived eleven phases: the test that looked
 * like its coverage could not reach it.
 *
 * <p><strong>The defect this pins.</strong> A {@code DataIntegrityViolationException} from
 * {@code appointments_no_overlap} was translated at the HTTP edge and nowhere else, so a Customer
 * who lost a race to the dashboard heard "Something went wrong on my end" — the one account of the
 * event that is false. The time went; nothing went wrong.
 */
class ToolRefusalTest {

    private static final UUID BUSINESS = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();

    @Test
    @DisplayName("a lost exclusion-constraint race is SLOT_UNAVAILABLE, not TOOL_ERROR")
    void the_constraint_reaches_the_model_as_the_time_being_gone() {
        ObjectNode result = executeThrowing(() -> new DataIntegrityViolationException(
                "could not execute statement [insert into appointments ...]",
                new SQLException("ERROR: conflicting key value violates exclusion constraint"
                        + " \"appointments_no_overlap\"")));

        assertThat(result.path("error").asText()).isEqualTo(ErrorCode.SLOT_UNAVAILABLE.name());
        assertThat(result.path("message").asText()).isEqualTo("That time was booked while you were deciding. Choose another.");
        // The same vocabulary every other surface uses, which is what lets a transcript be read
        // against the error codes rather than against prose.
        assertThat(result.path("error").asText()).isNotEqualTo(ToolRegistry.TOOL_ERROR);
        // Nothing was wrong with any argument, so there is no fields key at all.
        assertThat(result.has("fields")).isFalse();
        // And the schema is not a fact the model gets to paraphrase to a customer.
        assertThat(result.toString()).doesNotContain("appointments_no_overlap");
    }

    @Test
    @DisplayName("a lost @Version check is VERSION_CONFLICT, not TOOL_ERROR")
    void the_version_check_reaches_the_model_as_a_concurrent_edit() {
        ObjectNode result =
                executeThrowing(() -> new OptimisticLockingFailureException("Row was updated by another transaction"));

        assertThat(result.path("error").asText()).isEqualTo(ErrorCode.VERSION_CONFLICT.name());
        assertThat(result.path("message").asText()).isNotBlank();
    }

    /**
     * The behaviour that must not change. An integrity violation nobody mapped is a defect, and the
     * model is told only that the tool failed — the detail belongs in the log, tied to the
     * transcript by the conversation id.
     */
    @Test
    @DisplayName("an unmapped integrity violation is still TOOL_ERROR, and leaks no SQL")
    void an_unmapped_violation_is_still_a_defect() {
        ObjectNode result = executeThrowing(() -> new DataIntegrityViolationException(
                "could not execute statement [ERROR: null value in column \"name\"]"
                        + " [insert into customers (id,name,phone) values (?,?,?)]",
                new SQLException("ERROR: null value in column \"name\" of relation customers")));

        assertThat(result.path("error").asText()).isEqualTo(ToolRegistry.TOOL_ERROR);
        assertThat(result.toString()).doesNotContain("insert into", "null value", "customers");
    }

    @Test
    @DisplayName("a domain refusal still passes its own code, message and fields through")
    void an_api_exception_is_unchanged() {
        ObjectNode result = executeThrowing(() -> new ApiException(
                ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid.",
                List.of(new dev.reception.common.error.FieldError(
                        "customer_phone", "Use international form, like +995 555 123456."))));

        assertThat(result.path("error").asText()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        assertThat(result.path("fields").get(0).path("field").asText()).isEqualTo("customer_phone");
    }

    private ObjectNode executeThrowing(Supplier<RuntimeException> failure) {
        ToolRegistry registry = new ToolRegistry(List.of(new ThrowingTool(failure)));
        ToolContext context = new ToolContext(
                BUSINESS,
                CONVERSATION,
                AuthorizedAppointments.none(),
                Clock.fixed(Instant.parse("2026-09-14T09:00:00Z"), ZoneOffset.UTC));
        return registry.execute("throwing_tool", JsonNodeFactory.instance.objectNode(), context);
    }

    /** A tool whose only behaviour is the failure under test. */
    private record ThrowingTool(Supplier<RuntimeException> failure) implements Tool {

        @Override
        public String name() {
            return "throwing_tool";
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name(), "Throws.", JsonNodeFactory.instance.objectNode());
        }

        @Override
        public ObjectNode execute(JsonNode arguments, ToolContext context) {
            throw failure.get();
        }
    }
}
