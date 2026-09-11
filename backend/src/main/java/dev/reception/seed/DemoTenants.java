package dev.reception.seed;

import static dev.reception.appointments.AppointmentSource.AI;
import static dev.reception.appointments.AppointmentSource.CLASSIC;
import static dev.reception.appointments.AppointmentSource.DASHBOARD;
import static dev.reception.seed.Blueprint.Outcome.BOOKED;
import static dev.reception.seed.Blueprint.Outcome.CANCELLED_BY_BUSINESS;
import static dev.reception.seed.Blueprint.Outcome.CANCELLED_BY_CUSTOMER;
import static dev.reception.seed.Blueprint.Outcome.COMPLETED;
import static dev.reception.seed.Blueprint.Outcome.NO_SHOW;
import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.List;

/**
 * The two demo tenants.
 *
 * <p><strong>Two verticals in two timezones and two currencies, deliberately</strong>
 * (docs/phases/phase-11-hardening-and-deployment.md). Tenant isolation and timezone correctness
 * are then <em>demonstrable</em> rather than claimed: a reviewer signs into one, watches the
 * other's data stay invisible, and sees each business's day drawn in its own wall clock. One
 * tenant would have proved neither, because with a single Business in the tables every row belongs
 * to it and every clock agrees.
 *
 * <p>The differences are the point, so they are listed rather than left to be noticed:
 *
 * <ul>
 *   <li>Salon Aria is {@code Asia/Tbilisi} and GEL; Dato's Auto is {@code Europe/Berlin} and EUR —
 *       and Berlin observes daylight saving while Tbilisi does not, so the offset between them is
 *       not even constant
 *   <li>Salon Aria's barber is not assigned to Colour or Balayage, so a Service with no available
 *       Employee is on screen from the first minute
 *   <li>Salon Aria's colourist is away all of next week; Dato's Auto closes for a public holiday.
 *       Time Off and a Business Closure are different mechanisms and the calendar draws them
 *       differently
 *   <li>Dato's Auto's full service is four hours with a thirty-minute Buffer — the case where the
 *       time an Appointment blocks is visibly not the time the Customer agreed to
 * </ul>
 *
 * <p>The password is the same for both accounts and is printed by the seed. It is a local-only
 * fixture in a {@code local}-guarded path; nothing here is a secret and nothing here is reused
 * anywhere that matters.
 */
final class DemoTenants {

    static final String PASSWORD = "reception-demo";

    private DemoTenants() {}

    static List<Blueprint.Tenant> all() {
        return List.of(salonAria(), datosAuto());
    }

