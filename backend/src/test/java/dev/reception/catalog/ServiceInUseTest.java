package dev.reception.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import dev.reception.business.AppointmentImpact;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The delete-guard and the deactivation impact, against a world where Appointments exist.
 *
 * <p>They do not exist yet — phase 06 builds them — so the seam is what gets stood in for.
 * {@link AppointmentImpact} is the port phase 04 asks its questions through, and replacing it with a
 * stub that answers "yes, this is booked" exercises the whole path from the controller down: the
 * {@code 409}, its code, and the count on a deactivation response.
 *
 * <p>Written now rather than deferred to phase 06 because the behaviour is phase 04's. What phase 06
 * owes is the real implementation of the port; if it changes any of these answers, this test fails
 * and says so — which is the point of testing against the seam rather than around it.
 */
class ServiceInUseTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    /** Stands in for phase 06. The real bean is {@code EmptyAppointmentImpact}, which answers zero. */
    @MockitoBean
    private AppointmentImpact appointments;

    private AuthTestClient owner;
    private String service;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        owner = new AuthTestClient(rest, port);
        owner.register("nino@aria.test", PASSWORD, "Salon Aria");

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Haircut");
        body.put("durationMinutes", 45);
        body.put("price", "60.00");
        service = JsonPath.read(owner.post("/services", body).getBody(), "$.id");
    }

    @Test
    @DisplayName("deleting a booked service is refused with 409 SERVICE_IN_USE")
    void refuses_to_delete_a_booked_service() {
        when(appointments.everBooked(any(), any())).thenReturn(true);

        ResponseEntity<String> response = owner.delete("/services/" + service);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) JsonPath.read(response.getBody(), "$.code")).isEqualTo("SERVICE_IN_USE");
        // A 409 whose text does not name the alternative leaves the owner stuck with a service they
        // can neither remove nor stop offering.
        assertThat((String) JsonPath.read(response.getBody(), "$.detail")).containsIgnoringCase("deactivate");

        assertThat(owner.get("/services/" + service).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a service that has never been booked is still deletable")
    void deletes_an_unbooked_service() {
        when(appointments.everBooked(any(), any())).thenReturn(false);

        assertThat(owner.delete("/services/" + service).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("another tenant's booked service is a 404, not a 409")
    void another_tenants_service_is_not_found_rather_than_in_use() {
        when(appointments.everBooked(any(), any())).thenReturn(true);

        AuthTestClient other = new AuthTestClient(rest, port);
        other.register("dato@auto.test", PASSWORD, "Datos Auto");

        // A 409 here would confirm that the row exists and is booked, which is exactly what the
        // 404-for-everything rule exists to prevent (docs/06-security.md §3).
        assertThat(other.delete("/services/" + service).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a deactivation reports the number of upcoming appointments it affects")
    void reports_affected_future_appointments_on_a_service() {
        when(appointments.countFutureForService(any(), any())).thenReturn(3L);

        String body = owner.post("/services/" + service + "/deactivate", Map.of()).getBody();

        assertThat(((Number) JsonPath.read(body, "$.affectedFutureAppointments")).longValue())
                .isEqualTo(3L);
        // Reported, not acted on. The service is deactivated; nothing is cancelled.
        assertThat((boolean) JsonPath.read(body, "$.service.active")).isFalse();
    }

    @Test
    @DisplayName("re-activating reports nothing, because it costs nothing")
    void reports_zero_on_an_activation() {
        when(appointments.countFutureForService(any(), any())).thenReturn(3L);
        owner.post("/services/" + service + "/deactivate", Map.of());

        String body = owner.post("/services/" + service + "/activate", Map.of()).getBody();

        assertThat(((Number) JsonPath.read(body, "$.affectedFutureAppointments")).longValue())
                .isZero();
    }

    @Test
    @DisplayName("deactivating an employee reports the number of upcoming appointments it affects")
    void reports_affected_future_appointments_on_an_employee() {
        when(appointments.countFutureForEmployee(any(), any())).thenReturn(2L);
        String employee = JsonPath.read(
                owner.post("/employees", Map.of("fullName", "Nino Beridze")).getBody(), "$.id");

        String body = owner.post("/employees/" + employee + "/deactivate", Map.of())
                .getBody();

        assertThat(((Number) JsonPath.read(body, "$.affectedFutureAppointments")).longValue())
                .isEqualTo(2L);
        assertThat((boolean) JsonPath.read(body, "$.employee.active")).isFalse();
    }

    @Test
    @DisplayName("a closure still reports its own impact through the same port")
    void reports_affected_appointments_on_a_closure() {
        // The phase-03 question, asked through the port phase 04 extended. Extending an interface is
        // where an existing caller quietly starts getting a different answer, so it is worth one
        // assertion.
        when(appointments.countWithin(any(), any(), any())).thenReturn(5L);

        String body = owner.post("/business/closures", Map.of("startDate", "2026-12-24", "endDate", "2026-12-26"))
                .getBody();

        assertThat(((Number) JsonPath.read(body, "$.affectedAppointments")).longValue())
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("an unknown service is a 404 before the guard is ever consulted")
    void unknown_services_are_not_found() {
        when(appointments.everBooked(any(), any())).thenReturn(true);

        assertThat(owner.delete("/services/" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
