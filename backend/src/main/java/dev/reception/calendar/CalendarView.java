package dev.reception.calendar;

import dev.reception.appointments.AppointmentQueryService;
import dev.reception.business.BusinessClosure;
import dev.reception.staff.EmployeeTimeOff;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a day or week view draws, as one value.
 *
 * @param employeeNames every Employee mentioned by the time off, resolved once — a calendar column
 *     is headed by a person's name, and looking each one up per absence is the N+1 the query below
 *     exists to avoid
 */
public record CalendarView(
        LocalDate from,
        LocalDate to,
        ZoneId timezone,
        List<AppointmentQueryService.AppointmentView> appointments,
        List<BusinessClosure> closures,
        List<EmployeeTimeOff> timeOff,
        Map<UUID, String> employeeNames) {}