    /** A hair salon in Tbilisi: four Services from thirty minutes to two and a half hours. */
    private static Blueprint.Tenant salonAria() {
        return new Blueprint.Tenant(
                new Blueprint.Owner("owner@salonaria.example", PASSWORD, "Nino Kapanadze"),
                new Blueprint.Profile(
                        "Salon Aria",
                        "salon-aria",
                        ZoneId.of("Asia/Tbilisi"),
                        "GEL",
                        "GE",
                        "A small hair studio on Chavchavadze Avenue. Cuts, colour and balayage, by appointment.",
                        "12 Chavchavadze Avenue",
                        "Tbilisi",
                        "+995 322 12 34 56",
                        "hello@salonaria.example",
                        "https://salonaria.example",
                        "Please give us 24 hours' notice if you need to cancel or move an appointment.",
                        "Parking is free in the courtyard behind the building. We take cash and card, "
                                + "and we do not take deposits.",
                        15,
                        60,
                        60,
                        24),
                List.of(
                        new Blueprint.Interval(MONDAY, at(10, 0), at(19, 0)),
                        new Blueprint.Interval(TUESDAY, at(10, 0), at(19, 0)),
                        new Blueprint.Interval(WEDNESDAY, at(10, 0), at(19, 0)),
                        new Blueprint.Interval(THURSDAY, at(10, 0), at(19, 0)),
                        new Blueprint.Interval(FRIDAY, at(10, 0), at(19, 0)),
                        new Blueprint.Interval(SATURDAY, at(10, 0), at(17, 0))),
                List.of(
                        new Blueprint.Service("Haircut", "Wash, cut and finish.", 30, 0, 10, price("40.00")),
                        new Blueprint.Service("Blow-dry", "Wash and blow-dry.", 45, 0, 0, price("35.00")),
                        new Blueprint.Service(
                                "Colour", "Single-process colour, roots or full head.", 120, 0, 15, price("150.00")),
                        new Blueprint.Service(
                                "Balayage", "Freehand lightening and toner.", 150, 0, 15, price("220.00"))),
                List.of(
                        new Blueprint.Employee(
                                "Nino Kapanadze",
                                "nino@salonaria.example",
                                "+995 555 11 22 33",
                                "Senior stylist",
                                weekdays(at(10, 0), at(18, 0)),
                                List.of("Haircut", "Blow-dry", "Colour", "Balayage"),
                                List.of()),
                        new Blueprint.Employee(
                                "Mariam Beridze",
                                "mariam@salonaria.example",
                                "+995 555 22 33 44",
                                "Colour specialist",
                                List.of(
                                        new Blueprint.Interval(TUESDAY, at(11, 0), at(19, 0)),
                                        new Blueprint.Interval(WEDNESDAY, at(11, 0), at(19, 0)),
                                        new Blueprint.Interval(THURSDAY, at(11, 0), at(19, 0)),
                                        new Blueprint.Interval(FRIDAY, at(11, 0), at(19, 0)),
                                        new Blueprint.Interval(SATURDAY, at(11, 0), at(17, 0))),
                                List.of("Haircut", "Blow-dry", "Colour", "Balayage"),
                                // Next week, Monday to Friday. Her Saturday is untouched, which is
                                // what makes Time Off visibly a range of days rather than a switch.
                                List.of(new Blueprint.TimeOff(1, MONDAY, FRIDAY, "Annual leave"))),
                        new Blueprint.Employee(
                                "Giorgi Tsiklauri",
                                "giorgi@salonaria.example",
                                "+995 555 33 44 55",
                                "Barber",
                                List.of(
                                        new Blueprint.Interval(MONDAY, at(9, 0), at(15, 0)),
                                        new Blueprint.Interval(WEDNESDAY, at(9, 0), at(15, 0)),
                                        new Blueprint.Interval(FRIDAY, at(9, 0), at(15, 0))),
                                // No Colour and no Balayage. His shift also opens an hour before the
                                // salon does, so the engine has something to intersect away.
                                List.of("Haircut", "Blow-dry"),
                                List.of())),
                List.of(
                        new Blueprint.Faq(
                                "Where can I park?",
                                "There is free parking in the courtyard behind the building — turn in from "
                                        + "the side street, not from the avenue."),
                        new Blueprint.Faq(
                                "How can I pay?",
                                "Cash or card in the salon. We do not take a deposit when you book."),
                        new Blueprint.Faq(
                                "Can I bring my child with me?",
                                "Of course. There is a sofa by the window and we are never in a hurry.")),
                List.of(),
                List.of(
                        new Blueprint.Customer("Ana Gelashvili", "+995 555 10 10 10", "ana.gelashvili@example.com"),
                        new Blueprint.Customer("Tamar Kvaratskhelia", "+995 555 20 20 20", "tamar.k@example.com"),
                        new Blueprint.Customer("Levan Chkheidze", "+995 555 30 30 30", "levan.ch@example.com"),
                        // No email on file, deliberately: ADR-0008's case, where the manage page has
                        // to say so rather than promise a message nothing will send.
                        new Blueprint.Customer("Salome Jorbenadze", "+995 555 40 40 40", null),
                        new Blueprint.Customer("Nika Abashidze", "+995 555 50 50 50", "nika.abashidze@example.com"),
                        new Blueprint.Customer("Keti Lomidze", "+995 555 60 60 60", "keti.lomidze@example.com")),
                salonAriaAppointments());
    }

