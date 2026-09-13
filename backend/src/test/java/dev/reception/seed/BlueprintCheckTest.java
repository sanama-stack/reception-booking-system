package dev.reception.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.reception.appointments.AppointmentSource;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The demo dataset is coherent, and the check that says so can fail.
 *
 * <p>The first test is the one that matters day to day: it runs in CI with no database and no
 * Spring context, so a placement mistake in {@link DemoTenants} — a Saturday booking for an
 * Employee who does not work Saturdays, an hour past closing time, two appointments in one diary
 * slot — is caught by the build rather than by somebody looking at a calendar during a demo.
 *
 * <p>The rest are its counterfactuals. A validator that has never been seen to fail is
 * indistinguishable from one that returns silently, and this project has shipped one of those
 * before. Each case below plants exactly one defect in the real Salon Aria blueprint and asserts
 * the check names it.
 */
class BlueprintCheckTest {

    @Test
    void the_demo_tenants_are_coherent() {
        assertThatCode(() -> BlueprintCheck.verify(DemoTenants.all())).doesNotThrowAnyException();
    }

    @Test
    void an_appointment_that_runs_past_closing_time_is_refused() {
        // Salon Aria closes at 17:00 on Saturday; a 150-minute balayage at 16:00 does not fit.
        assertThatThrownBy(() -> verifyWith(appointment(
                        0, DayOfWeek.SATURDAY, LocalTime.of(16, 0), "Mariam Beridze", "Balayage")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the business hours");
    }

    @Test
    void an_appointment_outside_the_employees_own_schedule_is_refused() {
        // The salon is open until 19:00 on Wednesday, but Giorgi's shift ends at 15:00.
        assertThatThrownBy(() -> verifyWith(appointment(
                        0, DayOfWeek.WEDNESDAY, LocalTime.of(17, 0), "Giorgi Tsiklauri", "Haircut")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside Giorgi Tsiklauri's working schedule");
    }

    @Test
    void a_service_the_employee_does_not_perform_is_refused() {
        assertThatThrownBy(() -> verifyWith(appointment(
                        0, DayOfWeek.WEDNESDAY, LocalTime.of(11, 0), "Giorgi Tsiklauri", "Colour")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not perform Colour");
    }

    @Test
    void a_second_appointment_for_one_employee_on_one_day_is_refused() {
        // Two in the same diary on the same day is what the one-a-day rule exists to forbid: it is
        // the rule that makes the whole set provably free of overlap without reasoning about Buffers.
        assertThatThrownBy(() -> verifyWith(
                        appointment(0, DayOfWeek.TUESDAY, LocalTime.of(11, 0), "Nino Kapanadze", "Haircut"),
                        appointment(0, DayOfWeek.TUESDAY, LocalTime.of(14, 0), "Nino Kapanadze", "Blow-dry")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a second appointment for this employee on this day");
    }

    @Test
    void an_appointment_during_the_employees_time_off_is_refused() {
        // Mariam is away Monday to Friday next week.
        assertThatThrownBy(() -> verifyWith(appointment(
                        1, DayOfWeek.WEDNESDAY, LocalTime.of(12, 0), "Mariam Beridze", "Colour")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is on time off that day");
    }

    @Test
    void a_customer_cancellation_the_window_would_refuse_is_refused_here_first() {
        Blueprint.Appointment lastWeek = new Blueprint.Appointment(
                new Blueprint.Placement(-1, DayOfWeek.TUESDAY, LocalTime.of(11, 0)),
                "Nino Kapanadze",
                "Haircut",
                "Ana Gelashvili",
                AppointmentSource.CLASSIC,
                Blueprint.Outcome.CANCELLED_BY_CUSTOMER,
                null);

        assertThatThrownBy(() -> verifyWith(lastWeek))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not far enough ahead");
    }

    @Test
    void a_customer_cancellation_next_monday_is_refused_because_a_sunday_seed_is_hours_from_it() {
        // The regression. Salon Aria shipped exactly this placement, and the rule it had to pass —
        // "week offset of at least 1" — waved it through, because a week number cannot answer a
        // question asked in hours. Seeded on a Sunday after 11:00 local, next Monday 11:00 is
        // inside the 24-hour window, CancellationService refused it, and all eight DemoSeedTest
        // cases failed at the one seedAll() line. The same commit passed that morning: CI on this
        // repository was green or red according to the hour it ran.
        //
        // Asserting the number, not just the phrase. The worst case here is 11 hours and the
        // message has to say so, because that figure is the whole of what the old rule could not
        // see — a rule that reported "week +1, fine" would satisfy a message-free assertion.
        Blueprint.Appointment nextMonday = new Blueprint.Appointment(
                new Blueprint.Placement(1, DayOfWeek.MONDAY, LocalTime.of(11, 0)),
                "Giorgi Tsiklauri",
                "Blow-dry",
                "Keti Lomidze",
                AppointmentSource.DASHBOARD,
                Blueprint.Outcome.CANCELLED_BY_CUSTOMER,
                null);

        assertThatThrownBy(() -> verifyWith(nextMonday))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not far enough ahead")
                .hasMessageContaining("11 hours in the worst case");
    }

    @Test
    void a_customer_cancellation_far_enough_ahead_of_the_window_is_accepted() {
        // The other side of the boundary, so the rule above is not simply "refuse every customer
        // cancellation" — which would pass the test above while making the check useless. Thursday
        // of week +1 guarantees 85 hours whenever the seed runs. Nino rather than Giorgi, and
        // Thursday rather than Wednesday, because both of those are already occupied in week +1 and
        // a clash would fail this test for a reason that has nothing to do with the window.
        Blueprint.Appointment nextThursday = new Blueprint.Appointment(
                new Blueprint.Placement(1, DayOfWeek.THURSDAY, LocalTime.of(13, 0)),
                "Nino Kapanadze",
                "Haircut",
                "Keti Lomidze",
                AppointmentSource.DASHBOARD,
                Blueprint.Outcome.CANCELLED_BY_CUSTOMER,
                null);

        assertThatCode(() -> verifyWith(nextThursday)).doesNotThrowAnyException();
    }

    @Test
    void every_problem_is_named_at_once() {
        // One message listing everything, rather than one run per defect. A fixture is usually
        // wrong in more than one place at a time, and finding that out one build at a time is how a
        // ten-minute correction becomes an afternoon.
        assertThatThrownBy(() -> verifyWith(
                        appointment(0, DayOfWeek.WEDNESDAY, LocalTime.of(11, 0), "Giorgi Tsiklauri", "Colour"),
                        appointment(0, DayOfWeek.SATURDAY, LocalTime.of(16, 0), "Mariam Beridze", "Balayage")))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage().lines().count()).isGreaterThan(2));
    }

    /** Salon Aria, exactly as it ships, with these appointments added to its own. */
    private static void verifyWith(Blueprint.Appointment... planted) {
        Blueprint.Tenant salon = DemoTenants.all().get(0);
        List<Blueprint.Appointment> appointments = new ArrayList<>(salon.appointments());
        appointments.addAll(List.of(planted));

        BlueprintCheck.verify(List.of(new Blueprint.Tenant(
                salon.owner(),
                salon.profile(),
                salon.hours(),
                salon.services(),
                salon.employees(),
                salon.faqs(),
                salon.closures(),
                salon.customers(),
                appointments)));
    }

    private static Blueprint.Appointment appointment(
            int weekOffset, DayOfWeek day, LocalTime at, String employee, String service) {
        return new Blueprint.Appointment(
                new Blueprint.Placement(weekOffset, day, at),
                employee,
                service,
                "Ana Gelashvili",
                AppointmentSource.CLASSIC,
                Blueprint.Outcome.BOOKED,
                null);
    }
}
