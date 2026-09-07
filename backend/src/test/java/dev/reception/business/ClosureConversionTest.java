package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.ids.IdGenerator;
import dev.reception.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Local dates in, instants out — the one conversion in the system that turns a Business Closure
 * into a span of time.
 *
 * <p>Two things are being pinned here. That {@code endDate} is <em>inclusive</em>, because that is
 * what "closed the 24th to the 26th" means to the person typing it, while the stored range is
 * half-open, because that is what the availability engine needs to subtract closures without gaps
 * or double-counting. And that the conversion happens in the <em>business's</em> zone, not the
 * server's — a UTC server and a Tbilisi salon disagree by four hours about when Christmas Eve
 * starts, and the salon is right.
 */
class ClosureConversionTest {

    private static final UUID BUSINESS_ID = UUID.randomUUID();

    private ClosureService serviceFor(ZoneId zone) {
        BusinessClosureRepository closures = mock(BusinessClosureRepository.class);
        when(closures.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BusinessRepository businesses = mock(BusinessRepository.class);
        when(businesses.findById(BUSINESS_ID))
                .thenReturn(Optional.of(
                        new Business(BUSINESS_ID, "Salon Aria", "salon-aria", zone, "USD", Instant.EPOCH)));

        TenantContext tenant = mock(TenantContext.class);
        when(tenant.businessId()).thenReturn(BUSINESS_ID);

        IdGenerator ids = mock(IdGenerator.class);
        when(ids.newId()).thenAnswer(invocation -> UUID.randomUUID());

        return new ClosureService(
                closures,
                businesses,
                new EmptyAppointmentImpact(),
                tenant,
                ids,
                Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a single closed day spans that whole day in the business's zone")
    void one_day_spans_that_day() {
        BusinessClosure closure = serviceFor(ZoneId.of("Asia/Tbilisi"))
                .create(LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-25"), "Christmas")
                .closure();

        // Tbilisi is UTC+4 year round, so the local day begins at 20:00 the previous day in UTC.
        assertThat(closure.startsAt()).isEqualTo(Instant.parse("2026-12-24T20:00:00Z"));
        assertThat(closure.endsAt()).isEqualTo(Instant.parse("2026-12-25T20:00:00Z"));
    }

    @Test
    @DisplayName("the last day is inclusive — the range ends when the next day begins")
    void end_date_is_inclusive() {
        BusinessClosure closure = serviceFor(ZoneId.of("UTC"))
                .create(LocalDate.parse("2026-12-24"), LocalDate.parse("2026-12-26"), "Holidays")
                .closure();

        assertThat(closure.startsAt()).isEqualTo(Instant.parse("2026-12-24T00:00:00Z"));
        assertThat(closure.endsAt()).isEqualTo(Instant.parse("2026-12-27T00:00:00Z"));
    }

    @Test
    @DisplayName("two adjacent closures meet exactly, leaving no gap and no overlap")
    void adjacent_closures_meet() {
        ClosureService service = serviceFor(ZoneId.of("Europe/London"));

        BusinessClosure first = service.create(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-03"), null)
                .closure();
        BusinessClosure second = service.create(LocalDate.parse("2026-05-04"), LocalDate.parse("2026-05-05"), null)
                .closure();

        assertThat(first.endsAt()).isEqualTo(second.startsAt());
    }

    @Test
    @DisplayName("a closure over a spring-forward boundary is 23 hours long, not 24")
    void spans_a_daylight_saving_gap() {
        // London moves to BST at 01:00 UTC on 29 March 2026, so that local day has 23 hours.
        BusinessClosure closure = serviceFor(ZoneId.of("Europe/London"))
                .create(LocalDate.parse("2026-03-29"), LocalDate.parse("2026-03-29"), "Refurbishment")
                .closure();

        assertThat(closure.startsAt()).isEqualTo(Instant.parse("2026-03-29T00:00:00Z"));
        assertThat(closure.endsAt()).isEqualTo(Instant.parse("2026-03-29T23:00:00Z"));
        assertThat(java.time.Duration.between(closure.startsAt(), closure.endsAt()).toHours())
                .isEqualTo(23);
    }

    @Test
    @DisplayName("a closure over an autumn fall-back boundary is 25 hours long")
    void spans_a_daylight_saving_overlap() {
        // London returns to GMT at 02:00 local on 25 October 2026; that local day has 25 hours.
        BusinessClosure closure = serviceFor(ZoneId.of("Europe/London"))
                .create(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-25"), null)
                .closure();

        assertThat(java.time.Duration.between(closure.startsAt(), closure.endsAt()).toHours())
                .isEqualTo(25);
    }

    @Test
    @DisplayName("a closure starting on a day whose midnight does not exist begins at the first instant that does")
    void resolves_a_midnight_that_daylight_saving_skipped() {
        // Santiago's clocks jump from 23:59:59 to 01:00 on 6 September 2026: there is no 00:00.
        // atStartOfDay resolves forward rather than throwing, which is the behaviour a closure
        // wants — the business is closed from the first moment of a day that begins at 01:00.
        BusinessClosure closure = serviceFor(ZoneId.of("America/Santiago"))
                .create(LocalDate.parse("2026-09-06"), LocalDate.parse("2026-09-06"), null)
                .closure();

        assertThat(closure.startsAt().atZone(ZoneId.of("America/Santiago")).getHour()).isEqualTo(1);
        assertThat(closure.startsAt()).isBefore(closure.endsAt());
    }

    @Test
    @DisplayName("a last day before the first day is rejected, naming the field the owner can fix")
    void rejects_an_inverted_range() {
        assertThatThrownBy(() -> serviceFor(ZoneId.of("UTC"))
                        .create(LocalDate.parse("2026-12-26"), LocalDate.parse("2026-12-24"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).singleElement().satisfies(error -> assertThat(error.field())
                            .isEqualTo("endDate"));
                });
    }

    @Test
    @DisplayName("a single-day closure is not an inverted range")
    void allows_a_single_day() {
        assertThat(serviceFor(ZoneId.of("UTC"))
                        .create(LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-25"), null)
                        .closure())
                .isNotNull();
    }
}