    /**
     * Twenty-three Appointments over five weeks, at most one per Employee per day.
     *
     * <p>One per Employee per day is not an aesthetic choice: it is what makes the set provably
     * free of overlap without the fixture having to reason about Buffers, and the exclusion
     * constraint is left to prove it rather than to be trusted.
     */
    private static List<Blueprint.Appointment> salonAriaAppointments() {
        return List.of(
                // Two weeks ago — closed out.
                appointment(-2, MONDAY, at(10, 0), "Nino Kapanadze", "Haircut", "Ana Gelashvili", DASHBOARD, COMPLETED),
                appointment(
                        -2, WEDNESDAY, at(14, 0), "Nino Kapanadze", "Colour", "Tamar Kvaratskhelia", CLASSIC, COMPLETED),
                appointment(
                        -2, THURSDAY, at(11, 0), "Mariam Beridze", "Balayage", "Keti Lomidze", CLASSIC, COMPLETED),
                appointment(
                        -2, FRIDAY, at(10, 30), "Giorgi Tsiklauri", "Haircut", "Levan Chkheidze", DASHBOARD, NO_SHOW),
                // Last week.
                appointment(
                        -1, TUESDAY, at(11, 0), "Nino Kapanadze", "Blow-dry", "Salome Jorbenadze", AI, COMPLETED),
                appointment(-1, THURSDAY, at(10, 0), "Nino Kapanadze", "Balayage", "Ana Gelashvili", CLASSIC, COMPLETED),
                appointment(-1, WEDNESDAY, at(15, 0), "Mariam Beridze", "Colour", "Tamar Kvaratskhelia", AI, COMPLETED),
                appointment(
                        -1,
                        SATURDAY,
                        at(11, 30),
                        "Mariam Beridze",
                        "Haircut",
                        "Nika Abashidze",
                        CLASSIC,
                        CANCELLED_BY_BUSINESS),
                appointment(
                        -1, MONDAY, at(13, 0), "Giorgi Tsiklauri", "Haircut", "Nika Abashidze", DASHBOARD, COMPLETED),
                appointment(-1, WEDNESDAY, at(11, 0), "Giorgi Tsiklauri", "Blow-dry", "Keti Lomidze", CLASSIC, COMPLETED),
                // This week. The early ones are already behind us by Friday; the Monday cut is left
                // CONFIRMED on purpose, so the demo script has something real to mark completed.
                appointment(0, MONDAY, at(10, 30), "Nino Kapanadze", "Haircut", "Levan Chkheidze", CLASSIC, BOOKED),
                appointment(
                        0,
                        TUESDAY,
                        at(15, 0),
                        "Nino Kapanadze",
                        "Colour",
                        "Keti Lomidze",
                        AI,
                        BOOKED,
                        "Going a shade lighter than last time if there is room in the appointment."),
                appointment(0, THURSDAY, at(11, 0), "Nino Kapanadze", "Blow-dry", "Ana Gelashvili", CLASSIC, BOOKED),
                appointment(
                        0, WEDNESDAY, at(12, 0), "Mariam Beridze", "Balayage", "Tamar Kvaratskhelia", CLASSIC, BOOKED),
                appointment(0, FRIDAY, at(16, 0), "Mariam Beridze", "Haircut", "Salome Jorbenadze", AI, BOOKED),
                appointment(0, SATURDAY, at(12, 0), "Mariam Beridze", "Colour", "Ana Gelashvili", CLASSIC, BOOKED),
                appointment(0, FRIDAY, at(10, 0), "Giorgi Tsiklauri", "Haircut", "Nika Abashidze", DASHBOARD, BOOKED),
                // Next week — Mariam is on leave Monday to Friday, so her only booking is Saturday.
                appointment(1, TUESDAY, at(10, 0), "Nino Kapanadze", "Haircut", "Ana Gelashvili", AI, BOOKED),
                appointment(1, WEDNESDAY, at(13, 0), "Nino Kapanadze", "Colour", "Levan Chkheidze", CLASSIC, BOOKED),
                appointment(
                        1,
                        MONDAY,
                        at(11, 0),
                        "Giorgi Tsiklauri",
                        "Blow-dry",
                        "Keti Lomidze",
                        DASHBOARD,
                        CANCELLED_BY_CUSTOMER),
                appointment(
                        1, SATURDAY, at(11, 0), "Mariam Beridze", "Balayage", "Tamar Kvaratskhelia", CLASSIC, BOOKED),
                // The week after.
                appointment(
                        2, THURSDAY, at(14, 0), "Nino Kapanadze", "Balayage", "Salome Jorbenadze", CLASSIC, BOOKED),
                appointment(2, WEDNESDAY, at(12, 0), "Giorgi Tsiklauri", "Haircut", "Nika Abashidze", AI, BOOKED));
    }

