package dev.reception.seed;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Proves a demo tenant is coherent before a single row is written.
 *
 * <p>This exists because the failure it catches is invisible. Every rule below describes a state the
 * application will happily store — an Appointment outside the Business's opening hours is legal, and
 * has to be, because hours can change after a booking — so nothing downstream would object. The
 * seed would succeed, and the demo would show a salon with a Saturday booking on a day it is shut.
 *
 * <p>It is a pure function of the blueprint, which is what lets {@code DemoTenantsSpecTest} run it
 * in CI with no database, no Spring context and no clock. The alternative was finding a placement
 * mistake by reading a calendar, which is how the same class of mistake has been found in this
 * project before — after it shipped.
 *
 * <p>What it deliberately does <em>not</em> check is the Business Closure, whose date is a real
 * calendar day rather than a week offset. An Appointment that falls on a public holiday is a state
 * the application produces on purpose — {@code ClosureService.create} returns the number of
 * Appointments a new closure affects — so it is left alone rather than rejected here.
 */
final class BlueprintCheck {

    private BlueprintCheck() {}

    /** @throws IllegalStateException naming every problem at once, rather than the first */
    static void verify(List<Blueprint.Tenant> tenants) {
        List<String> problems = new ArrayList<>();

        Set<String> slugs = new HashSet<>();
        Set<String> owners = new HashSet<>();
        for (Blueprint.Tenant tenant : tenants) {
            String slug = tenant.profile().slug();
            if (!slugs.add(slug)) {
                problems.add("two tenants claim the slug " + slug);
            }
            if (!owners.add(tenant.owner().email())) {
                problems.add("two tenants claim the owner " + tenant.owner().email());
            }
            verify(tenant, problems);
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "The demo blueprint is not coherent:\n  - " + String.join("\n  - ", problems));
        }
    }

    private static void verify(Blueprint.Tenant tenant, List<String> problems) {
        String where = tenant.profile().name();
        Set<String> serviceNames = new HashSet<>();
        for (Blueprint.Service service : tenant.services()) {
            serviceNames.add(service.name());
        }

        for (Blueprint.Employee employee : tenant.employees()) {
            for (String service : employee.services()) {
                if (!serviceNames.contains(service)) {
                    problems.add("%s: %s is assigned to a service that does not exist: %s"
                            .formatted(where, employee.fullName(), service));
                }
            }
        }

        Set<String> occupied = new HashSet<>();
        for (Blueprint.Appointment appointment : tenant.appointments()) {
            verify(tenant, appointment, occupied, problems);
        }
    }

    private static void verify(
            Blueprint.Tenant tenant, Blueprint.Appointment appointment, Set<String> occupied, List<String> problems) {
        String where = tenant.profile().name();
        String label = "%s: %s for %s on %s of week %+d at %s"
                .formatted(
                        where,
                        appointment.service(),
                        appointment.employee(),
                        appointment.when().day(),
                        appointment.when().weekOffset(),
                        appointment.when().at());

        Blueprint.Employee employee = find(tenant.employees(), Blueprint.Employee::fullName, appointment.employee());
        Blueprint.Service service = find(tenant.services(), Blueprint.Service::name, appointment.service());
        boolean known = employee != null && service != null;

        if (employee == null) {
            problems.add(label + " — no such employee");
        }
        if (service == null) {
            problems.add(label + " — no such service");
        }
        if (find(tenant.customers(), Blueprint.Customer::fullName, appointment.customer()) == null) {
            problems.add(label + " — no such customer: " + appointment.customer());
        }
        if (!known) {
            return;
        }

        if (!employee.services().contains(service.name())) {
            problems.add(label + " — %s does not perform %s".formatted(employee.fullName(), service.name()));
        }

        // One Appointment per Employee per day is what makes the whole set provably free of
        // overlap without this check having to reason about Buffers. The exclusion constraint is
        // then left to prove it at insert time rather than asked to be trusted.
        String day = "%s/%d/%s".formatted(appointment.employee(), appointment.when().weekOffset(), appointment.when().day());
        if (!occupied.add(day)) {
            problems.add(label + " — a second appointment for this employee on this day");
        }

        LocalTime from = appointment.when().at();
        LocalTime to = from.plusMinutes(service.durationMinutes());
        if (to.isBefore(from) || to.equals(LocalTime.MIDNIGHT)) {
            problems.add(label + " — runs past midnight");
            return;
        }
        if (!within(tenant.hours(), appointment.when().day(), from, to)) {
            problems.add(label + " — outside the business hours, which end before it does");
        }
        if (!within(employee.schedule(), appointment.when().day(), from, to)) {
            problems.add(label + " — outside %s's working schedule".formatted(employee.fullName()));
        }

        for (Blueprint.TimeOff away : employee.timeOff()) {
            boolean sameWeek = away.weekOffset() == appointment.when().weekOffset();
            boolean withinRange = appointment.when().day().getValue() >= away.from().getValue()
                    && appointment.when().day().getValue() <= away.to().getValue();
            if (sameWeek && withinRange) {
                problems.add(label + " — %s is on time off that day".formatted(employee.fullName()));
            }
        }

        // A Customer may only cancel while the Cancellation Window is open, so a fixture that asks
        // for one in the past describes a cancellation the application would refuse to perform.
        // Next week or later is the only placement that is certainly outside a window measured in
        // hours, whatever day the seed runs.
        if (appointment.outcome() == Blueprint.Outcome.CANCELLED_BY_CUSTOMER && appointment.when().weekOffset() < 1) {
            problems.add(label + " — cancelled by the customer, but not far enough ahead to be allowed to");
        }
    }

    private static boolean within(
            List<Blueprint.Interval> intervals, java.time.DayOfWeek day, LocalTime from, LocalTime to) {
        for (Blueprint.Interval interval : intervals) {
            if (interval.day() == day && !from.isBefore(interval.from()) && !to.isAfter(interval.to())) {
                return true;
            }
        }
        return false;
    }

    private static <T> T find(List<T> candidates, java.util.function.Function<T, String> name, String wanted) {
        for (T candidate : candidates) {
            if (name.apply(candidate).equals(wanted)) {
                return candidate;
            }
        }
        return null;
    }
}
