package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.catalog.AssignmentService;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.staff.Employee;
import dev.reception.staff.EmployeeService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * One Service, plus who can perform it.
 *
 * <p>The second half is why this exists separately from {@code get_services}: "can I have Anna for
 * a colour?" is a question about the assignment table, and answering it wrongly sends the customer
 * to a time that will be refused with {@code EMPLOYEE_CANNOT_PERFORM_SERVICE} — which is a refusal
 * the availability engine makes deliberately rather than quietly returning nothing.
 *
 * <p>{@code catalog.read} is what turns another tenant's service id into a {@code 404}, so a model
 * that was somehow handed an id from elsewhere gets "not found" and no confirmation that the row
 * exists (docs/06-security.md §3).
 */
@Component
public class GetServiceDetailsTool implements Tool {

    private final ServiceCatalogService catalog;
    private final AssignmentService assignments;
    private final EmployeeService employees;

    public GetServiceDetailsTool(
            ServiceCatalogService catalog, AssignmentService assignments, EmployeeService employees) {
        this.catalog = catalog;
        this.assignments = assignments;
        this.employees = employees;
    }

    @Override
    public String name() {
        return "get_service_details";
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                name(),
                "One service in full, including which staff members can perform it. Call it when the "
                        + "customer asks for a particular person, or wants to know more about a service "
                        + "than the list gave you.",
                ToolSchemas.object()
                        .required("service_id", "string", "The service's id, from get_services.")
                        .build());
    }

    @Override
    public ObjectNode execute(JsonNode arguments, ToolContext context) {
        UUID serviceId = ToolArguments.uuid(arguments, "service_id");
        dev.reception.catalog.Service service = catalog.read(serviceId);

        ObjectNode result = ToolResults.object();
        result.put("service_id", service.getId().toString());
        result.put("name", service.name());
        result.put("description", service.description());
        result.put("duration_minutes", service.durationMinutes());
        result.put("price", service.priceAmount().toPlainString());
        result.put("currency", service.currency());
        result.put("bookable", service.active());

        // Only the ones who are still bookable. An assignment to a deactivated Employee is history
        // the owner kept, not an offer the customer can take.
        ArrayNode performers = result.putArray("performed_by");
        for (UUID employeeId : assignments.employeesFor(service.getId())) {
            Employee employee = employees.read(employeeId);
            if (!employee.active()) {
                continue;
            }
            ObjectNode entry = performers.addObject();
            entry.put("employee_id", employee.getId().toString());
            entry.put("name", employee.fullName());
            // Job title and nothing else. An Employee's email and phone are for the owner, and this
            // is a tool whose output a stranger will hear read aloud (staff/package-info.java).
            entry.put("job_title", employee.jobTitle());
        }

        return result;
    }
}
