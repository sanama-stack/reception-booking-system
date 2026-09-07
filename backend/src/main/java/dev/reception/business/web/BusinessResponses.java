package dev.reception.business.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.reception.business.Business;
import dev.reception.business.BusinessClosure;
import dev.reception.business.BusinessFaq;
import dev.reception.business.BusinessHours;
import dev.reception.business.ClosureService;
import dev.reception.business.OnboardingService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies for {@code /business/*}, written by hand rather than mapped from entities.
 *
 * <p>No internal column can appear in one of these by accident, because there is no path from an
 * entity into them that does not pass through a named field.
 */
public final class BusinessResponses {

    private BusinessResponses() {}

    /** The full profile and settings. */
    public record BusinessProfile(
            UUID id,
            String name,
            String slug,
            String timezone,
            String currency,
            String description,
            String addressLine,
            String city,
            String country,
            String phone,
            String email,
            String website,
            int slotIntervalMinutes,
            int minLeadTimeMinutes,
            int maxAdvanceDays,
            int cancellationWindowHours,
            String cancellationPolicy,
            boolean aiEnabled,
            String aiAdditionalInfo,
            int aiDailyCostCapCents,
            String bookingUrl,
            Instant updatedAt) {

        public static BusinessProfile of(Business business) {
            return new BusinessProfile(
                    business.getId(),
                    business.name(),
                    business.slug(),
                    business.timezone().getId(),
                    business.currency(),
                    business.description(),
                    business.addressLine(),
                    business.city(),
                    business.country(),
                    business.phone(),
                    business.email(),
                    business.website(),
                    business.slotIntervalMinutes(),
                    business.minLeadTimeMinutes(),
                    business.maxAdvanceDays(),
                    business.cancellationWindowHours(),
                    business.cancellationPolicy(),
                    business.aiEnabled(),
                    business.aiAdditionalInfo(),
                    business.aiDailyCostCapCents(),
                    "/book/" + business.slug(),
                    business.updatedAt());
        }
    }

    /**
     * The week, plus the timezone it is to be read in.
     *
     * <p>The zone travels with the times because {@code 09:00} is meaningless without it, and a
     * client that has to remember to look it up somewhere else is a client that will eventually
     * forget (docs/04-api-overview.md §2).
     */
    public record WeekHours(String timezone, List<DayHours> hours) {

        public static WeekHours of(String timezone, List<BusinessHours> week) {
            return new WeekHours(timezone, week.stream().map(DayHours::of).toList());
        }
    }

    /**
     * Times are rendered {@code HH:mm}, which is the shape docs/04-api-overview.md §5 publishes and
     * the shape an {@code <input type="time">} both produces and expects. Jackson's ISO default
     * would add a seconds field that opening hours never carry and no client wants to strip.
     *
     * <p>The <em>request</em> side is deliberately left on the ISO default, which accepts both: strict
     * in what we send, liberal in what we accept.
     */
    public record DayHours(
            UUID id,
            int dayOfWeek,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime opensAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime closesAt) {

        public static DayHours of(BusinessHours hours) {
            return new DayHours(
                    hours.getId(), hours.dayOfWeek().getValue(), hours.opensAt(), hours.closesAt());
        }
    }

    /**
     * A closure, given back as both the instants that were stored and the local dates the owner
     * entered.
     *
     * <p>Sending only the instants would make the list render as the day before for a business west
     * of UTC, unless every client repeated the conversion; sending only the dates would lose the
     * representation the engine actually uses. Both, plus the zone, leaves nothing to infer.
     */
    public record Closure(
            UUID id,
            Instant startsAt,
            Instant endsAt,
            LocalDate startDate,
            LocalDate endDate,
            String reason) {

        public static Closure of(BusinessClosure closure, ZoneId zone) {
            return new Closure(
                    closure.getId(),
                    closure.startsAt(),
                    closure.endsAt(),
                    LocalDate.ofInstant(closure.startsAt(), zone),
                    // ends_at is the start of the following day; the owner entered the day before it.
                    LocalDate.ofInstant(closure.endsAt(), zone).minusDays(1),
                    closure.reason());
        }
    }

    public record ClosureList(String timezone, List<Closure> closures) {}

    /** A created closure and the appointments it covers, which the owner must now deal with. */
    public record CreatedClosure(Closure closure, long affectedAppointments) {

        public static CreatedClosure of(ClosureService.CreatedClosure created, ZoneId zone) {
            return new CreatedClosure(Closure.of(created.closure(), zone), created.affectedAppointments());
        }
    }

    public record Faq(UUID id, String question, String answer, int sortOrder) {

        public static Faq of(BusinessFaq faq) {
            return new Faq(faq.getId(), faq.question(), faq.answer(), faq.sortOrder());
        }
    }

    public record FaqList(List<Faq> faqs) {

        public static FaqList of(List<BusinessFaq> faqs) {
            return new FaqList(faqs.stream().map(Faq::of).toList());
        }
    }

    public record Onboarding(
            boolean hoursConfigured,
            boolean hasActiveService,
            boolean hasActiveEmployee,
            boolean hasEmployeeSchedule,
            boolean hasBookableService,
            boolean publicPageReady,
            String bookingUrl) {

        public static Onboarding of(OnboardingService.Checklist checklist) {
            return new Onboarding(
                    checklist.hoursConfigured(),
                    checklist.hasActiveService(),
                    checklist.hasActiveEmployee(),
                    checklist.hasEmployeeSchedule(),
                    checklist.hasBookableService(),
                    checklist.publicPageReady(),
                    checklist.bookingUrl());
        }
    }
}