    /** A garage in Berlin: three Services, the longest of them four hours with a half-hour Buffer. */
    private static Blueprint.Tenant datosAuto() {
        return new Blueprint.Tenant(
                new Blueprint.Owner("owner@datosauto.example", PASSWORD, "Dato Kiknadze"),
                new Blueprint.Profile(
                        "Dato's Auto",
                        "datos-auto",
                        ZoneId.of("Europe/Berlin"),
                        "EUR",
                        "DE",
                        "Independent workshop in Neukölln. Servicing, diagnostics and repairs for every make.",
                        "44 Karl-Marx-Straße",
                        "Berlin",
                        "+49 30 1234 5678",
                        "werkstatt@datosauto.example",
                        "https://datosauto.example",
                        "Cancel or move up to 24 hours before your slot and there is nothing to pay.",
                        "We are a five-minute walk from Rathaus Neukölln. A courtesy car can be arranged for "
                                + "a full service if you ask when you book.",
                        30,
                        120,
                        45,
                        24),
                List.of(
                        new Blueprint.Interval(MONDAY, at(8, 0), at(18, 0)),
                        new Blueprint.Interval(TUESDAY, at(8, 0), at(18, 0)),
                        new Blueprint.Interval(WEDNESDAY, at(8, 0), at(18, 0)),
                        new Blueprint.Interval(THURSDAY, at(8, 0), at(18, 0)),
                        new Blueprint.Interval(FRIDAY, at(8, 0), at(18, 0)),
                        new Blueprint.Interval(SATURDAY, at(9, 0), at(13, 0))),
                List.of(
                        new Blueprint.Service(
                                "Oil change", "Oil and filter, all makes.", 30, 0, 15, price("79.00")),
                        new Blueprint.Service(
                                "Diagnostics", "Fault-code read and road test.", 60, 0, 15, price("120.00")),
                        new Blueprint.Service(
                                "Full service",
                                "Inspection, fluids, filters and brakes. The car is with us for the day.",
                                240,
                                0,
                                30,
                                price("450.00"))),
                List.of(
                        new Blueprint.Employee(
                                "Dato Kiknadze",
                                "dato@datosauto.example",
                                "+49 151 2345 6789",
                                "Master mechanic",
                                weekdays(at(8, 0), at(16, 0)),
                                List.of("Oil change", "Diagnostics", "Full service"),
                                List.of()),
                        new Blueprint.Employee(
                                "Lukas Brandt",
                                "lukas@datosauto.example",
                                "+49 172 3456 789",
                                "Mechanic",
                                List.of(
                                        new Blueprint.Interval(TUESDAY, at(10, 0), at(18, 0)),
                                        new Blueprint.Interval(WEDNESDAY, at(10, 0), at(18, 0)),
                                        new Blueprint.Interval(THURSDAY, at(10, 0), at(18, 0)),
                                        new Blueprint.Interval(FRIDAY, at(10, 0), at(18, 0)),
                                        new Blueprint.Interval(SATURDAY, at(9, 0), at(13, 0))),
                                // No full service: a four-hour job does not fit an eight-hour shift
                                // that starts at ten, and assigning it would advertise a Slot the
                                // engine would never offer.
                                List.of("Oil change", "Diagnostics"),
                                List.of())),
                List.of(
                        new Blueprint.Faq(
                                "Do you take cars still under warranty?",
                                "Yes. Servicing here does not affect a manufacturer's warranty — we stamp the "
                                        + "book and use approved parts."),
                        new Blueprint.Faq(
                                "Can I wait while you work?",
                                "For an oil change or diagnostics, yes. A full service takes the day, so most "
                                        + "people leave the car with us.")),
                // Day of German Unity, resolved to its next occurrence. A public holiday closes the
                // whole Business regardless of its hours, which is a different thing from a mechanic
                // being away — and the calendar draws them differently.
                List.of(new Blueprint.Closure(MonthDay.of(Month.OCTOBER, 3), "Tag der Deutschen Einheit")),
                List.of(
                        new Blueprint.Customer("Jonas Weber", "+49 171 1234567", "jonas.weber@example.com"),
                        new Blueprint.Customer("Lena Schulz", "+49 172 2345678", "lena.schulz@example.com"),
                        new Blueprint.Customer("Mehmet Yilmaz", "+49 176 34567890", "mehmet.yilmaz@example.com"),
                        new Blueprint.Customer("Sofia Richter", "+49 151 23456789", "sofia.richter@example.com"),
                        new Blueprint.Customer("Paul Neumann", "+49 160 4567890", null)),
                datosAutoAppointments());
    }

    /** Sixteen Appointments over five weeks, at most one per Employee per day. */
    private static List<Blueprint.Appointment> datosAutoAppointments() {
        return List.of(
                appointment(-2, MONDAY, at(8, 0), "Dato Kiknadze", "Full service", "Jonas Weber", DASHBOARD, COMPLETED),
                appointment(-2, WEDNESDAY, at(13, 0), "Dato Kiknadze", "Diagnostics", "Lena Schulz", CLASSIC, COMPLETED),
                appointment(-2, THURSDAY, at(10, 0), "Lukas Brandt", "Oil change", "Mehmet Yilmaz", CLASSIC, COMPLETED),
                appointment(-1, TUESDAY, at(9, 0), "Dato Kiknadze", "Oil change", "Sofia Richter", AI, COMPLETED),
                appointment(-1, THURSDAY, at(8, 30), "Dato Kiknadze", "Full service", "Paul Neumann", CLASSIC, COMPLETED),
                appointment(-1, WEDNESDAY, at(11, 0), "Lukas Brandt", "Diagnostics", "Jonas Weber", CLASSIC, NO_SHOW),
                appointment(-1, SATURDAY, at(9, 30), "Lukas Brandt", "Oil change", "Lena Schulz", DASHBOARD, COMPLETED),
                appointment(0, MONDAY, at(10, 0), "Dato Kiknadze", "Diagnostics", "Mehmet Yilmaz", CLASSIC, COMPLETED),
                appointment(
                        0,
                        WEDNESDAY,
                        at(8, 0),
                        "Dato Kiknadze",
                        "Full service",
                        "Sofia Richter",
                        CLASSIC,
                        BOOKED,
                        "There is a rattle from the front left over bumps — please look at it while it is in."),
                appointment(0, THURSDAY, at(14, 0), "Lukas Brandt", "Oil change", "Paul Neumann", AI, BOOKED),
                appointment(0, FRIDAY, at(11, 0), "Lukas Brandt", "Diagnostics", "Jonas Weber", CLASSIC, BOOKED),
                appointment(1, TUESDAY, at(9, 0), "Dato Kiknadze", "Full service", "Lena Schulz", CLASSIC, BOOKED),
                appointment(
                        1,
                        WEDNESDAY,
                        at(10, 30),
                        "Lukas Brandt",
                        "Oil change",
                        "Mehmet Yilmaz",
                        CLASSIC,
                        CANCELLED_BY_CUSTOMER),
                appointment(1, FRIDAY, at(14, 0), "Dato Kiknadze", "Diagnostics", "Sofia Richter", AI, BOOKED),
                appointment(2, TUESDAY, at(15, 0), "Lukas Brandt", "Oil change", "Paul Neumann", CLASSIC, BOOKED),
                appointment(2, THURSDAY, at(8, 0), "Dato Kiknadze", "Full service", "Jonas Weber", DASHBOARD, BOOKED));
    }

    private static Blueprint.Appointment appointment(
            int weekOffset,
            DayOfWeek day,
            LocalTime at,
            String employee,
            String service,
            String customer,
            dev.reception.appointments.AppointmentSource source,
            Blueprint.Outcome outcome) {
        return appointment(weekOffset, day, at, employee, service, customer, source, outcome, null);
    }

    /** The same, with the note a Customer left when they booked. */
    private static Blueprint.Appointment appointment(
            int weekOffset,
            DayOfWeek day,
            LocalTime at,
            String employee,
            String service,
            String customer,
            dev.reception.appointments.AppointmentSource source,
            Blueprint.Outcome outcome,
            String note) {
        return new Blueprint.Appointment(
                new Blueprint.Placement(weekOffset, day, at), employee, service, customer, source, outcome, note);
    }

    private static List<Blueprint.Interval> weekdays(LocalTime from, LocalTime to) {
        return List.of(
                new Blueprint.Interval(MONDAY, from, to),
                new Blueprint.Interval(TUESDAY, from, to),
                new Blueprint.Interval(WEDNESDAY, from, to),
                new Blueprint.Interval(THURSDAY, from, to),
                new Blueprint.Interval(FRIDAY, from, to));
    }

    private static LocalTime at(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }

    private static BigDecimal price(String amount) {
        return new BigDecimal(amount);
    }
}
